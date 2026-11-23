package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.AtsType
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
import java.io.IOException
import java.time.Instant

class GreenhouseSourceTest {
    private val server = MockWebServer()
    private val preset =
        SearchPreset(1, "Android", listOf("android"), emptyList(), "", JobFilters(), RunSchedule())

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun source(slug: String = "stripe") = GreenhouseSource(slug, "Fallback Co", OkHttpClient(), server.url("/"))

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/greenhouse/stripe-jobs.json")) { "fixture missing" }
            .bufferedReader()
            .use { it.readText() }

    private fun reply(
        code: Int = 200,
        body: String = fixture(),
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build(),
    )

    @Test
    fun theKeyNamesTheBoard() {
        assertEquals("greenhouse:stripe", source().key)
    }

    @Test
    fun postingsAreReadFromTheRecordedBoardReply() =
        runTest {
            reply()

            val postings = source().fetch(null, preset)

            assertEquals("three real ones and two usable hand-made ones; the blank title is skipped", 5, postings.size)
            val first = postings.first()
            assertTrue(first.externalId.all { it.isDigit() })
            assertEquals("Stripe", first.company)
            assertEquals(AtsType.GREENHOUSE, first.atsType)
            assertTrue(first.url.startsWith("https://stripe.com/jobs/"))
            assertTrue(first.location?.isNotBlank() == true)
            assertTrue("posting date comes from first_published", first.postedAt != null)
        }

    @Test
    fun theRequestAsksForContentAndNeedsNoKey() =
        runTest {
            reply()

            source().fetch(null, preset)

            val request = server.takeRequest()
            assertEquals(
                "/v1/boards/stripe/jobs?content=true",
                request.url.encodedPath + "?" + request.url.encodedQuery,
            )
            assertNull(request.headers["Authorization"])
        }

    @Test
    fun theDescriptionIsUnescapedHtml() =
        runTest {
            reply()

            val description =
                source()
                    .fetch(null, preset)
                    .first()
                    .description
                    .orEmpty()

            assertTrue(description, description.startsWith("<h2>Who we are</h2>"))
            assertTrue(description, description.contains("R&D & \"quotes\"."))
        }

    @Test
    fun theCompanyFallsBackToTheWatchlistName() =
        runTest {
            reply()

            val fallback = source().fetch(null, preset).first { it.externalId == "900001" }

            assertEquals("Fallback Co", fallback.company)
            assertNull(fallback.description)
            assertEquals(Instant.parse("2026-11-01T14:00:00Z"), fallback.postedAt)
        }

    @Test
    fun postingsWithoutATitleOrWithBadDatesAreHandledNotFatal() =
        runTest {
            reply()

            val postings = source().fetch(null, preset)

            assertTrue("blank title skipped", postings.none { it.externalId == "900002" })
            val badDate = postings.first { it.externalId == "900003" }
            assertNull(badDate.postedAt)
            assertNull(badDate.location)
        }

    @Test
    fun anEmptyBoardIsAnEmptyList() =
        runTest {
            reply(body = """{"jobs":[],"meta":{"total":0}}""")

            assertTrue(source().fetch(null, preset).isEmpty())
        }

    @Test
    fun aFailedFetchThrowsInsteadOfReturningAnEmptyList() =
        runTest {
            for (code in listOf(404, 429, 500, 503)) {
                reply(code = code, body = "nope")
                try {
                    source().fetch(null, preset)
                    fail("HTTP $code should throw")
                } catch (expected: IOException) {
                    assertTrue(expected.message, expected.message!!.contains("HTTP $code"))
                }
            }
        }

    @Test
    fun aReplyWithNoJobListIsNotABoard() =
        runTest {
            reply(body = """{"message":"Welcome"}""")
            reply(body = "{}")

            repeat(2) {
                try {
                    source().fetch(null, preset)
                    fail("a reply without jobs must not be an empty board")
                } catch (expected: IOException) {
                    assertTrue(expected.message, expected.message!!.contains("cannot read"))
                }
            }
        }

    @Test
    fun checkBoardDoesNotCallAnUnrelatedJsonReplyABoard() =
        runTest {
            reply(body = """{"message":"Welcome"}""")

            assertTrue(source().checkBoard() is BoardCheck.Failed)
        }

    @Test
    fun theKeyIgnoresCase() {
        assertEquals("greenhouse:stripe", GreenhouseSource("Stripe", "Stripe", OkHttpClient()).key)
    }

    @Test
    fun anUnreadableReplyThrows() =
        runTest {
            reply(body = "<html>maintenance</html>")

            try {
                source().fetch(null, preset)
                fail("expected an IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message, expected.message!!.contains("cannot read"))
            }
        }

    @Test
    fun checkBoardSaysValidWithTheJobCount() =
        runTest {
            reply()

            assertEquals("counts what a fetch would return", BoardCheck.Valid(5), source().checkBoard())
            assertEquals(
                "the check does not download descriptions",
                "/v1/boards/stripe/jobs",
                server.takeRequest().url.encodedPath,
            )
        }

    @Test
    fun checkBoardSaysNotFoundForAnUnknownSlug() =
        runTest {
            reply(code = 404, body = """{"status":404,"error":"Job not found"}""")

            assertEquals(BoardCheck.NotFound, source("no-such-board").checkBoard())
        }

    @Test
    fun checkBoardReportsOtherFailuresWithoutThrowing() =
        runTest {
            reply(code = 503, body = "")
            reply(body = "not json")

            assertTrue(source().checkBoard() is BoardCheck.Failed)
            assertTrue(source().checkBoard() is BoardCheck.Failed)
        }

    @Test(expected = IllegalArgumentException::class)
    fun aSlugCannotInjectAPath() {
        GreenhouseSource("../admin", "Co", OkHttpClient())
    }
}
