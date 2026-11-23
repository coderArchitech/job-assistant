package com.coderarchitech.jobassistant.sources

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlEntitiesTest {
    @Test
    fun namedEntitiesAreDecoded() {
        assertEquals(
            "<p class=\"a\">R&D's\u00A0x</p>",
            "&lt;p class=&quot;a&quot;&gt;R&amp;D&apos;s&nbsp;x&lt;/p&gt;".unescapeHtmlEntities(),
        )
    }

    @Test
    fun numericEntitiesAreDecodedInDecimalAndHex() {
        assertEquals("'\"A", "&#39;&#x22;&#X41;".unescapeHtmlEntities())
        assertEquals("\uD83D\uDE00", "&#128512;".unescapeHtmlEntities())
    }

    @Test
    fun onlyOneLevelIsUndone() {
        assertEquals("&lt;script&gt;", "&amp;lt;script&amp;gt;".unescapeHtmlEntities())
        assertEquals("&lt;script&gt;", "&#38;lt;script&#38;gt;".unescapeHtmlEntities())
    }

    @Test
    fun entitiesThatAreNotRealCharactersAreLeftAlone() {
        for (text in listOf("&#0;", "&#xD800;", "&#1114112;", "&#xZZ;", "&unknown;", "AT&T and R&D")) {
            assertEquals(text, text.unescapeHtmlEntities())
        }
    }

    @Test
    fun textWithoutEntitiesIsReturnedAsIs() {
        assertEquals("plain text", "plain text".unescapeHtmlEntities())
    }
}
