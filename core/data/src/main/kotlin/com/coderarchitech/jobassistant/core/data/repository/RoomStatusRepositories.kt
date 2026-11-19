package com.coderarchitech.jobassistant.core.data.repository

import androidx.room.withTransaction
import com.coderarchitech.jobassistant.core.data.db.AgentDatabase
import com.coderarchitech.jobassistant.core.model.ApplicationStatus
import com.coderarchitech.jobassistant.core.model.ApplicationTransitions
import com.coderarchitech.jobassistant.core.model.JobStatus
import com.coderarchitech.jobassistant.core.model.JobTransitions
import com.coderarchitech.jobassistant.core.model.TransitionResult
import java.time.Instant
import javax.inject.Inject

/** Room-backed [JobRepository]: one guarded `UPDATE … WHERE status = :from` per move. */
internal class RoomJobRepository
    @Inject
    constructor(
        private val db: AgentDatabase,
    ) : JobRepository {
        override suspend fun transition(
            jobId: Long,
            from: JobStatus,
            to: JobStatus,
        ): TransitionResult<JobStatus> =
            if (JobTransitions.isAllowed(
                    from,
                    to,
                )
            ) {
                compareAndSet(jobId, from, to)
            } else {
                TransitionResult.NotAllowed(from, to)
            }

        override suspend fun transitionManually(
            jobId: Long,
            from: JobStatus,
            to: JobStatus,
        ): TransitionResult<JobStatus> =
            if (JobTransitions.isAllowedManually(from, to)) {
                compareAndSet(jobId, from, to)
            } else {
                TransitionResult.NotAllowed(from, to)
            }

        private suspend fun compareAndSet(
            jobId: Long,
            from: JobStatus,
            to: JobStatus,
        ): TransitionResult<JobStatus> =
            db.withTransaction {
                val dao = db.jobDao()
                if (dao.compareAndSetStatus(jobId, from, to) > 0) {
                    TransitionResult.Moved
                } else {
                    // Nothing changed, so say why. This read is in the same transaction as the write.
                    dao.getStatus(jobId)?.let { TransitionResult.Conflict(it) } ?: TransitionResult.NotFound
                }
            }
    }

/** Room-backed [ApplicationRepository]. */
internal class RoomApplicationRepository
    @Inject
    constructor(
        private val db: AgentDatabase,
    ) : ApplicationRepository {
        override suspend fun markSubmitted(
            jobId: Long,
            from: ApplicationStatus,
            submittedAt: Instant,
            proofPath: String?,
            attemptId: String?,
        ): TransitionResult<ApplicationStatus> {
            requireAttemptWhenSubmitting(from, attemptId)
            return move(jobId, from, ApplicationStatus.SUBMITTED, submittedAt, proofPath, attemptId)
        }

        override suspend fun markFailed(
            jobId: Long,
            from: ApplicationStatus,
            attemptId: String?,
        ): TransitionResult<ApplicationStatus> {
            requireAttemptWhenSubmitting(from, attemptId)
            return move(jobId, from, ApplicationStatus.FAILED, submittedAt = null, proofPath = null, attemptId)
        }

        override suspend fun markCheckManually(
            jobId: Long,
            attemptId: String,
        ): TransitionResult<ApplicationStatus> =
            move(
                jobId,
                ApplicationStatus.SUBMITTING,
                ApplicationStatus.CHECK_MANUALLY,
                submittedAt = null,
                proofPath = null,
                attemptId,
            )

        override suspend fun recoverInterruptedSubmissions(): Int =
            db.applicationDao().compareAndSetAllStatus(ApplicationStatus.SUBMITTING, ApplicationStatus.CHECK_MANUALLY)

        private fun requireAttemptWhenSubmitting(
            from: ApplicationStatus,
            attemptId: String?,
        ) = require(from != ApplicationStatus.SUBMITTING || attemptId != null) {
            "Leaving SUBMITTING needs the attempt ID, so a stale session cannot touch a newer attempt"
        }

        private suspend fun move(
            jobId: Long,
            from: ApplicationStatus,
            to: ApplicationStatus,
            submittedAt: Instant?,
            proofPath: String?,
            attemptId: String?,
        ): TransitionResult<ApplicationStatus> {
            if (!ApplicationTransitions.isAllowed(from, to)) return TransitionResult.NotAllowed(from, to)
            return db.withTransaction {
                val dao = db.applicationDao()
                if (dao.compareAndSetStatus(jobId, from, to, submittedAt, proofPath, attemptId) > 0) {
                    TransitionResult.Moved
                } else {
                    // Nothing changed, so say why. This read is in the same transaction as the write.
                    val current = dao.getByJobId(jobId)
                    when {
                        current == null -> TransitionResult.NotFound
                        current.status != from -> TransitionResult.Conflict(current.status)
                        else -> TransitionResult.AttemptMismatch
                    }
                }
            }
        }
    }

/** Room-backed [TransactionRunner]. A nested call joins the transaction already open in the same coroutine. */
internal class RoomTransactionRunner
    @Inject
    constructor(
        private val db: AgentDatabase,
    ) : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
    }
