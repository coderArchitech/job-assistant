package com.coderarchitech.jobassistant.sources

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSupportTest {
    @Test
    fun workplaceSpellingsAreUnified() {
        assertEquals("remote", "Remote".normalizedWorkplace())
        assertEquals("remote", " remote ".normalizedWorkplace())
        assertEquals("hybrid", "HYBRID".normalizedWorkplace())
        for (spelling in listOf("OnSite", "on-site", "On_Site", "on site", "ONSITE")) {
            assertEquals(spelling, "on-site", spelling.normalizedWorkplace())
        }
    }

    @Test
    fun noInformationIsNull() {
        assertNull("unspecified".normalizedWorkplace())
        assertNull("Unspecified".normalizedWorkplace())
        assertNull(" ".normalizedWorkplace())
        assertNull(null.normalizedWorkplace())
    }

    @Test
    fun anUnknownWordIsKeptInLowerCase() {
        assertEquals("flexible", "Flexible".normalizedWorkplace())
    }

    @Test
    fun cleanTrimsAndDropsBlank() {
        assertEquals("a b", "  a b ".clean())
        assertNull("   ".clean())
        assertNull(null.clean())
    }

    @Test
    fun aReplyWithinTheLimitIsRead() =
        runTest {
            val server = mockwebserver3.MockWebServer().apply { start() }
            try {
                server.enqueue(
                    mockwebserver3.MockResponse
                        .Builder()
                        .code(200)
                        .body("small")
                        .build(),
                )
                OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                    assertEquals("small", it.readText(maxBytes = 5))
                }
            } finally {
                server.close()
            }
        }

    @Test
    fun aReplyOverTheLimitIsRefusedNotBuffered() =
        runTest {
            val server = mockwebserver3.MockWebServer().apply { start() }
            try {
                server.enqueue(
                    mockwebserver3.MockResponse
                        .Builder()
                        .code(200)
                        .body("too long")
                        .build(),
                )
                OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                    try {
                        it.readText(maxBytes = 5)
                        org.junit.Assert.fail("expected the reply to be refused")
                    } catch (expected: java.io.IOException) {
                        org.junit.Assert.assertTrue(expected.message, expected.message!!.contains("larger"))
                    }
                }
            } finally {
                server.close()
            }
        }
}
