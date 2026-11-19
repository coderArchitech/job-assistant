package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.model.ApplicationStatus
import com.coderarchitech.jobassistant.core.model.JobStatus
import com.coderarchitech.jobassistant.core.model.TransitionResult
import java.time.Instant

/**
 * Moves jobs between statuses. This is the only way to change a job's status (CLAUDE.md): each call
 * checks the move against `JobTransitions`, then writes it in one transaction only if the job still has
 * the status the caller read, so two writers can never both succeed.
 */
interface JobRepository {
    /**
     * Moves the job from [from] to [to] as the pipeline: [TransitionResult.NotAllowed] if the table
     * forbids it, [TransitionResult.Conflict] with the actual status if the job is no longer in [from],
     * [TransitionResult.NotFound] if there is no such job, otherwise [TransitionResult.Moved].
     */
    suspend fun transition(
        jobId: Long,
        from: JobStatus,
        to: JobStatus,
    ): TransitionResult<JobStatus>

    /**
     * Same as [transition] for a status the user sets by hand (PRD FR12), which may go to any shown
     * status but never to an internal one. Still fails with [TransitionResult.Conflict] if the job
     * moved since the user's screen was drawn.
     */
    suspend fun transitionManually(
        jobId: Long,
        from: JobStatus,
        to: JobStatus,
    ): TransitionResult<JobStatus>
}

/**
 * Moves applications between statuses. `SUBMITTING` is written before the click and confirmed after;
 * it is never retried automatically (CLAUDE.md rule 5). Every method that leaves `SUBMITTING` takes the
 * attempt ID, so a stale session cannot confirm or fail a newer attempt.
 */
interface ApplicationRepository {
    /**
     * Records a confirmed submission: [from] to `SUBMITTED`, with the time and optional proof
     * screenshot path. [attemptId] is required when [from] is `SUBMITTING`.
     */
    suspend fun markSubmitted(
        jobId: Long,
        from: ApplicationStatus,
        submittedAt: Instant,
        proofPath: String?,
        attemptId: String? = null,
    ): TransitionResult<ApplicationStatus>

    /** Records a failure: [from] to `FAILED`. [attemptId] is required when [from] is `SUBMITTING`. */
    suspend fun markFailed(
        jobId: Long,
        from: ApplicationStatus,
        attemptId: String? = null,
    ): TransitionResult<ApplicationStatus>

    /** Moves one `SUBMITTING` attempt to `CHECK_MANUALLY` because its outcome is unknown. */
    suspend fun markCheckManually(
        jobId: Long,
        attemptId: String,
    ): TransitionResult<ApplicationStatus>

    /**
     * Moves every application still in `SUBMITTING` to `CHECK_MANUALLY` and returns how many moved.
     * Call it only when no apply session is running (at process start), because it cannot tell a
     * crashed attempt from a live one.
     */
    suspend fun recoverInterruptedSubmissions(): Int
}

/**
 * Runs several repository calls as one database transaction, so related status changes (an
 * application and its job) commit together or not at all.
 */
interface TransactionRunner {
    /** Runs [block] in a transaction; an exception thrown from it rolls back every write it made. */
    suspend fun <T> run(block: suspend () -> T): T
}
