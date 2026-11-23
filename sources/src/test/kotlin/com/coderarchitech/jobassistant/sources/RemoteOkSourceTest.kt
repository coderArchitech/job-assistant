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
import java.io.IOException
import java.time.Instant

class RemoteOkSourceTest {
    private val server = MockWebServer()
    private val preset = SearchPreset(1, "Android", listOf("android"), emptyList(), "", JobFilters(), RunSchedule())

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun source() = RemoteOkSource(OkHttpClient(), server.url("/"))

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/remoteok/remoteok-feed.json")) { "fixture missing" }
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
    fun theKeyIsFixed() {
        assertEquals("remoteok", source().key)
    }

    @Test
    fun theLegalNoticeAndJunkAreSkippedAndPostingsRead() =
        runTest {
            reply()

            val postings = source().fetch(null, preset)

            // Three recorded, the salaried and the flat-pay ones; the notice, the link-less entry and junk are skipped.
            assertEquals(5, postings.size)
            val first = postings.first()
            assertTrue(first.url.startsWith("https://remoteOK.com/remote-jobs/"))
            assertEquals("remote", first.workplaceType)
            assertNull("an unset salary is no salary", first.compensation)
            assertTrue(first.postedAt != null)
        }

    @Test
    fun salaryDatesAndApplyLinksAreMapped() =
        runTest {
            reply()

            val byId = source().fetch(null, preset).associateBy { it.externalId }

            val salaried = byId.getValue("e1")
            assertEquals("\$90,000 - \$120,000", salaried.compensation)
            assertEquals(Instant.ofEpochSecond(1795000000), salaried.postedAt)
            assertEquals("https://example.test/apply", salaried.applyUrl)
            assertEquals("Worldwide", salaried.location)
            val flat = byId.getValue("e3")
            assertEquals("\$80,000", flat.compensation)
            assertEquals(Instant.parse("2026-11-01T10:00:00Z"), flat.postedAt)
            assertNull(flat.applyUrl)
        }

    @Test
    fun theRequestNeedsNoKey() =
        runTest {
            reply()

            source().fetch(null, preset)

            val request = server.takeRequest()
            assertEquals("/api", request.url.encodedPath)
            assertNull(request.headers["Authorization"])
        }

    @Test
    fun aFailedFetchThrowsInsteadOfReturningAnEmptyList() =
        runTest {
            for (code in listOf(403, 429, 500)) {
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
    fun aReplyThatIsNotAnArrayIsNotAFeed() =
        runTest {
            reply(body = """{"jobs":[]}""")
            reply(body = "not json")

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
    fun theCreditNamesAndLinksToRemoteOk() {
        assertEquals("Remote OK", RemoteOkCredit.NAME)
        assertEquals("https://remoteok.com", RemoteOkCredit.URL)
    }
}
