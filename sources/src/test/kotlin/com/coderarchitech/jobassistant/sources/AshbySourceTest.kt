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

class AshbySourceTest {
    private val server = MockWebServer()
    private val preset = SearchPreset(1, "Android", listOf("android"), emptyList(), "", JobFilters(), RunSchedule())

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun source(board: String = "Ashby") = AshbySource(board, "Ashby Inc", OkHttpClient(), server.url("/"))

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/ashby/ashby-board.json")) { "fixture missing" }
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
    fun theKeyNamesTheBoardIgnoringCase() {
        assertEquals("ashby:ashby", source().key)
        assertEquals("ashby:ashby", source("ASHBY").key)
    }

    @Test
    fun postingsAreReadFromTheRecordedBoardReply() =
        runTest {
            reply()

            val postings = source().fetch(null, preset)

            // Three real, the designer and the one with no dates; unlisted, blank title and no id are skipped.
            assertEquals(5, postings.size)
            val first = postings.first()
            assertEquals("7458d4e9-da2e-47bd-98cb-adfda43d42b2", first.externalId)
            assertEquals("Engineering Manager - EU", first.title)
            assertEquals("Ashby Inc", first.company)
            assertEquals(AtsType.ASHBY, first.atsType)
            assertEquals("remote", first.workplaceType)
            assertEquals(Instant.parse("2024-03-04T14:29:08.532Z"), first.postedAt)
            assertTrue(first.url.startsWith("https://jobs.ashbyhq.com/Ashby/"))
            assertTrue(first.applyUrl.orEmpty().endsWith("/application"))
            assertEquals("<p>Fixture text for Engineering Manager - EU.</p>", first.description)
        }

    @Test
    fun theRequestAsksForCompensationAndNeedsNoKey() =
        runTest {
            reply()

            source().fetch(null, preset)

            val request = server.takeRequest()
            assertEquals("/posting-api/job-board/Ashby", request.url.encodedPath)
            assertEquals("true", request.url.queryParameter("includeCompensation"))
            assertNull(request.headers["Authorization"])
        }

    @Test
    fun compensationPrefersTheSalaryOnlySummary() =
        runTest {
            reply()

            val byId = source().fetch(null, preset).associateBy { it.externalId }

            assertTrue(
                byId
                    .getValue("7458d4e9-da2e-47bd-98cb-adfda43d42b2")
                    .compensation
                    .orEmpty()
                    .contains("150K"),
            )
            assertEquals(
                "falls back to the tier summary",
                "\$100K - \$120K",
                byId.getValue("e0000000-0000-0000-0000-00000000000a").compensation,
            )
            assertNull(byId.getValue("e0000000-0000-0000-0000-00000000000e").compensation)
        }

    @Test
    fun locationsIncludeTheSecondaryOnes() =
        runTest {
            reply()

            val first = source().fetch(null, preset).first()

            assertTrue(first.location.orEmpty(), first.location.orEmpty().startsWith("Remote - European Union; "))
            assertTrue(first.location.orEmpty(), first.location.orEmpty().contains("; Spain"))
        }

    @Test
    fun unlistedPostingsAreNotPublicOpeningsAndAreSkipped() =
        runTest {
            reply()

            val ids = source().fetch(null, preset).map { it.externalId }

            assertTrue("e0000000-0000-0000-0000-00000000000b" !in ids)
        }

    @Test
    fun aPostingWithLessDataStillComesThrough() =
        runTest {
            reply()

            val bare = source().fetch(null, preset).first { it.externalId == "e0000000-0000-0000-0000-00000000000e" }

            assertNull(bare.postedAt)
            assertNull(bare.location)
            assertNull(bare.workplaceType)
            assertNull(bare.description)
        }

    @Test
    fun postingsAreReadWhateverWayAshbySpellsThings() =
        runTest {
            val body =
                """{"jobs":[
                  {"id":" a ","title":"A","isListed":true,"applyUrl":"https://x.test/a/application","workplaceType":"OnSite",
                   "publishedAt":"2026-03-04T14:29:08.532",
                   "compensation":{"compensationTierSummary":"$160K - $225K • Offers Equity • Multiple Ranges"}},
                  {"id":"b","title":"B","jobUrl":"https://x.test/b","workplaceType":"unspecified",
                   "compensation":{"compensationTierSummary":"Offers Equity"},
                   "location":"Remote - US","secondaryLocations":[{"location":"remote - us"},{"location":" "},{"location":"Canada"}]}
                ]}"""
            reply(body = body)

            val byId = source().fetch(null, preset).associateBy { it.externalId }

            val a = byId.getValue("a")
            assertEquals(
                "falls back to the apply link when there is no job link",
                "https://x.test/a/application",
                a.url,
            )
            assertEquals("on-site", a.workplaceType)
            assertEquals(Instant.parse("2026-03-04T14:29:08.532Z"), a.postedAt)
            assertEquals("only the salary part of the tier summary", "\$160K - \$225K", a.compensation)
            val b = byId.getValue("b")
            assertNull(b.workplaceType)
            assertNull("a summary with no amount is no salary", b.compensation)
            assertEquals("Remote - US; Canada", b.location)
        }

    @Test
    fun anEmptyDescriptionIsNoDescription() =
        runTest {
            reply()

            val designer =
                source()
                    .fetch(
                        null,
                        preset,
                    ).first { it.externalId == "e0000000-0000-0000-0000-00000000000a" }

            assertNull(designer.description)
            assertEquals("hybrid", designer.workplaceType)
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
    fun checkBoardCountsWhatAFetchWouldReturn() =
        runTest {
            reply()

            assertEquals(BoardCheck.Valid(5), source().checkBoard())
        }

    @Test
    fun checkBoardSaysNotFoundForAnUnknownBoard() =
        runTest {
            reply(code = 404, body = "Not Found")

            assertEquals(BoardCheck.NotFound, source("no-such-board").checkBoard())
        }

    @Test
    fun checkBoardReportsOtherFailuresWithoutThrowing() =
        runTest {
            reply(code = 503, body = "")
            reply(body = """{"message":"Welcome"}""")

            assertTrue(source().checkBoard() is BoardCheck.Failed)
            assertTrue(source().checkBoard() is BoardCheck.Failed)
        }

    @Test
    fun aBoardNameCannotInjectAPath() {
        for (name in listOf("../admin", "..", ".", "a/b", "")) {
            try {
                AshbySource(name, "Co", OkHttpClient())
                fail("'$name' should be rejected")
            } catch (expected: IllegalArgumentException) {
                // Rejected, as it should be.
            }
        }
    }
}
