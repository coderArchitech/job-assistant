package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.DatabaseTest
import com.coderarchitech.jobassistant.core.data.db.T0
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomApplicationRepositoryTest : DatabaseTest() {
    private val repo get() = RoomApplicationRepository(db)

    /** Inserts a job and its application in [status], from attempt `attempt-1` unless [attemptId] is given. */
    private suspend fun insertApplication(
        status: ApplicationStatus,
        externalId: String = "job-${status.name}",
        attemptId: String = "attempt-1",
        proofPath: String? = null,
        into: com.coderarchitech.jobassistant.core.data.db.AgentDatabase = db,
    ): Long {
        val job = into.jobDao().insert(sampleJob(externalId = externalId, status = JobStatus.SUBMITTING).toEntity())
        // Built as SUBMITTING, then copied with status and time together: a SUBMITTED application needs both.
        val application =
            sampleApplication(job, status = ApplicationStatus.SUBMITTING).copy(
                status = status,
                attemptId = attemptId,
                proofPath = proofPath,
                submittedAt = T0.takeIf { status == ApplicationStatus.SUBMITTED },
            )
        into.applicationDao().insert(application.toEntity())
        return job
    }

    private suspend fun stored(jobId: Long) = db.applicationDao().getByJobId(jobId)

    @Test
    fun confirmingASubmissionStoresTheStatusTimeAndProof() =
        runBlocking {
            val job = insertApplication(ApplicationStatus.SUBMITTING)

            val result = repo.markSubmitted(job, "attempt-1", T0.plusSeconds(30), "proofs/1.png")

            assertEquals(TransitionResult.Moved, result)
            val row = stored(job)
            assertEquals(ApplicationStatus.SUBMITTED, row?.status)
            assertEquals(T0.plusSeconds(30), row?.submittedAt)
            assertEquals("proofs/1.png", row?.proofPath)
        }

    @Test
    fun aStaleSessionCannotConfirmOrFailAnotherAttempt() =
        runBlocking {
            val job = insertApplication(ApplicationStatus.SUBMITTING, attemptId = "attempt-2")

            assertEquals(TransitionResult.AttemptMismatch, repo.markSubmitted(job, "attempt-1", T0, null))
            assertEquals(TransitionResult.AttemptMismatch, repo.markFailed(job, "attempt-1"))
            assertEquals(TransitionResult.AttemptMismatch, repo.markCheckManually(job, "attempt-1"))
            assertEquals(ApplicationStatus.SUBMITTING, stored(job)?.status)
            assertNull(stored(job)?.submittedAt)
        }

    @Test
    fun confirmingTwiceMovesOnceAndTheSecondCallSeesSubmitted() =
        runBlocking {
            val job = insertApplication(ApplicationStatus.SUBMITTING)
            repo.markSubmitted(job, "attempt-1", T0, null)

            val second = repo.markSubmitted(job, "attempt-1", T0.plusSeconds(99), null)

            assertEquals(TransitionResult.Conflict(ApplicationStatus.SUBMITTED), second)
            assertEquals(T0, stored(job)?.submittedAt)
        }

    @Test
    fun aFailedAttemptIsFinal() =
        runBlocking {
            val job = insertApplication(ApplicationStatus.SUBMITTING)
            assertEquals(TransitionResult.Moved, repo.markFailed(job, "attempt-1"))

            assertEquals(
                TransitionResult.Conflict(ApplicationStatus.FAILED),
                repo.markSubmitted(job, "attempt-1", T0, null),
            )
            assertEquals(
                TransitionResult.NotAllowed(ApplicationStatus.FAILED, ApplicationStatus.SUBMITTED),
                repo.confirmSubmittedManually(job, ApplicationStatus.FAILED, T0, null),
            )
            assertEquals(ApplicationStatus.FAILED, stored(job)?.status)
        }

    @Test
    fun anApplicationWithoutARowIsNotFound() =
        runBlocking {
            val job = db.jobDao().insert(sampleJob().toEntity())

            assertEquals(TransitionResult.NotFound, repo.markFailed(job, "attempt-1"))
            assertEquals(TransitionResult.NotFound, repo.markFailedManually(job, ApplicationStatus.CHECK_MANUALLY))
        }

    @Test
    fun anUnresolvedAttemptCanBeMarkedForManualCheckAndThenResolvedByTheUser() =
        runBlocking {
            val job = insertApplication(ApplicationStatus.SUBMITTING, proofPath = "proofs/early.png")

            assertEquals(TransitionResult.Moved, repo.markCheckManually(job, "attempt-1"))
            assertEquals(ApplicationStatus.CHECK_MANUALLY, stored(job)?.status)

            // The user looked at the portal and found the application there.
            assertEquals(
                TransitionResult.Moved,
                repo.confirmSubmittedManually(job, ApplicationStatus.CHECK_MANUALLY, T0, proofPath = null),
            )
            assertEquals("proofs/early.png", stored(job)?.proofPath) // a null proof leaves the stored one alone
            assertEquals(ApplicationStatus.SUBMITTED, stored(job)?.status)
        }

    @Test
    fun theUserCanRecordThatACheckedAttemptWasNotSubmitted() =
        runBlocking {
            val job = insertApplication(ApplicationStatus.CHECK_MANUALLY)

            assertEquals(TransitionResult.Moved, repo.markFailedManually(job, ApplicationStatus.CHECK_MANUALLY))
            assertEquals(ApplicationStatus.FAILED, stored(job)?.status)
        }

    @Test
    fun anApplicationDoneByHandOnTheSiteCanBeRecordedAsSubmitted() =
        runBlocking {
            val job = insertApplication(ApplicationStatus.APPLY_MANUALLY)

            assertEquals(
                TransitionResult.Moved,
                repo.confirmSubmittedManually(job, ApplicationStatus.APPLY_MANUALLY, T0, proofPath = null),
            )
            assertEquals(ApplicationStatus.SUBMITTED, stored(job)?.status)
        }

    @Test
    fun theUserCannotSettleAnAttemptThatMayStillBeInFlight() =
        runBlocking {
            val job = insertApplication(ApplicationStatus.SUBMITTING)

            assertEquals(
                TransitionResult.NotAllowed(ApplicationStatus.SUBMITTING, ApplicationStatus.SUBMITTED),
                repo.confirmSubmittedManually(job, ApplicationStatus.SUBMITTING, T0, null),
            )
            assertEquals(
                TransitionResult.NotAllowed(ApplicationStatus.SUBMITTING, ApplicationStatus.FAILED),
                repo.markFailedManually(job, ApplicationStatus.SUBMITTING),
            )
            assertEquals(ApplicationStatus.SUBMITTING, stored(job)?.status)
        }

    @Test
    fun recoveryMovesOnlyAttemptsStillInSubmitting() =
        runBlocking {
            val crashedOne = insertApplication(ApplicationStatus.SUBMITTING, externalId = "a")
            val crashedTwo = insertApplication(ApplicationStatus.SUBMITTING, externalId = "b", attemptId = "attempt-2")
            val done = insertApplication(ApplicationStatus.SUBMITTED, externalId = "c")
            val manual = insertApplication(ApplicationStatus.APPLY_MANUALLY, externalId = "d")

            assertEquals(2, repo.recoverInterruptedSubmissions())

            assertEquals(ApplicationStatus.CHECK_MANUALLY, stored(crashedOne)?.status)
            assertEquals(ApplicationStatus.CHECK_MANUALLY, stored(crashedTwo)?.status)
            assertEquals(ApplicationStatus.SUBMITTED, stored(done)?.status)
            assertEquals(ApplicationStatus.APPLY_MANUALLY, stored(manual)?.status)
            assertEquals(0, repo.recoverInterruptedSubmissions())
        }

    @Test
    fun aRecoveredAttemptIsNeverRetriedByItsOldSession() =
        runBlocking {
            val job = insertApplication(ApplicationStatus.SUBMITTING)
            repo.recoverInterruptedSubmissions()

            val lateConfirmation = repo.markSubmitted(job, "attempt-1", T0, null)

            assertEquals(TransitionResult.Conflict(ApplicationStatus.CHECK_MANUALLY), lateConfirmation)
        }

    @Test
    fun twoSessionsConfirmingTheSameAttemptCannotBothSucceed() =
        runBlocking {
            withFileDatabase { raceDb ->
                val job = insertApplication(ApplicationStatus.SUBMITTING, into = raceDb)
                val racing = RoomApplicationRepository(raceDb)
                val gate = CompletableDeferred<Unit>()

                val results =
                    List(RACERS) {
                        async(Dispatchers.Default) {
                            gate.await()
                            racing.markSubmitted(job, "attempt-1", T0, null)
                        }
                    }.also { gate.complete(Unit) }.awaitAll()

                assertEquals(1, results.count { it == TransitionResult.Moved })
                assertEquals(ApplicationStatus.SUBMITTED, raceDb.applicationDao().getByJobId(job)?.status)
            }
        }

    private companion object {
        const val RACERS = 8
    }
}
