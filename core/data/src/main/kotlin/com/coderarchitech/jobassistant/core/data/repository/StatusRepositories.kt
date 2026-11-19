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
     * status but never to an internal one, and from `SUBMITTING` only to `APPLIED` or `FAILED`. Still
     * fails with [TransitionResult.Conflict] if the job moved since the user's screen was drawn.
     */
    suspend fun transitionManually(
        jobId: Long,
        from: JobStatus,
        to: JobStatus,
    ): TransitionResult<JobStatus>
}

/**
 * Moves applications between statuses. `SUBMITTING` is written before the click and confirmed after;
 * it is never retried automatically (CLAUDE.md rule 5). The methods that settle a live attempt take its
 * attempt ID, so a stale session cannot confirm or fail a newer attempt; the `…Manually` methods are
 * for the user settling a record that is no longer in flight, and refuse `SUBMITTING`.
 */
interface ApplicationRepository {
    /** Records a confirmed submission of attempt [attemptId]: `SUBMITTING` to `SUBMITTED`. */
    suspend fun markSubmitted(
        jobId: Long,
        attemptId: String,
        submittedAt: Instant,
        proofPath: String?,
    ): TransitionResult<ApplicationStatus>

    /** Records that attempt [attemptId] failed before anything was submitted: `SUBMITTING` to `FAILED`. */
    suspend fun markFailed(
        jobId: Long,
        attemptId: String,
    ): TransitionResult<ApplicationStatus>

    /** Moves attempt [attemptId] from `SUBMITTING` to `CHECK_MANUALLY` because its outcome is unknown. */
    suspend fun markCheckManually(
        jobId: Long,
        attemptId: String,
    ): TransitionResult<ApplicationStatus>

    /**
     * Records that the user confirmed the application was submitted, from `CHECK_MANUALLY` (they found
     * it on the portal) or `APPLY_MANUALLY` (they applied there by hand). A null [proofPath] keeps any
     * stored one. [TransitionResult.NotAllowed] if [from] is `SUBMITTING`.
     */
    suspend fun confirmSubmittedManually(
        jobId: Long,
        from: ApplicationStatus,
        submittedAt: Instant,
        proofPath: String?,
    ): TransitionResult<ApplicationStatus>

    /**
     * Records that the user found the application was not submitted, from `CHECK_MANUALLY`.
     * [TransitionResult.NotAllowed] if [from] is `SUBMITTING`.
     */
    suspend fun markFailedManually(
        jobId: Long,
        from: ApplicationStatus,
    ): TransitionResult<ApplicationStatus>

    /**
     * Moves every application still in `SUBMITTING` to `CHECK_MANUALLY` and returns how many moved.
     * Call it once at process start, before any apply session can begin, because it cannot tell a
     * crashed attempt from a live one. The matching jobs stay `SUBMITTING` until the user settles them.
     */
    suspend fun recoverInterruptedSubmissions(): Int
}

/**
 * Runs several repository calls as one database transaction, so related status changes (an
 * application and its job) commit together or not at all.
 */
interface TransactionRunner {
    /**
     * Runs [block] in a transaction; an exception thrown from it rolls back every write it made. The
     * repositories report a refused or lost move as a return value, not an exception, so call
     * [requireMoved] on each result inside [block] to roll everything back when one does not move.
     */
    suspend fun <T> run(block: suspend () -> T): T
}

/** Thrown by [requireMoved] when a transition did not move; [result] says why. */
class TransitionFailedException(
    val result: TransitionResult<*>,
) : IllegalStateException("The transition did not move: $result")

/**
 * Throws [TransitionFailedException] unless this result is [TransitionResult.Moved]. Inside
 * [TransactionRunner.run] it rolls back the whole transaction; catch the exception outside it.
 */
fun TransitionResult<*>.requireMoved() {
    if (this != TransitionResult.Moved) throw TransitionFailedException(this)
}
