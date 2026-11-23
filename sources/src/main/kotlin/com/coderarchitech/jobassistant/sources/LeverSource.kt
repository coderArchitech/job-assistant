package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.network.NetworkJson
import com.coderarchitech.jobassistant.core.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant

/** Where a Lever site is hosted. Lever keeps European accounts on a separate host. */
enum class LeverRegion(
    internal val baseUrl: String,
    internal val keyPrefix: String,
) {
    /** `api.lever.co` */
    GLOBAL("https://api.lever.co/", "lever"),

    /** `api.eu.lever.co` */
    EU("https://api.eu.lever.co/", "lever-eu"),
}

/**
 * One company's Lever postings page: `GET api.lever.co/v0/postings/{site}?mode=json&skip=&limit=`
 * (`api.eu.lever.co` for European sites). No key is needed to list postings; applying needs the company's
 * own key, so the app applies through the hosted form instead.
 *
 * Checked against Lever's Postings API documentation and a live site: the reply is a JSON array of
 * postings, `skip` and `limit` page through it, and an unknown site answers HTTP 404. A posting has `id`,
 * `text` (the title), `categories` (location, team, department, commitment), `createdAt` in epoch
 * milliseconds, `description` (HTML), `lists`, `additional`, `hostedUrl`, `applyUrl`, `workplaceType` and
 * sometimes `salaryRange`. It does not name the company.
 *
 * The page size and maximum are not documented, so [fetch] asks for 100 at a time and keeps going until a
 * page adds nothing new.
 *
 * @param site the site name, such as `leverdemo` in `jobs.lever.co/leverdemo`.
 * @param companyName the company the postings belong to, since Lever does not say.
 * @param client the `NetworkGroup.JOB_SOURCES` client.
 */
class LeverSource(
    private val site: String,
    private val companyName: String,
    private val client: OkHttpClient,
    private val region: LeverRegion = LeverRegion.GLOBAL,
    baseUrl: HttpUrl = region.baseUrl.toHttpUrl(),
) : JobSource {
    init {
        require(companyName.isNotBlank()) { "A site needs a company name" }
        require(SITE.matches(site)) { "A Lever site name is letters, digits, '-' and '_'" }
    }

    // Site names are not case sensitive, so one site must not get two keys.
    override val key: String = "${region.keyPrefix}:${site.lowercase()}"

    private val postingsUrl =
        baseUrl
            .newBuilder()
            .addPathSegments("v0/postings")
            .addPathSegment(site)
            .addQueryParameter("mode", "json")
            .build()

    override suspend fun fetch(
        since: SourceCursor?,
        preset: SearchPreset,
    ): List<RawPosting> = fetchAll()

    private suspend fun fetchAll(): List<RawPosting> {
        val seen = HashSet<String>()
        val postings = ArrayList<RawPosting>()
        for (page in 0 until MAX_PAGES) {
            val items = parsePage(download(page * PAGE_SIZE))
            if (items.isEmpty()) return postings
            // A page whose ids were all seen before means the server ignored `skip` and repeated a page.
            val fresh = items.filter { it.id == null || seen.add(it.id) }
            if (fresh.isEmpty()) return postings
            fresh.mapNotNullTo(postings) { it.toPosting() }
        }
        throw IOException("Lever site $site has more than ${MAX_PAGES * PAGE_SIZE} postings; refusing a partial list")
    }

    /**
     * Asks Lever whether this site exists, without throwing. A check button reports any failure, and a 404
     * is its answer, so the exceptions are not rethrown.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun checkBoard(): BoardCheck =
        try {
            // One page is enough to know the site exists; a large site is not downloaded in full to say so.
            BoardCheck.Valid(parsePage(download(0)).mapNotNull { it.toPosting() }.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SiteNotFoundException) {
            BoardCheck.NotFound
        } catch (e: IOException) {
            BoardCheck.Failed(e.message ?: "Network error")
        }

    /** Lever has no site with that name. */
    private class SiteNotFoundException(
        site: String,
    ) : IOException("Lever site $site was not found")

    private fun pageUrl(skip: Int): HttpUrl =
        postingsUrl
            .newBuilder()
            .addQueryParameter("skip", skip.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .build()

    private suspend fun download(skip: Int): String =
        client.newCall(Request.Builder().url(pageUrl(skip)).build()).await().use { response ->
            if (response.code == HTTP_NOT_FOUND) throw SiteNotFoundException(site)
            if (!response.isSuccessful) throw IOException("Lever site $site answered HTTP ${response.code}")
            readBody(response)
        }

    private suspend fun readBody(response: Response): String = withContext(Dispatchers.IO) { response.body.string() }

    /** Throws if the reply is not a list of postings. */
    private fun parsePage(body: String): List<PostingDto> =
        try {
            NetworkJson.decodeFromString<List<PostingDto>>(body)
        } catch (e: SerializationException) {
            throw IOException("Lever site $site sent a reply this app cannot read", e)
        }

    private fun PostingDto.toPosting(): RawPosting? {
        val postingTitle = text.clean().orEmpty()
        val postingUrl = hostedUrl.clean().orEmpty()
        if (id == null || postingTitle.isEmpty() || postingUrl.isEmpty()) return null
        return RawPosting(
            externalId = id,
            title = postingTitle,
            company = companyName,
            url = postingUrl,
            location = categories?.location.clean() ?: categories?.allLocations?.firstNotNullOfOrNull { it.clean() },
            description = fullDescription(),
            postedAt = createdAt?.let { Instant.ofEpochMilli(it) },
            applyUrl = applyUrl.clean(),
            compensation = salaryText(),
            atsType = AtsType.LEVER,
            workplaceType = workplaceType.clean()?.lowercase()?.takeIf { it != UNSPECIFIED },
        )
    }

    /** The opening text, the lists (responsibilities, requirements) and the closing text, as HTML. */
    private fun PostingDto.fullDescription(): String? {
        val parts =
            buildList {
                description?.let(::add)
                lists.orEmpty().filter { !it.content.isNullOrBlank() }.forEach {
                    val heading =
                        it.text
                            .clean()
                            ?.let { text -> "<h3>$text</h3>" }
                            .orEmpty()
                    add("$heading<ul>${it.content}</ul>")
                }
                additional?.let(::add)
            }.filter { it.isNotBlank() }
        return parts.joinToString("\n").ifEmpty { null }
    }

    private fun PostingDto.salaryText(): String? =
        salaryDescriptionPlain.clean() ?: salaryRange?.let { range ->
            if (range.min == null && range.max == null) {
                null
            } else {
                // Lever's interval tokens look like `per-year-salary` or `per-hour-wage`.
                val interval =
                    range.interval
                        .clean()
                        ?.removeSuffix("-salary")
                        ?.removeSuffix("-wage")
                        ?.replace('-', ' ')
                val amounts = "${range.min.show()}-${range.max.show()}"
                listOfNotNull(range.currency.clean(), amounts, interval).joinToString(" ")
            }
        }

    @Suppress("LongParameterList") // One field per part of Lever's posting that is used.
    @Serializable
    private class PostingDto(
        val id: String? = null,
        val text: String? = null,
        val categories: CategoriesDto? = null,
        val createdAt: Long? = null,
        val description: String? = null,
        val lists: List<ListDto>? = null,
        val additional: String? = null,
        val hostedUrl: String? = null,
        val applyUrl: String? = null,
        val workplaceType: String? = null,
        val salaryRange: SalaryDto? = null,
        val salaryDescriptionPlain: String? = null,
    )

    @Serializable
    private class CategoriesDto(
        val location: String? = null,
        val allLocations: List<String>? = null,
    )

    @Serializable
    private class ListDto(
        val text: String? = null,
        val content: String? = null,
    )

    @Serializable
    private class SalaryDto(
        val currency: String? = null,
        val interval: String? = null,
        // Doubles, so a fractional amount cannot make the whole site unreadable.
        val min: Double? = null,
        val max: Double? = null,
    )

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 100
        const val HTTP_NOT_FOUND = 404
        const val UNSPECIFIED = "unspecified"
        val SITE = Regex("[A-Za-z0-9_-]{1,100}")
    }
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** 85000.0 shows as 85000; a missing bound shows as nothing. */
private fun Double?.show(): String =
    this
        ?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
        .orEmpty()
