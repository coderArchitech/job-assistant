package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.KnownPostings
import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.model.SourceCursorStore
import kotlinx.coroutines.CancellationException
import java.time.Clock

/** What [SourceSync.fetchNew] found. */
sealed interface FetchOutcome {
    /**
     * The fetch worked. [postings] are the ones the app does not hold yet, each once; [alreadyKnown]
     * counts the ones it dropped. Pass this to [SourceSync.commit] once [postings] are stored.
     */
    data class Fetched(
        val sourceKey: String,
        val postings: List<RawPosting>,
        val alreadyKnown: Int,
        /** The cursor to store when the postings are safely stored. */
        val cursorAfter: SourceCursor,
    ) : FetchOutcome

    /** The fetch failed; the cursor stays where it was, so the next run asks again. */
    data class Failed(
        val sourceKey: String,
        val message: String,
    ) : FetchOutcome
}

/**
 * Runs a [JobSource] incrementally: it hands the source its cursor, drops postings the app already
 * holds, and moves the cursor forward only when told the new postings are stored. Fetching and
 * committing are separate so a crash between them loses nothing: the next run fetches again and
 * [KnownPostings] drops whatever did get stored.
 */
class SourceSync(
    private val cursors: SourceCursorStore,
    private val known: KnownPostings,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Fetches [source] for [preset] and returns only postings not stored yet. A failure of the source, the
     * cursor store or the job lookup is returned as [FetchOutcome.Failed]; only cancellation is thrown.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure of one source must not stop the others.
    suspend fun fetchNew(
        source: JobSource,
        preset: SearchPreset,
    ): FetchOutcome {
        try {
            return fetchNewOrThrow(source, preset)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FetchOutcome.Failed(source.key, e.message ?: e::class.java.simpleName)
        }
    }

    private suspend fun fetchNewOrThrow(
        source: JobSource,
        preset: SearchPreset,
    ): FetchOutcome.Fetched {
        val since = cursors.get(source.key)
        val startedAt = clock.instant()
        val fetched = source.fetch(since, preset)

        val distinct = fetched.distinctBy { it.externalId }
        val stored = known.knownExternalIds(source.key, distinct.map { it.externalId })
        val fresh = distinct.filter { it.externalId !in stored }
        return FetchOutcome.Fetched(
            sourceKey = source.key,
            postings = fresh,
            alreadyKnown = distinct.size - fresh.size,
            cursorAfter =
                SourceCursor(
                    sourceKey = source.key,
                    lastSuccessAt = startedAt,
                    lastSeenExternalId = newestOf(distinct)?.externalId ?: since?.lastSeenExternalId,
                ),
        )
    }

    /** Moves the source's cursor forward. Call it once the postings of [outcome] are stored. */
    suspend fun commit(outcome: FetchOutcome.Fetched) = cursors.save(outcome.cursorAfter)

    /** The most recently posted one, or the last one listed when no posting has a date. */
    private fun newestOf(postings: List<RawPosting>): RawPosting? =
        postings.mapNotNull { p -> p.postedAt?.let { p to it } }.maxByOrNull { it.second }?.first
            ?: postings.lastOrNull()
}
