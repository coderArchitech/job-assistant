package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.network.NetworkJson
import com.coderarchitech.jobassistant.core.network.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * What Remote OK's terms ask of anyone showing its jobs: mention Remote OK as the source and link back to it
 * (a normal link, not `nofollow`). Screens that show a posting from [RemoteOkSource.KEY] must show this.
 */
object RemoteOkCredit {
    const val NAME = "Remote OK"
    const val URL = "https://remoteok.com"
}

/**
 * Remote OK's public feed: `GET remoteok.com/api`. No key is needed and the reply is one JSON array of about
 * a hundred recent postings, so [fetch] ignores the cursor and the preset and [SourceSync] drops postings the
 * app already holds.
 *
 * Checked against the live feed. The first element is not a posting: it holds `legal`, the API terms, and is
 * skipped along with anything else that has no id, title, company or link. A posting has `id`, `epoch`,
 * `date`, `company`, `position`, `description` (HTML), `location`, `apply_url`, `url`, `salary_min` and
 * `salary_max` (0 when not given). Every posting is remote work.
 *
 * @param client the `NetworkGroup.JOB_SOURCES` client.
 */
class RemoteOkSource(
    private val client: OkHttpClient,
    baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) : JobSource {
    override val key: String = KEY

    private val feedUrl = baseUrl.newBuilder().addPathSegment("api").build()

    override suspend fun fetch(
        since: SourceCursor?,
        preset: SearchPreset,
    ): List<RawPosting> = parse(download())

    private suspend fun download(): String =
        client.newCall(Request.Builder().url(feedUrl).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("Remote OK answered HTTP ${response.code}")
            response.readText()
        }

    /** Throws if the reply is not a JSON array; the notice and unusable entries are skipped. */
    private fun parse(body: String): List<RawPosting> {
        val entries =
            try {
                NetworkJson.parseToJsonElement(body) as? JsonArray
            } catch (e: SerializationException) {
                throw IOException("Remote OK sent a reply this app cannot read", e)
            } ?: throw IOException("Remote OK sent a reply this app cannot read")
        return entries.mapNotNull { element -> (element as? JsonObject)?.let(::decode)?.toPosting() }
    }

    // One odd entry must not lose the other ninety-nine.
    @Suppress("SwallowedException")
    private fun decode(element: JsonObject): JobDto? =
        try {
            NetworkJson.decodeFromJsonElement(JobDto.serializer(), element)
        } catch (e: SerializationException) {
            null
        }

    @Suppress("ReturnCount") // One early exit per required field reads better than a nested null check.
    private fun JobDto.toPosting(): RawPosting? {
        val postingId = id?.content.clean() ?: return null
        val postingTitle = position.clean() ?: return null
        val postingUrl = listOf(url, applyUrl).firstNotNullOfOrNull { it.clean()?.takeIf(::isWebLink) } ?: return null
        val postingCompany = company.clean() ?: return null
        return RawPosting(
            externalId = postingId,
            title = postingTitle,
            company = postingCompany,
            url = postingUrl,
            location = location.clean(),
            description = description?.takeIf { it.isNotBlank() },
            postedAt = epoch?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) } ?: date.toInstant(),
            applyUrl = applyUrl.clean()?.takeIf { it != postingUrl && isWebLink(it) },
            compensation = salary(),
            workplaceType = "remote",
        )
    }

    private fun JobDto.salary(): String? {
        // US dollars a year; 0 means not given, and the two figures may come in either order.
        val figures = listOfNotNull(salaryMin, salaryMax).filter { it > 0 }.map { it.toLong() }
        val low = figures.minOrNull() ?: return null
        val high = figures.max()
        val money = NumberFormat.getIntegerInstance(Locale.US)
        return if (high > low) "\$${money.format(low)} - \$${money.format(high)}" else "\$${money.format(low)}"
    }

    private fun isWebLink(text: String): Boolean = text.toHttpUrlOrNull() != null

    @Suppress("SwallowedException")
    private fun String?.toInstant(): Instant? =
        try {
            this?.let { OffsetDateTime.parse(it).toInstant() }
        } catch (e: DateTimeParseException) {
            null
        }

    @Suppress("LongParameterList") // One field per part of Remote OK's posting that is used.
    @Serializable
    private class JobDto(
        // A string today; read as text whichever way the feed writes it.
        val id: JsonPrimitive? = null,
        val epoch: Long? = null,
        val date: String? = null,
        val company: String? = null,
        val position: String? = null,
        val description: String? = null,
        val location: String? = null,
        @SerialName("apply_url") val applyUrl: String? = null,
        val url: String? = null,
        @SerialName("salary_min") val salaryMin: Double? = null,
        @SerialName("salary_max") val salaryMax: Double? = null,
    )

    companion object {
        /** The source key; there is one Remote OK feed. */
        const val KEY = "remoteok"
        private const val DEFAULT_BASE_URL = "https://remoteok.com/"
    }
}
