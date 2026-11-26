package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.JobFilters
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.network.NoRetry
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class RemotiveSourceTest {
    private val server = MockWebServer()
    private val preset = SearchPreset(1, "Android", listOf("android"), emptyList(), "", JobFilters(), RunSchedule())
    private val start = Instant.parse("2026-11-26T08:00:00Z")
    private var now = start
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = now
        }

    @Before
    fun begin() = server.start()

    @After
    fun stop() = server.close()

    private fun source(client: OkHttpClient = OkHttpClient()) = RemotiveSource(client, clock, server.url("/"))

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/remotive/remotive-feed.json")) { "fixture missing" }
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

    private fun cursor(at: Instant) = SourceCursor("remotive", at, "1")

    /** True when the fetch went ahead, false when the rate rules stopped it. */
    @Suppress("SwallowedException")
    private suspend fun RemotiveSource.attempt(since: SourceCursor? = null): Boolean =
        try {
            fetch(since, preset)
            true
        } catch (e: SourceThrottledException) {
            false
        }

    @Test
    fun theKeyIsFixed() {
        assertEquals("remotive", source().key)
    }

    @Test
    fun postingsAreReadFromTheRecordedFeed() =
        runTest {
            reply()

            val postings = source().fetch(null, preset)

            // Three recorded and the one with no salary; the bad link, the missing id and junk are skipped.
            assertEquals(4, postings.size)
            val first = postings.first()
            assertEquals("remote", first.workplaceType)
            assertTrue(first.url.startsWith("https://remotive.com/remote-jobs/"))
            assertTrue(first.postedAt != null)
            val bare = postings.first { it.externalId == "9001" }
            assertNull(bare.compensation)
            assertNull(bare.location)
            assertNull(bare.description)
            assertEquals(Instant.parse("2026-11-20T08:00:00Z"), bare.postedAt)
        }

    @Test
    fun aDateWithNoOffsetIsTakenAsUtcAndSalaryIsKeptAsWritten() =
        runTest {
            reply(
                body =
                    """{"jobs":[{"id":1,"url":"https://remotive.com/remote-jobs/a/1","title":"A","company_name":"Co",
                    "publication_date":"2026-11-24T10:31:32","salary":"$57K - $67K",
                    "candidate_required_location":"USA"}]}""",
            )

            val posting = source().fetch(null, preset).single()

            assertEquals(Instant.parse("2026-11-24T10:31:32Z"), posting.postedAt)
            assertEquals("$57K - $67K", posting.compensation)
            assertEquals("USA", posting.location)
        }

    @Test
    fun oneRequestAsksForTheWholeFeedWithoutRetries() =
        runTest {
            reply()
            var tagged = false
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        Interceptor { chain ->
                            tagged = chain.request().tag(NoRetry::class.java) != null
                            chain.proceed(chain.request())
                        },
                    ).build()

            source(client).fetch(null, preset)

            val request = server.takeRequest()
            assertEquals("/api/remote-jobs", request.url.encodedPath)
            assertNull("no search, so the day's allowance is not spent per keyword", request.url.query)
            assertTrue("a retry would count against the allowance", tagged)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun noNewFetchBeforeSixHoursAfterTheLastSuccess() =
        runTest {
            reply()
            val source = source()

            assertEquals(false, source.attempt(cursor(start - Duration.ofHours(5))))
            assertEquals("nothing was sent", 0, server.requestCount)

            assertTrue(source.attempt(cursor(start - Duration.ofHours(6))))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun neverMoreThanFourRequestsInADayOrTwoInAMinute() =
        runTest {
            repeat(6) { reply() }
            val source = source()

            assertTrue(source.attempt())
            now += Duration.ofSeconds(10)
            assertEquals("the second came inside half a minute", false, source.attempt())
            repeat(3) {
                now += Duration.ofMinutes(1)
                assertTrue(source.attempt())
            }
            now += Duration.ofHours(2)
            assertEquals("the fifth in a day", false, source.attempt())

            assertEquals(4, server.requestCount)

            now = start + Duration.ofHours(24) + Duration.ofSeconds(1)
            assertTrue("the first request has aged out", source.attempt())
        }

    @Test
    fun aFailedRequestStillCountsAndNothingIsRetried() =
        runTest {
            reply(code = 503, body = "down")
            val source = source()

            try {
                source.fetch(null, preset)
                fail("HTTP 503 should throw")
            } catch (expected: IOException) {
                assertTrue(expected.message, expected.message!!.contains("HTTP 503"))
            }
            assertEquals("a second try inside half a minute is refused", false, source.attempt())
            assertEquals(1, server.requestCount)
        }

    @Test
    fun aReplyWithNoJobListIsNotTheFeed() =
        runTest {
            reply(body = """{"message":"hi"}""")

            try {
                source().fetch(null, preset)
                fail("expected an IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message, expected.message!!.contains("cannot read"))
            }
        }

    @Test
    fun theCreditNamesAndLinksToRemotive() {
        assertEquals("Remotive", RemotiveCredit.NAME)
        assertEquals("https://remotive.com", RemotiveCredit.URL)
    }
}
