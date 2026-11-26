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
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * One We Work Remotely category feed: `GET weworkremotely.com/categories/{category}.rss`, such as
 * `remote-programming-jobs`. No key is needed and each feed holds the category's recent postings, so [fetch]
 * ignores the cursor and [SourceSync] drops postings the app already holds.
 *
 * Checked against two live feeds. Each `<item>` has `title` written as `Company: Job title`, `region`,
 * `category`, `description` (HTML, escaped inside the XML), `pubDate` (RFC 1123), `guid` and `link` (both the
 * posting's page). An item without a company, a title or a web link is skipped. Every posting is remote work.
 *
 * The feed is read with Android's [XmlPullParser], which neither reads a DTD nor expands external entities.
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
                readItems(body)
            } catch (e: XmlPullParserException) {
                throw IOException("We Work Remotely feed $category sent a reply this app cannot read", e)
            }
        return items.mapNotNull { it.toPosting() }
    }

    /** Every `<item>` as a map of its child elements' text. Throws if there is no `<channel>`. */
    private fun readItems(body: String): List<Map<String, String>> {
        val parser = newParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(body))
        val items = mutableListOf<Map<String, String>>()
        var sawChannel = false
        var item: MutableMap<String, String>? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_TAG) continue
            val name = parser.name
            when {
                parser.eventType == XmlPullParser.END_TAG && name == "item" -> {
                    item?.let(items::add)
                    item = null
                }
                parser.eventType == XmlPullParser.END_TAG -> Unit
                name == "channel" -> sawChannel = true
                name == "item" -> item = mutableMapOf()
                item != null && name in FIELDS -> item[name] = parser.nextText()
            }
        }
        if (!sawChannel) throw XmlPullParserException("No <channel> element")
        return items
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
            location = get("region").clean(),
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

    private fun isWebLink(text: String): Boolean = text.toHttpUrlOrNull() != null

    @Suppress("SwallowedException")
    private fun String?.toInstant(): Instant? =
        try {
            clean()?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
        } catch (e: DateTimeParseException) {
            null
        }

    companion object {
        /** Keys are `weworkremotely:<category>`, one per feed. */
        const val KEY_PREFIX = "weworkremotely:"
        private const val DEFAULT_BASE_URL = "https://weworkremotely.com/"
        private val CATEGORY = Regex("[a-z0-9][a-z0-9-]{0,99}")
        private val FIELDS = setOf("title", "region", "description", "pubDate", "guid", "link")
    }
}
