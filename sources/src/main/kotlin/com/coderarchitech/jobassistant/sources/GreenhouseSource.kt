package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.network.NetworkJson
import com.coderarchitech.jobassistant.core.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * One company's Greenhouse job board: `GET boards-api.greenhouse.io/v1/boards/{slug}/jobs?content=true`.
 * No key is needed, and the board answers with every open posting in one reply, so [fetch] ignores the
 * cursor and [SourceSync] drops the postings the app already holds.
 *
 * Checked against Greenhouse's Job Board API documentation and a live board: each posting has `id`,
 * `title`, `absolute_url`, `location.name`, `updated_at`, `first_published`, `company_name` and, with
 * `content=true`, an HTML-entity-escaped `content`.
 *
 * @param slug the board's token, such as `stripe` in `boards-api.greenhouse.io/v1/boards/stripe`.
 * @param companyName used when a posting does not name its company.
 * @param client the `NetworkGroup.JOB_SOURCES` client.
 */
class GreenhouseSource(
    private val slug: String,
    private val companyName: String,
    private val client: OkHttpClient,
    baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) : JobSource {
    init {
        require(companyName.isNotBlank()) { "A board needs a company name" }
        require(SLUG.matches(slug)) { "A Greenhouse board slug is letters, digits, '-' and '_'" }
    }

    // Board tokens are not case sensitive, so one board must not get two keys.
    override val key: String = "greenhouse:${slug.lowercase()}"

    private val jobsUrl =
        baseUrl
            .newBuilder()
            .addPathSegments("v1/boards")
            .addPathSegment(slug)
            .addPathSegment("jobs")
            .addQueryParameter("content", "true")
            .build()

    override suspend fun fetch(
        since: SourceCursor?,
        preset: SearchPreset,
    ): List<RawPosting> = parse(download())

    /** Asks Greenhouse whether this board exists, without throwing. */
    @Suppress("TooGenericExceptionCaught") // A check button must report any failure, never crash.
    suspend fun checkBoard(): BoardCheck =
        try {
            client.newCall(Request.Builder().url(jobsUrl.withoutContent()).build()).await().use { response ->
                when {
                    response.code == HTTP_NOT_FOUND -> BoardCheck.NotFound
                    !response.isSuccessful -> BoardCheck.Failed("Greenhouse answered HTTP ${response.code}")
                    else -> BoardCheck.Valid(parse(response.readText()).size)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            BoardCheck.Failed(e.message ?: "Network error")
        } catch (e: SerializationException) {
            BoardCheck.Failed("Greenhouse sent a reply this app cannot read: ${e.javaClass.simpleName}")
        }

    private suspend fun download(): String =
        client.newCall(Request.Builder().url(jobsUrl).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("Greenhouse board $slug answered HTTP ${response.code}")
            response.readText()
        }

    /**
     * Maps the reply, skipping postings that cannot become a [RawPosting]. Throws if the reply is unreadable,
     * which includes a reply with no job list.
     */
    private fun parse(body: String): List<RawPosting> {
        val board =
            try {
                NetworkJson.decodeFromString<BoardDto>(body)
            } catch (e: SerializationException) {
                throw IOException("Greenhouse board $slug sent a reply this app cannot read", e)
            }
        return board.jobs.mapNotNull { it.toPosting() }
    }

    private fun JobDto.toPosting(): RawPosting? {
        val postingTitle = title?.trim().orEmpty()
        val postingUrl = absoluteUrl?.trim().orEmpty()
        if (id == null || postingTitle.isEmpty() || postingUrl.isEmpty()) return null
        return RawPosting(
            externalId = id.toString(),
            title = postingTitle,
            company = company?.trim().takeUnless { it.isNullOrEmpty() } ?: companyName,
            url = postingUrl,
            location = location?.name?.trim()?.takeIf { it.isNotEmpty() },
            description = content?.unescapeHtmlEntities(),
            postedAt = parseInstant(firstPublished) ?: parseInstant(updatedAt),
            atsType = AtsType.GREENHOUSE,
        )
    }

    private fun parseInstant(text: String?): Instant? =
        try {
            text?.let { OffsetDateTime.parse(it).toInstant() }
        } catch (e: DateTimeParseException) {
            null
        }

    private fun HttpUrl.withoutContent(): HttpUrl = newBuilder().removeAllQueryParameters("content").build()

    @Serializable
    private class BoardDto(
        // Required: a 200 reply without a job list is not a board, whatever else it says.
        val jobs: List<JobDto>,
    )

    @Serializable
    private class JobDto(
        val id: Long? = null,
        val title: String? = null,
        @SerialName("absolute_url") val absoluteUrl: String? = null,
        @SerialName("company_name") val company: String? = null,
        val location: LocationDto? = null,
        val content: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("first_published") val firstPublished: String? = null,
    )

    @Serializable
    private class LocationDto(
        val name: String? = null,
    )

    private companion object {
        const val DEFAULT_BASE_URL = "https://boards-api.greenhouse.io/"
        const val HTTP_NOT_FOUND = 404
        val SLUG = Regex("[A-Za-z0-9_-]{1,100}")
    }
}
