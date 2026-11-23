package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.DatabaseTest
import com.coderarchitech.jobassistant.core.data.db.T0
import com.coderarchitech.jobassistant.core.data.db.entity.toEntity
import com.coderarchitech.jobassistant.core.data.db.sampleJob
import com.coderarchitech.jobassistant.core.model.SourceCursor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomSourceStoresTest : DatabaseTest() {
    private val cursors get() = RoomSourceCursorStore(db.sourceCursorDao())
    private val known get() = RoomKnownPostings(db.jobDao())

    @Test
    fun aCursorIsSavedReplacedAndReadBack() =
        runTest {
            assertNull(cursors.get("greenhouse:stripe"))

            cursors.save(SourceCursor("greenhouse:stripe", T0, "a"))
            cursors.save(SourceCursor("greenhouse:stripe", T0.plusSeconds(60), "b"))

            assertEquals(SourceCursor("greenhouse:stripe", T0.plusSeconds(60), "b"), cursors.get("greenhouse:stripe"))
            assertNull(cursors.get("remotive"))
        }

    @Test
    fun onlyPostingsStoredForThatSourceAreKnown() =
        runTest {
            db.jobDao().insert(sampleJob(externalId = "a", source = "remotive").toEntity())
            db.jobDao().insert(sampleJob(externalId = "b", source = "remotive").toEntity())
            db.jobDao().insert(sampleJob(externalId = "c", source = "remoteok").toEntity())

            assertEquals(setOf("a", "b"), known.knownExternalIds("remotive", listOf("a", "b", "c", "d")))
            assertEquals(setOf("c"), known.knownExternalIds("remoteok", listOf("a", "c")))
            assertEquals(emptySet<String>(), known.knownExternalIds("remotive", emptyList()))
        }

    @Test
    fun aLongListOfIdsIsAnsweredInSlices() =
        runTest {
            val stored = (1..1_200).map { "job-$it" }
            stored.forEach { db.jobDao().insert(sampleJob(externalId = it, source = "remotive").toEntity()) }

            val asked = stored + (1..300).map { "new-$it" } + stored.take(50) // 1,550 asked, 50 repeated

            assertEquals(stored.toSet(), known.knownExternalIds("remotive", asked))
        }
}
