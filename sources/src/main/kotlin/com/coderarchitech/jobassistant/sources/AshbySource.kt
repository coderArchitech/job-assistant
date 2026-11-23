package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.network.NetworkJson
import com.coderarchitech.jobassistant.core.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * One company's Ashby job board:
 * `GET api.ashbyhq.com/posting-api/job-board/{board}?includeCompensation=true`. No key is needed, and the
 * board answers with every posting in one reply, so [fetch] ignores the cursor and [SourceSync] drops the
 * postings the app already holds.
 *
 * Checked against Ashby's public Job Postings API documentation and a live board. A posting has `id`,
 * `title`, `location`, `secondaryLocations`, `workplaceType` (`OnSite`, `Remote` or `Hybrid`),
 * `publishedAt`, `isListed`, `jobUrl`, `applyUrl`, `descriptionHtml` and, with `includeCompensation`, a
 * `compensation` object. It does not name the company, and a posting with `isListed` false is reachable
 * only by its direct link, so it is not a public opening and is skipped. An unknown board is HTTP 404.
 *
 * @param board the board name, such as `Ashby` in `jobs.ashbyhq.com/Ashby`.
 * @param companyName the company the postings belong to, since Ashby does not say.
 * @param client the `NetworkGroup.JOB_SOURCES` client.
 */
class AshbySource(
    private val board: String,
    private val companyName: String,
    private val client: OkHttpClient,
    baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) : JobSource {
    init {
        require(companyName.isNotBlank()) { "A board needs a company name" }
        require(BOARD.matches(board)) { "An Ashby board name is letters, digits, '.', '-' and '_'" }
    }

    // Board names are not case sensitive on the hosted page, so one board must not get two keys.
    override val key: String = "ashby:${board.lowercase()}"

    private val boardUrl =
        baseUrl
            .newBuilder()
            .addPathSegments("posting-api/job-board")
            .addPathSegment(board)
            .addQueryParameter("includeCompensation", "true")
            .build()

    override suspend fun fetch(
        since: SourceCursor?,
        preset: SearchPreset,
    ): List<RawPosting> = parse(download())

    /** Asks Ashby whether this board exists, without throwing. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun checkBoard(): BoardCheck =
        try {
            client.newCall(Request.Builder().url(boardUrl).build()).await().use { response ->
                when {
                    response.code == HTTP_NOT_FOUND -> BoardCheck.NotFound
                    !response.isSuccessful -> BoardCheck.Failed("Ashby answered HTTP ${response.code}")
                    else -> BoardCheck.Valid(parse(response.readText()).size)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            BoardCheck.Failed(e.message ?: "Network error")
        }

    private suspend fun download(): String =
        client.newCall(Request.Builder().url(boardUrl).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("Ashby board $board answered HTTP ${response.code}")
            response.readText()
        }

    /**
     * Maps the reply, skipping postings that cannot become a [RawPosting] and unlisted ones. Throws if the
     * reply is unreadable, which includes a reply with no job list.
     */
    private fun parse(body: String): List<RawPosting> {
        val reply =
            try {
                NetworkJson.decodeFromString<BoardDto>(body)
            } catch (e: SerializationException) {
                throw IOException("Ashby board $board sent a reply this app cannot read", e)
            }
        return reply.jobs.filter { it.isListed != false }.mapNotNull { it.toPosting() }
    }

    private fun JobDto.toPosting(): RawPosting? {
        val postingTitle = title.clean().orEmpty()
        val postingId = id.clean()
        // A posting with only an apply link is still an opening.
        val postingUrl = (jobUrl.clean() ?: applyUrl.clean()).orEmpty()
        if (postingId == null || postingTitle.isEmpty() || postingUrl.isEmpty()) return null
        return RawPosting(
            externalId = postingId,
            title = postingTitle,
            company = companyName,
            url = postingUrl,
            location = allLocations(),
            description = descriptionHtml?.takeIf { it.isNotBlank() },
            postedAt = publishedAt.toInstant(),
            applyUrl = applyUrl.clean(),
            compensation = compensation?.let { it.scrapeableCompensationSalarySummary.clean() ?: it.salaryFromTiers() },
            atsType = AtsType.ASHBY,
            workplaceType = workplaceType.normalizedWorkplace(),
        )
    }

    /** The main location followed by any others, so a location filter sees all of them. */
    private fun JobDto.allLocations(): String? =
        (listOf(location) + secondaryLocations.orEmpty().map { it.location })
            .mapNotNull { it.clean() }
            .distinctBy { it.lowercase() }
            .joinToString("; ")
            .ifEmpty { null }

    /** Ashby sends an offset (`+00:00`); a time without one is taken as UTC rather than dropped. */
    private fun String?.toInstant(): Instant? =
        try {
            this?.let { OffsetDateTime.parse(it).toInstant() }
        } catch (e: DateTimeParseException) {
            try {
                this?.let { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC) }
            } catch (e2: DateTimeParseException) {
                null
            }
        }

    /**
     * The salary part of the tier summary (`$160K - $225K • Offers Equity` gives `$160K - $225K`), or null
     * when the summary names no amount, such as `Offers Equity`.
     */
    private fun CompensationDto.salaryFromTiers(): String? =
        compensationTierSummary
            .clean()
            ?.substringBefore(" • ")
            ?.clean()
            ?.takeIf { part -> part.any { it.isDigit() } }

    @Suppress("LongParameterList") // One field per part of Ashby's posting that is used.
    @Serializable
    private class JobDto(
        val id: String? = null,
        val title: String? = null,
        val location: String? = null,
        val secondaryLocations: List<SecondaryLocationDto>? = null,
        val workplaceType: String? = null,
        val isListed: Boolean? = null,
        val publishedAt: String? = null,
        val jobUrl: String? = null,
        val applyUrl: String? = null,
        val descriptionHtml: String? = null,
        val compensation: CompensationDto? = null,
    )

    @Serializable
    private class SecondaryLocationDto(
        val location: String? = null,
    )

    @Serializable
    private class CompensationDto(
        val compensationTierSummary: String? = null,
        val scrapeableCompensationSalarySummary: String? = null,
    )

    @Serializable
    private class BoardDto(
        // Required: a 200 reply without a job list is not a board, whatever else it says.
        val jobs: List<JobDto>,
    )

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.ashbyhq.com/"
        const val HTTP_NOT_FOUND = 404
        val BOARD = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}")
    }
}
