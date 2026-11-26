package com.coderarchitech.jobassistant.sources

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.StringReader

/** Reads the items of an RSS 2.0 feed with a pull parser. Shared by the RSS sources. */
internal object RssReader {
    private const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"

    /**
     * Every `<item>` as a map of the [fields] among its direct child elements (the first of each name, with
     * the text of anything nested inside it). Throws [XmlPullParserException] if there is no `<channel>`.
     * The parser is relaxed where it supports that, so an HTML entity such as `&nbsp;` that a feed forgot
     * to escape does not lose the whole feed. Namespaces are not processed, so `media:content` is just a name.
     */
    fun readItems(
        parser: XmlPullParser,
        body: String,
        fields: Set<String>,
    ): List<Map<String, String>> {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        runCatching { parser.setFeature(FEATURE_RELAXED, true) }
        parser.setInput(StringReader(body))
        val items = mutableListOf<Map<String, String>>()
        var sawChannel = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "channel" -> sawChannel = true
                "item" -> items += readItem(parser, fields)
            }
        }
        if (!sawChannel) throw XmlPullParserException("No <channel> element")
        return items
    }

    /** Reads one `<item>`, leaving the parser on its end tag. Nested elements never overwrite its own fields. */
    private fun readItem(
        parser: XmlPullParser,
        fields: Set<String>,
    ): Map<String, String> {
        val itemDepth = parser.depth
        val values = mutableMapOf<String, String>()
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == itemDepth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) throw XmlPullParserException("Unclosed <item>")
            if (parser.eventType == XmlPullParser.START_TAG && parser.depth == itemDepth + 1) {
                val name = parser.name
                val text = readAllText(parser)
                if (name in fields) values.putIfAbsent(name, text)
            }
        }
        return values
    }

    /** The text of the current element and everything inside it, leaving the parser on its end tag. */
    private fun readAllText(parser: XmlPullParser): String {
        val depth = parser.depth
        val text = StringBuilder()
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            when (parser.eventType) {
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_DOCUMENT -> throw XmlPullParserException("Unclosed element")
            }
        }
        return text.toString()
    }
}
