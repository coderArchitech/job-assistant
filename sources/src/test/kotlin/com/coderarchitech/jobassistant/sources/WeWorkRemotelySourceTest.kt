package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.JobFilters
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.SearchPreset
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

/** Runs on Robolectric so the feed is read by Android's own XmlPullParser, as on a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeWorkRemotelySourceTest {
    private val server = MockWebServer()
    private val preset = SearchPreset(1, "Android", listOf("android"), emptyList(), "", JobFilters(), RunSchedule())

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun source(category: String = PROGRAMMING) = WeWorkRemotelySource(category, OkHttpClient(), server.url("/"))

    private fun fixture(category: String): String =
        checkNotNull(javaClass.getResourceAsStream("/weworkremotely/$category.rss")) { "fixture missing" }
            .bufferedReader()
            .use { it.readText() }

    private fun reply(
        body: String,
        code: Int = 200,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build(),
    )

    private fun feed(vararg items: String) =
        """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>T</title>
        ${items.joinToString("\n")}</channel></rss>"""

    @Test
    fun theKeyNamesTheCategory() {
        assertEquals("weworkremotely:remote-programming-jobs", source().key)
    }

    @Test
    fun theProgrammingFeedIsRead() =
        runTest {
            reply(fixture(PROGRAMMING))

            val postings = source().fetch(null, preset)

            assertEquals(4, postings.size)
            val first = postings.first()
            assertEquals("EPAM Systems", first.company)
            assertEquals("Senior Web Application Security Engineer", first.title)
            assertEquals("Anywhere in the World", first.location)
            assertEquals("remote", first.workplaceType)
            assertEquals(Instant.parse("2026-11-05T21:24:38Z"), first.postedAt)
            assertEquals(
                "https://weworkremotely.com/remote-jobs/epam-systems-senior-web-application-security-engineer",
                first.url,
            )
            assertEquals(first.url, first.externalId)
            assertTrue("the escaped HTML comes back as HTML", first.description.orEmpty().contains("<strong>"))
            assertEquals("/categories/remote-programming-jobs.rss", server.takeRequest().url.encodedPath)
        }

    @Test
    fun theDesignFeedIsReadAndEntitiesAreDecoded() =
        runTest {
            reply(fixture(DESIGN))

            val postings = source(DESIGN).fetch(null, preset)

            assertEquals(4, postings.size)
            assertTrue(postings.any { it.title == "Sets&Props Concept Artist Internship" })
            assertEquals(
                "a colon after the first stays in the title",
                "Senior Director, Product Management, Agentic Platform",
                postings.first { it.company == "ZoomInfo Technologies LLC" }.title,
            )
        }

    @Test
    fun itemsThatCannotBePostingsAreSkipped() =
        runTest {
            reply(
                feed(
                    "<item><title>No company here</title><link>https://weworkremotely.com/remote-jobs/a</link></item>",
                    "<item><title>Co: </title><link>https://weworkremotely.com/remote-jobs/b</link></item>",
                    "<item><title>Co: Bad link</title><link>javascript:alert(1)</link></item>",
                    "<item><title>Co: Guid only</title><guid>https://weworkremotely.com/remote-jobs/c</guid></item>",
                    "<item><title>Co: Bare</title><link>https://weworkremotely.com/remote-jobs/d</link>" +
                        "<pubDate>not a date</pubDate><description> </description></item>",
                ),
            )

            val byTitle = source().fetch(null, preset).associateBy { it.title }

            assertEquals(setOf("Guid only", "Bare"), byTitle.keys)
            assertEquals("https://weworkremotely.com/remote-jobs/c", byTitle.getValue("Guid only").url)
            val bare = byTitle.getValue("Bare")
            assertEquals("without a guid the link is the id", bare.url, bare.externalId)
            assertNull(bare.postedAt)
            assertNull(bare.description)
            assertNull(bare.location)
        }

    @Test
    fun anExternalEntityIsNeverRead() =
        runTest {
            reply(
                """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <rss><channel><item><title>Co: &x;</title><link>https://weworkremotely.com/remote-jobs/x</link></item>
                </channel></rss>""",
            )

            try {
                val postings = source().fetch(null, preset)
                assertTrue(postings.none { it.title.contains("root:") })
            } catch (expected: IOException) {
                // Refusing the document is just as safe.
            }
        }

    @Test
    fun aFailedFetchThrows() =
        runTest {
            for (code in listOf(403, 404, 500)) {
                reply("nope", code)
                try {
                    source().fetch(null, preset)
                    fail("HTTP $code should throw")
                } catch (expected: IOException) {
                    assertTrue(expected.message, expected.message!!.contains("HTTP $code"))
                }
            }
        }

    @Test
    fun aReplyThatIsNotAFeedThrows() =
        runTest {
            reply("<html><body>Not a feed</body></html>")
            reply("{not xml")

            repeat(2) {
                try {
                    source().fetch(null, preset)
                    fail("expected an IOException")
                } catch (expected: IOException) {
                    assertTrue(expected.message, expected.message!!.contains("cannot read"))
                }
            }
        }

    @Test
    fun aCategoryCannotInjectAPath() {
        for (name in listOf("../admin", "a/b", "", "Remote-Jobs", "a.rss")) {
            try {
                WeWorkRemotelySource(name, OkHttpClient())
                fail("'$name' should be rejected")
            } catch (expected: IllegalArgumentException) {
                // Rejected, as it should be.
            }
        }
    }

    private companion object {
        const val PROGRAMMING = "remote-programming-jobs"
        const val DESIGN = "remote-design-jobs"
    }
}
