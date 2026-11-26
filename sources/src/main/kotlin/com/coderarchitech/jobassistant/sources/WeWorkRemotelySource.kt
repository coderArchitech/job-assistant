package com.coderarchitech.jobassistant.sources

import android.util.Xml
import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.network.await
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One We Work Remotely category feed: `GET weworkremotely.com/categories/{category}.rss`, such as
 * `remote-programming-jobs`. No key is needed and each feed holds the category's recent postings, so [fetch]
 * ignores the cursor and [SourceSync] drops postings the app already holds.
 *
 * Checked against two live feeds. Each `<item>` has `title` written as `Company: Job title`, `region`,
 * `category`, `description` (HTML, escaped inside the XML), `pubDate` (RFC 1123), `guid` and `link` (both the
 * posting's page); some feeds add `country`, `state`, `type` and `skills`. An item without a company, a
 * title or a web link is skipped. Every posting is remote work.
 *
 * The feed is read by [RssReader] with Android's [XmlPullParser], which neither reads a DTD nor expands
 * external entities.
 *
 * @param category the feed's slug, such as `remote-programming-jobs`.
 * @param client the `NetworkGroup.JOB_SOURCES` client.
 */
class WeWorkRemotelySource(
    private val category: String,
    private val client: OkHttpClient,
    baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val newParser: () -> XmlPullParser = { Xml.newPullParser() },
) : JobSource {
    init {
        require(CATEGORY.matches(category)) { "A We Work Remotely category is lower-case letters, digits and '-'" }
    }

    override val key: String = "$KEY_PREFIX$category"

    private val feedUrl =
        baseUrl
            .newBuilder()
            .addPathSegment("categories")
            .addPathSegment("$category.rss")
            .build()

    override suspend fun fetch(
        since: SourceCursor?,
        preset: SearchPreset,
    ): List<RawPosting> = parse(download())

    private suspend fun download(): String =
        client.newCall(Request.Builder().url(feedUrl).build()).await().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "We Work Remotely feed $category answered HTTP ${response.code}",
                )
            }
            response.readText()
        }

    /** Throws if the reply is not an RSS feed; unusable items are skipped. */
    private fun parse(body: String): List<RawPosting> {
        val items =
            try {
                RssReader.readItems(newParser(), body, FIELDS)
            } catch (e: XmlPullParserException) {
                throw IOException("We Work Remotely feed $category sent a reply this app cannot read", e)
            }
        return items.mapNotNull { it.toPosting() }
    }

    @Suppress("ReturnCount") // One early exit per required part reads better than a nested null check.
    private fun Map<String, String>.toPosting(): RawPosting? {
        val (company, title) = splitTitle(get("title")) ?: return null
        val link = listOf(get("link"), get("guid")).firstNotNullOfOrNull { it.clean()?.takeIf(::isWebLink) }
        if (link == null) return null
        return RawPosting(
            externalId = get("guid").clean() ?: link,
            title = title,
            company = company,
            url = link,
            location = location(),
            description = get("description")?.takeIf { it.isNotBlank() },
            postedAt = get("pubDate").toInstant(),
            workplaceType = "remote",
        )
    }

    /** `Company: Job title` to its two parts; the first `: ` splits, since titles may hold colons too. */
    private fun splitTitle(text: String?): Pair<String, String>? {
        val full = text.clean() ?: return null
        val company = full.substringBefore(": ", "").clean()
        val title = full.substringAfter(": ", "").clean()
        return if (company == null || title == null) null else company to title
    }

    /**
     * The region followed by the country and state when the feed names them, so a posting that is
     * "Anywhere in the World" but limited to one country is not taken as worldwide. Flag emoji are dropped.
     */
    private fun Map<String, String>.location(): String? =
        listOf("region", "country", "state")
            .mapNotNull { get(it)?.replace(FLAG, "").clean() }
            .distinctBy { it.lowercase() }
            .joinToString("; ")
            .ifEmpty { null }

    private fun isWebLink(text: String): Boolean = text.toHttpUrlOrNull() != null

    /** RFC 822 dates, as RSS uses; also accepts zone names such as `UTC` and a weekday that does not match. */
    private fun String?.toInstant(): Instant? {
        val text = clean() ?: return null
        val strict = runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()
        // Without the weekday, so a wrong one does not lose the date.
        val lenient = { ZonedDateTime.parse(text.replace(WEEKDAY, ""), LENIENT_DATE) }
        return (strict ?: runCatching(lenient).getOrNull())?.toInstant()
    }

    companion object {
        /** Keys are `weworkremotely:<category>`, one per feed. */
        const val KEY_PREFIX = "weworkremotely:"
        private const val DEFAULT_BASE_URL = "https://weworkremotely.com/"
        private val CATEGORY = Regex("[a-z0-9][a-z0-9-]{0,99}")
        private val FIELDS = setOf("title", "region", "country", "state", "description", "pubDate", "guid", "link")

        /** Regional indicator letters, which make up flag emoji such as the one before a country name. */
        private val FLAG = Regex("[\\x{1F1E6}-\\x{1F1FF}]")
        private val WEEKDAY = Regex("^[A-Za-z]{3}, *")

        /** `z` reads zone names such as `UTC`, `GMT` and `EST`; `Z` reads offsets such as `+0000`. */
        private val LENIENT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm[:ss] [Z][z]", Locale.US)
    }
}
