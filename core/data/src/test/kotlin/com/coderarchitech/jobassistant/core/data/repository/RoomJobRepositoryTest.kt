package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.DatabaseTest
import com.coderarchitech.jobassistant.core.data.db.entity.toEntity
import com.coderarchitech.jobassistant.core.data.db.sampleApplication
import com.coderarchitech.jobassistant.core.data.db.sampleJob
import com.coderarchitech.jobassistant.core.model.ApplicationStatus
import com.coderarchitech.jobassistant.core.model.JobStatus
import com.coderarchitech.jobassistant.core.model.TransitionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomJobRepositoryTest : DatabaseTest() {
    private val repo get() = RoomJobRepository(db)

    private suspend fun insertJob(
        status: JobStatus,
        externalId: String = "job-${status.name}",
    ): Long = db.jobDao().insert(sampleJob(externalId = externalId, status = status).toEntity())

    private suspend fun statusOf(id: Long) = db.jobDao().getStatus(id)

    @Test
    fun movesAJobWhenItHasTheExpectedStatus() =
        runBlocking {
            val id = insertJob(JobStatus.SCORED)

            val result = repo.transition(id, JobStatus.SCORED, JobStatus.APPROVED)

            assertEquals(TransitionResult.Moved, result)
            assertEquals(JobStatus.APPROVED, statusOf(id))
        }

    @Test
    fun aStaleWriterGetsAConflictWithTheActualStatusAndWritesNothing() =
        runBlocking {
            val id = insertJob(JobStatus.SCORED)
            val seenByStaleWriter = statusOf(id)
            repo.transition(id, JobStatus.SCORED, JobStatus.APPROVED) // another writer gets there first

            val result = repo.transition(id, seenByStaleWriter ?: error("missing"), JobStatus.SKIPPED)

            assertEquals(TransitionResult.Conflict(JobStatus.APPROVED), result)
            assertEquals(JobStatus.APPROVED, statusOf(id))
        }

    @Test
    fun aMoveThatIsNotInTheTableIsRefusedAndWritesNothing() =
        runBlocking {
            val id = insertJob(JobStatus.FOUND)

            val result = repo.transition(id, JobStatus.FOUND, JobStatus.APPROVED)

            assertEquals(TransitionResult.NotAllowed(JobStatus.FOUND, JobStatus.APPROVED), result)
            assertEquals(JobStatus.FOUND, statusOf(id))
        }

    @Test
    fun aJobThatIsNotApprovedCannotBecomeReady() =
        runBlocking {
            val id = insertJob(JobStatus.SCORED)

            assertEquals(
                TransitionResult.NotAllowed(JobStatus.SCORED, JobStatus.READY),
                repo.transition(id, JobStatus.SCORED, JobStatus.READY),
            )
            assertEquals(JobStatus.SCORED, statusOf(id))
        }

    @Test
    fun aMissingJobIsNotFound() =
        runBlocking {
            assertEquals(TransitionResult.NotFound, repo.transition(999, JobStatus.FOUND, JobStatus.SCORED))
        }

    @Test
    fun theUserCanSetAnyShownStatusByHand() =
        runBlocking {
            val id = insertJob(JobStatus.FAILED)

            assertEquals(TransitionResult.Moved, repo.transitionManually(id, JobStatus.FAILED, JobStatus.APPROVED))
            assertEquals(JobStatus.APPROVED, statusOf(id))
            assertEquals(TransitionResult.Moved, repo.transitionManually(id, JobStatus.APPROVED, JobStatus.OFFER))
            assertEquals(JobStatus.OFFER, statusOf(id))
        }

    @Test
    fun theUserCannotSetAnInternalStatusByHand() =
        runBlocking {
            val id = insertJob(JobStatus.APPROVED)

            assertEquals(
                TransitionResult.NotAllowed(JobStatus.APPROVED, JobStatus.SUBMITTING),
                repo.transitionManually(id, JobStatus.APPROVED, JobStatus.SUBMITTING),
            )
            assertEquals(JobStatus.APPROVED, statusOf(id))
        }

    @Test
    fun aManualChangeFromAStaleScreenGetsAConflict() =
        runBlocking {
            val id = insertJob(JobStatus.APPLIED)
            repo.transition(id, JobStatus.APPLIED, JobStatus.REPLIED)

            val result = repo.transitionManually(id, JobStatus.APPLIED, JobStatus.REJECTED)

            assertEquals(TransitionResult.Conflict(JobStatus.REPLIED), result)
        }

    /** Task T1.05 Done when: two concurrent transitions of the same job cannot both succeed. */
    @Test
    fun twoConcurrentTransitionsOfTheSameJobCannotBothSucceed() =
        runBlocking {
            repeat(ROUNDS) { round ->
                val id = insertJob(JobStatus.SCORED, externalId = "race-$round")
                val gate = CompletableDeferred<Unit>()
                // Half the racers approve and half skip, so the two writers disagree about the outcome.
                val targets = List(RACERS) { if (it % 2 == 0) JobStatus.APPROVED else JobStatus.SKIPPED }

                val results =
                    targets
                        .map { target ->
                            async(Dispatchers.Default) {
                                gate.await()
                                target to repo.transition(id, JobStatus.SCORED, target)
                            }
                        }.also { gate.complete(Unit) }
                        .awaitAll()

                val winners = results.filter { it.second == TransitionResult.Moved }
                assertEquals("round $round: exactly one writer wins", 1, winners.size)
                val finalStatus = statusOf(id)
                assertEquals("round $round: the winner's status is stored", winners.single().first, finalStatus)
                results.filter { it.second != TransitionResult.Moved }.forEach { (_, result) ->
                    assertEquals(
                        "round $round: losers see the winner's status",
                        TransitionResult.Conflict(finalStatus),
                        result,
                    )
                }
            }
        }

    @Test
    fun twoWorkersMovingAJobToTheSameStatusDoTheWorkOnce() =
        runBlocking {
            val id = insertJob(JobStatus.APPROVED)
            val gate = CompletableDeferred<Unit>()

            val results =
                List(RACERS) {
                    async(Dispatchers.Default) {
                        gate.await()
                        repo.transition(id, JobStatus.APPROVED, JobStatus.READY)
                    }
                }.also { gate.complete(Unit) }.awaitAll()

            assertEquals(1, results.count { it == TransitionResult.Moved })
            assertEquals(RACERS - 1, results.count { it == TransitionResult.Conflict(JobStatus.READY) })
        }

    @Test
    fun relatedChangesInOneTransactionCommitTogether() =
        runBlocking {
            val job = insertJob(JobStatus.SUBMITTING)
            db.applicationDao().insert(sampleApplication(job).toEntity())
            val jobs = RoomJobRepository(db)
            val applications = RoomApplicationRepository(db)

            RoomTransactionRunner(db).run {
                jobs.transition(job, JobStatus.SUBMITTING, JobStatus.APPLIED)
                applications.markSubmitted(job, ApplicationStatus.SUBMITTING, SUBMITTED_AT, null, "attempt-1")
            }

            assertEquals(JobStatus.APPLIED, statusOf(job))
            assertEquals(ApplicationStatus.SUBMITTED, db.applicationDao().getByJobId(job)?.status)
        }

    @Test
    fun anErrorInATransactionRollsBackEveryWriteInIt() =
        runBlocking {
            val job = insertJob(JobStatus.SUBMITTING)
            db.applicationDao().insert(sampleApplication(job).toEntity())
            val jobs = RoomJobRepository(db)
            val applications = RoomApplicationRepository(db)

            val failure =
                runCatchingNonCancellation {
                    RoomTransactionRunner(db).run {
                        jobs.transition(job, JobStatus.SUBMITTING, JobStatus.APPLIED)
                        applications.markSubmitted(job, ApplicationStatus.SUBMITTING, SUBMITTED_AT, null, "attempt-1")
                        error("crash between the two writes")
                    }
                }

            assertTrue(failure is IllegalStateException)
            assertEquals(JobStatus.SUBMITTING, statusOf(job))
            assertEquals(ApplicationStatus.SUBMITTING, db.applicationDao().getByJobId(job)?.status)
        }

    private suspend fun runCatchingNonCancellation(block: suspend () -> Unit): Throwable? =
        withContext(Dispatchers.Default) {
            try {
                block()
                null
            } catch (expected: IllegalStateException) {
                expected
            }
        }

    private companion object {
        const val ROUNDS = 25
        const val RACERS = 8
        val SUBMITTED_AT = com.coderarchitech.jobassistant.core.data.db.T0
    }
}
