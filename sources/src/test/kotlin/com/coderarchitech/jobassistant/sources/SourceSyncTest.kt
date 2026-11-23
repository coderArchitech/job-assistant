package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.JobFilters
import com.coderarchitech.jobassistant.core.model.KnownPostings
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.model.SourceCursorStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SourceSyncTest {
    private val t0 = Instant.parse("2026-03-01T06:00:00Z")
    private val cursors = InMemoryCursors()
    private val stored = InMemoryJobs()
    private val preset =
        SearchPreset(
            id = 1,
            name = "Android",
            keywords = listOf("android"),
            portalIds = emptyList(),
            context = "",
            filters = JobFilters(),
            schedule = RunSchedule(),
        )

    private fun sync(at: Instant) = SourceSync(cursors, stored, Clock.fixed(at, ZoneOffset.UTC))

    private fun posting(
        id: String,
        postedAt: Instant? = null,
    ) = RawPosting(
        externalId = id,
        title = "Android developer $id",
        company = "Example Co",
        url = "https://example.com/$id",
        postedAt = postedAt,
    )

    /** Stores what a run found, as the normalizer will, then commits the cursor. */
    private suspend fun ingestAndCommit(
        sync: SourceSync,
        outcome: FetchOutcome,
    ) {
        outcome as FetchOutcome.Fetched
        stored.add(outcome.sourceKey, outcome.postings.map { it.externalId })
        sync.commit(outcome)
    }

    /** Task T2.01 Done when: a fake source runs incrementally across two runs without reprocessing jobs. */
    @Test
    fun aSourceRunsIncrementallyAcrossTwoRunsWithoutReprocessingJobs() =
        runTest {
            val board =
                FakeSource(
                    "fake:board",
                    mutableListOf(posting("a", t0.minusSeconds(300)), posting("b", t0.minusSeconds(200))),
                    honorsCursor = true,
                )

            val firstRun = sync(t0)
            val first = firstRun.fetchNew(board, preset) as FetchOutcome.Fetched
            assertEquals(listOf("a", "b"), first.postings.map { it.externalId })
            ingestAndCommit(firstRun, first)

            board.postings += posting("c", t0.plusSeconds(60))
            val secondRun = sync(t0.plusSeconds(3_600))
            val second = secondRun.fetchNew(board, preset) as FetchOutcome.Fetched

            assertEquals(
                "the source was asked only for what is new",
                listOf("c"),
                second.postings.map { it.externalId },
            )
            assertEquals(t0, board.cursorsSeen[1]?.lastSuccessAt)
            assertEquals("b", board.cursorsSeen[1]?.lastSeenExternalId)
            assertEquals(0, second.alreadyKnown)
        }

    @Test
    fun aSourceThatIgnoresTheCursorStillGivesNothingTwice() =
        runTest {
            val board = FakeSource("fake:rss", mutableListOf(posting("a"), posting("b")), honorsCursor = false)
            val firstRun = sync(t0)
            ingestAndCommit(firstRun, firstRun.fetchNew(board, preset))

            board.postings += posting("c")
            val second = sync(t0.plusSeconds(3_600)).fetchNew(board, preset) as FetchOutcome.Fetched

            assertEquals(listOf("c"), second.postings.map { it.externalId })
            assertEquals(2, second.alreadyKnown)
        }

    @Test
    fun theCursorOnlyMovesWhenTheRunIsCommitted() =
        runTest {
            val board = FakeSource("fake:board", mutableListOf(posting("a")), honorsCursor = false)

            val run = sync(t0)
            run.fetchNew(board, preset) // the app crashes before it stores or commits anything
            assertNull(cursors.get("fake:board"))

            val retry = sync(t0.plusSeconds(60)).fetchNew(board, preset) as FetchOutcome.Fetched
            assertEquals("nothing was lost", listOf("a"), retry.postings.map { it.externalId })
        }

    @Test
    fun postingsStoredButNotCommittedAreNotHandledTwice() =
        runTest {
            val board = FakeSource("fake:board", mutableListOf(posting("a"), posting("b")), honorsCursor = false)
            val run = sync(t0)
            val first = run.fetchNew(board, preset) as FetchOutcome.Fetched
            stored.add(first.sourceKey, first.postings.map { it.externalId }) // stored, then a crash before commit

            val retry = sync(t0.plusSeconds(60)).fetchNew(board, preset) as FetchOutcome.Fetched

            assertTrue(retry.postings.isEmpty())
            assertEquals(2, retry.alreadyKnown)
        }

    @Test
    fun aFailedFetchIsReportedAndKeepsTheCursor() =
        runTest {
            val good = FakeSource("fake:board", mutableListOf(posting("a")), honorsCursor = false)
            val run = sync(t0)
            ingestAndCommit(run, run.fetchNew(good, preset))
            val before = cursors.get("fake:board")

            val broken =
                FakeSource("fake:board", mutableListOf(), honorsCursor = false, failure = IOException("HTTP 503"))
            val outcome = sync(t0.plusSeconds(60)).fetchNew(broken, preset)

            assertEquals(FetchOutcome.Failed("fake:board", "HTTP 503"), outcome)
            assertEquals(before, cursors.get("fake:board"))
        }

    @Test
    fun theCursorRecordsWhenTheRunStartedAndTheNewestPosting() =
        runTest {
            val board =
                FakeSource(
                    "fake:board",
                    mutableListOf(
                        posting("old", t0.minusSeconds(900)),
                        posting("newest", t0.minusSeconds(10)),
                        posting("middle", t0.minusSeconds(500)),
                    ),
                    honorsCursor = false,
                )

            val outcome = sync(t0).fetchNew(board, preset) as FetchOutcome.Fetched

            assertEquals(SourceCursor("fake:board", t0, "newest"), outcome.cursorAfter)
        }

    @Test
    fun anEmptyRunKeepsTheLastSeenId() =
        runTest {
            val board = FakeSource("fake:board", mutableListOf(posting("a", t0)), honorsCursor = false)
            val run = sync(t0)
            ingestAndCommit(run, run.fetchNew(board, preset))
            board.postings.clear()

            val later = sync(t0.plusSeconds(3_600))
            val outcome = later.fetchNew(board, preset) as FetchOutcome.Fetched
            later.commit(outcome)

            assertTrue(outcome.postings.isEmpty())
            assertEquals(SourceCursor("fake:board", t0.plusSeconds(3_600), "a"), cursors.get("fake:board"))
        }

    @Test
    fun aPostingListedTwiceIsHandledOnce() =
        runTest {
            val board =
                FakeSource("fake:board", mutableListOf(posting("a"), posting("a"), posting("b")), honorsCursor = false)

            val outcome = sync(t0).fetchNew(board, preset) as FetchOutcome.Fetched

            assertEquals(listOf("a", "b"), outcome.postings.map { it.externalId })
        }

    @Test
    fun twoSourcesKeepSeparateCursorsAndKnownJobs() =
        runTest {
            val one = FakeSource("fake:one", mutableListOf(posting("1")), honorsCursor = false)
            val two = FakeSource("fake:two", mutableListOf(posting("1")), honorsCursor = false)
            val run = sync(t0)
            ingestAndCommit(run, run.fetchNew(one, preset))

            val outcome = sync(t0).fetchNew(two, preset) as FetchOutcome.Fetched

            assertEquals(
                "the same id from another source is a different posting",
                listOf("1"),
                outcome.postings.map {
                    it.externalId
                },
            )
            assertNull(cursors.get("fake:two"))
        }

    @Test
    fun cancellationIsNotReportedAsAFailure() =
        runTest {
            val board =
                FakeSource(
                    "fake:board",
                    mutableListOf(),
                    honorsCursor = false,
                    failure = CancellationException("stopped"),
                )

            try {
                sync(t0).fetchNew(board, preset)
                org.junit.Assert.fail("expected the cancellation to propagate")
            } catch (expected: CancellationException) {
                assertEquals("stopped", expected.message)
            }
        }

    @Test(expected = IllegalArgumentException::class)
    fun aPostingNeedsAnIdAndATitle() {
        RawPosting(externalId = " ", title = "t", company = "c", url = "u")
    }

    private class FakeSource(
        override val key: String,
        val postings: MutableList<RawPosting>,
        private val honorsCursor: Boolean,
        private val failure: Exception? = null,
    ) : JobSource {
        val cursorsSeen = mutableListOf<SourceCursor?>()

        override suspend fun fetch(
            since: SourceCursor?,
            preset: SearchPreset,
        ): List<RawPosting> {
            cursorsSeen += since
            failure?.let { throw it }
            val after = since?.lastSuccessAt
            return if (honorsCursor &&
                after != null
            ) {
                postings.filter { (it.postedAt ?: Instant.MAX) > after }
            } else {
                postings.toList()
            }
        }
    }

    private class InMemoryCursors : SourceCursorStore {
        private val map = mutableMapOf<String, SourceCursor>()

        override suspend fun get(sourceKey: String) = map[sourceKey]

        override suspend fun save(cursor: SourceCursor) {
            map[cursor.sourceKey] = cursor
        }
    }

    private class InMemoryJobs : KnownPostings {
        private val ids = mutableSetOf<Pair<String, String>>()

        fun add(
            sourceKey: String,
            externalIds: List<String>,
        ) {
            externalIds.forEach { ids += sourceKey to it }
        }

        override suspend fun knownExternalIds(
            sourceKey: String,
            externalIds: Collection<String>,
        ): Set<String> = externalIds.filterTo(mutableSetOf()) { (sourceKey to it) in ids }
    }
}
