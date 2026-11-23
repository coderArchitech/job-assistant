package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.JobFilters
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.SearchPreset
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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

class LeverSourceTest {
    private val server = MockWebServer()
    private val preset = SearchPreset(1, "Android", listOf("android"), emptyList(), "", JobFilters(), RunSchedule())

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun source(
        site: String = "leverdemo",
        region: LeverRegion = LeverRegion.GLOBAL,
    ) = LeverSource(site, "Lever Demo Co", OkHttpClient(), region, server.url("/"))

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/lever/leverdemo-page1.json")) { "fixture missing" }
            .bufferedReader()
            .use { it.readText() }

    /** Serves [pages] by their `skip` value: skip 0 gets the first, 100 the second, and so on; anything else `[]`. */
    private fun serve(vararg pages: String) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val skip = request.url.queryParameter("skip")?.toInt() ?: 0
                    val body = pages.getOrNull(skip / PAGE)?.takeIf { skip % PAGE == 0 } ?: "[]"
                    return MockResponse
                        .Builder()
                        .code(200)
                        .body(body)
                        .build()
                }
            }
    }

    private fun posting(n: Int): String {
        val url = "https://jobs.lever.co/leverdemo/id-$n"
        return """{"id":"id-$n","text":"Job $n","hostedUrl":"$url","createdAt":${CREATED + n}}"""
    }

    private fun page(range: IntRange) = range.joinToString(prefix = "[", postfix = "]") { posting(it) }

    @Test
    fun theKeyNamesTheSiteAndTheRegion() {
        assertEquals("lever:leverdemo", source().key)
        assertEquals("lever:leverdemo", LeverSource("LeverDemo", "Co", OkHttpClient()).key)
        assertEquals("lever-eu:leverdemo", source(region = LeverRegion.EU).key)
    }

    @Test
    fun postingsAreReadFromTheRecordedSiteReply() =
        runTest {
            serve(fixture())

            val postings = source().fetch(null, preset)

            assertEquals(
                "three real, a remote one and a designer; the blank title, no link and no id are skipped",
                5,
                postings.size,
            )
            val first = postings.first()
            assertEquals("ad208490-4052-4f91-a57e-433f2d1e484b", first.externalId)
            assertEquals("Customer Success Manager", first.title)
            assertEquals("Lever Demo Co", first.company)
            assertEquals("Atlanta, Georgia", first.location)
            assertEquals(AtsType.LEVER, first.atsType)
            assertEquals(Instant.ofEpochMilli(1_788_199_939_703), first.postedAt)
            assertEquals("hybrid", first.workplaceType)
            assertEquals("https://jobs.lever.co/leverdemo/ad208490-4052-4f91-a57e-433f2d1e484b/apply", first.applyUrl)
            assertTrue(first.compensation.orEmpty(), first.compensation.orEmpty().contains("85000"))
        }

    @Test
    fun theDescriptionJoinsTheOpeningTheListsAndTheClosing() =
        runTest {
            serve(fixture())

            val description =
                source()
                    .fetch(null, preset)
                    .first()
                    .description
                    .orEmpty()

            assertTrue(description, description.startsWith("<div>Fixture text for Customer Success Manager.</div>"))
            assertTrue(
                description,
                description.contains("<h3>Skill Set:</h3><ul><li>First skill</li><li>Second skill</li></ul>"),
            )
            assertTrue(description, description.endsWith("<div>Benefits and more.</div>"))
        }

    @Test
    fun salaryAndWorkplaceAreReadAsLeverSendsThem() =
        runTest {
            val body =
                """[
                  {"id":"a","text":"A","hostedUrl":"https://x.test/a","workplaceType":" Remote ",
                   "salaryRange":{"currency":"USD","interval":"per-year-salary","min":85000.5,"max":175000}},
                  {"id":"b","text":"B","hostedUrl":"https://x.test/b","workplaceType":"Unspecified","salaryRange":{}},
                  {"id":"c","text":"C","hostedUrl":"https://x.test/c","categories":{"location":" ","allLocations":[""," Pune "]},
                   "lists":[{"text":" ","content":"<li>One</li>"},{"text":"Empty","content":""}]}
                ]"""
            serve(body)

            val byId = source().fetch(null, preset).associateBy { it.externalId }

            assertEquals("USD 85000.5-175000 per year", byId.getValue("a").compensation)
            assertEquals("remote", byId.getValue("a").workplaceType)
            assertNull("an empty range is no salary", byId.getValue("b").compensation)
            assertNull("Unspecified in any case is no information", byId.getValue("b").workplaceType)
            assertEquals("Pune", byId.getValue("c").location)
            assertEquals("<ul><li>One</li></ul>", byId.getValue("c").description)
        }

    @Test
    fun aPageOfPostingsWithoutIdsDoesNotEndThePaging() =
        runTest {
            serve("""[{"text":"No id","hostedUrl":"https://x.test/1"}]""", page(1..3))

            assertEquals(3, source().fetch(null, preset).size)
        }

    @Test
    fun aPostingWithLessDataStillComesThrough() =
        runTest {
            serve(fixture())

            val byId = source().fetch(null, preset).associateBy { it.externalId }

            val remote = byId.getValue("e0000000-0000-0000-0000-000000000001")
            assertEquals("Remote - India", remote.location)
            assertEquals("remote", remote.workplaceType)
            assertEquals("INR 12-18 LPA", remote.compensation)
            val designer = byId.getValue("e0000000-0000-0000-0000-000000000004")
            assertNull("unspecified is no information", designer.workplaceType)
            assertNull(designer.postedAt)
            assertNull(designer.description)
            assertNull(designer.applyUrl)
        }

    @Test
    fun everyPageIsFetchedUntilOneIsEmpty() =
        runTest {
            serve(page(1..100), page(101..150))

            val postings = source().fetch(null, preset)

            assertEquals(150, postings.size)
            val skips = List(server.requestCount) { server.takeRequest().url.queryParameter("skip") }
            assertEquals(listOf("0", "100", "200"), skips)
        }

    @Test
    fun theRequestAsksForJsonWithNoKey() =
        runTest {
            serve("[]")

            source().fetch(null, preset)

            val request = server.takeRequest()
            assertEquals("/v0/postings/leverdemo", request.url.encodedPath)
            assertEquals("json", request.url.queryParameter("mode"))
            assertEquals("100", request.url.queryParameter("limit"))
            assertNull(request.headers["Authorization"])
        }

    @Test
    fun aServerThatIgnoresSkipDoesNotLoopForever() =
        runTest {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        MockResponse
                            .Builder()
                            .code(200)
                            .body(page(1..100))
                            .build()
                }

            val postings = source().fetch(null, preset)

            assertEquals(100, postings.size)
            assertEquals("stopped at the first repeated page", 2, server.requestCount)
        }

    @Test
    fun aSiteWithTooManyPostingsFailsInsteadOfReturningAPartialList() =
        runTest {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val skip = request.url.queryParameter("skip")!!.toInt()
                        return MockResponse
                            .Builder()
                            .code(200)
                            .body(page((skip + 1)..(skip + 100)))
                            .build()
                    }
                }

            try {
                source().fetch(null, preset)
                fail("expected an IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message, expected.message!!.contains("partial"))
            }
        }

    @Test
    fun aFailedFetchThrowsInsteadOfReturningAnEmptyList() =
        runTest {
            for (code in listOf(404, 429, 500, 503)) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(code)
                        .body("nope")
                        .build(),
                )
                try {
                    source().fetch(null, preset)
                    fail("HTTP $code should throw")
                } catch (expected: IOException) {
                    assertTrue(
                        expected.message,
                        expected.message!!.contains(
                            if (code ==
                                404
                            ) {
                                "not found"
                            } else {
                                "HTTP $code"
                            },
                        ),
                    )
                }
            }
        }

    @Test
    fun aFailureOnALaterPageFailsTheWholeFetch() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(page(1..100))
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(500)
                    .body("boom")
                    .build(),
            )

            try {
                source().fetch(null, preset)
                fail("a partial list must not be returned")
            } catch (expected: IOException) {
                assertTrue(expected.message, expected.message!!.contains("HTTP 500"))
            }
        }

    @Test
    fun aReplyThatIsNotAListThrows() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"ok":false,"error":"Document not found"}""")
                    .build(),
            )

            try {
                source().fetch(null, preset)
                fail("expected an IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message, expected.message!!.contains("cannot read"))
            }
        }

    @Test
    fun checkBoardNeedsOnePageNotTheWholeSite() =
        runTest {
            serve(fixture(), page(101..200))

            assertEquals(BoardCheck.Valid(5), source().checkBoard())
            assertEquals("one request, however large the site", 1, server.requestCount)
        }

    @Test
    fun checkBoardSaysNotFoundForAnUnknownSite() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(404)
                    .body("""{"ok":false,"error":"Document not found"}""")
                    .build(),
            )

            assertEquals(BoardCheck.NotFound, source("no-such-site").checkBoard())
        }

    @Test
    fun checkBoardReportsOtherFailuresWithoutThrowing() =
        runTest {
            server.enqueue(MockResponse.Builder().code(503).build())
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("not json")
                    .build(),
            )

            assertTrue(source().checkBoard() is BoardCheck.Failed)
            assertTrue(source().checkBoard() is BoardCheck.Failed)
        }

    @Test(expected = IllegalArgumentException::class)
    fun aSiteNameCannotInjectAPath() {
        LeverSource("../admin", "Co", OkHttpClient())
    }

    private companion object {
        const val PAGE = 100
        const val CREATED = 1_788_000_000_000L
    }
}
