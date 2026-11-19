package com.coderarchitech.jobassistant.core.model

/**
 * Which moves between job statuses the pipeline may make (design: Job status diagram). A repository
 * checks a move against this table, then writes it only if the stored status is still the one the
 * caller read (CLAUDE.md: status changes use an expected-status precondition).
 *
 * Tailoring never changes a job's status before the user approves it, so [JobStatus.READY] is
 * reachable only from [JobStatus.APPROVED] (and back from [JobStatus.NEEDS_INPUT]).
 */
object JobTransitions {
    private val pipeline: Map<JobStatus, Set<JobStatus>> =
        mapOf(
            // FAILED: scoring output that stays invalid after one retry (design: AI provider layer).
            JobStatus.FOUND to setOf(JobStatus.SCORED, JobStatus.FAILED),
            JobStatus.SCORED to setOf(JobStatus.APPROVED, JobStatus.SKIPPED),
            JobStatus.APPROVED to setOf(JobStatus.READY, JobStatus.FAILED),
            JobStatus.READY to setOf(JobStatus.NEEDS_INPUT, JobStatus.SUBMITTING, JobStatus.FAILED),
            JobStatus.NEEDS_INPUT to setOf(JobStatus.READY, JobStatus.SUBMITTING, JobStatus.FAILED),
            JobStatus.SUBMITTING to setOf(JobStatus.APPLIED, JobStatus.FAILED),
            JobStatus.APPLIED to setOf(JobStatus.REPLIED, JobStatus.REJECTED),
            JobStatus.REPLIED to setOf(JobStatus.INTERVIEW, JobStatus.REJECTED),
            JobStatus.INTERVIEW to setOf(JobStatus.OFFER, JobStatus.REJECTED),
        )

    /** The statuses the pipeline may move a job to from [from]; empty for a final status. */
    fun next(from: JobStatus): Set<JobStatus> = pipeline[from].orEmpty()

    /** True if the pipeline may move a job from [from] to [to]. */
    fun isAllowed(
        from: JobStatus,
        to: JobStatus,
    ): Boolean = to in next(from)

    /**
     * True if the user may set [to] by hand (PRD FR12): any status the UI shows, other than the one
     * the job already has. The internal states are never set by hand, so a manual change cannot put a
     * job into the middle of an apply attempt. From `SUBMITTING` (a click in flight, or a crashed
     * attempt waiting in "Check manually") the user may only settle the attempt, as `APPLIED` or
     * `FAILED`; they cannot send the job back to `APPROVED` to be applied to again. A second application
     * for the job is also impossible because there is at most one application row per job.
     */
    fun isAllowedManually(
        from: JobStatus,
        to: JobStatus,
    ): Boolean =
        from != to &&
            !to.isInternal &&
            (from != JobStatus.SUBMITTING || to == JobStatus.APPLIED || to == JobStatus.FAILED)
}

/**
 * Which moves between application statuses are allowed. `SUBMITTING` is written before the click and
 * is never retried automatically (CLAUDE.md rule 5), so the only ways out are a confirmed submission,
 * a failure, or [ApplicationStatus.CHECK_MANUALLY] after a crash.
 */
object ApplicationTransitions {
    private val table: Map<ApplicationStatus, Set<ApplicationStatus>> =
        mapOf(
            ApplicationStatus.SUBMITTING to
                setOf(ApplicationStatus.SUBMITTED, ApplicationStatus.CHECK_MANUALLY, ApplicationStatus.FAILED),
            // The user checked the portal by hand and found the application there, or not.
            ApplicationStatus.CHECK_MANUALLY to setOf(ApplicationStatus.SUBMITTED, ApplicationStatus.FAILED),
            // The user applied on the portal themselves, using the prefilled answers.
            ApplicationStatus.APPLY_MANUALLY to setOf(ApplicationStatus.SUBMITTED),
        )

    /** The statuses an application may move to from [from]; empty for a final status. */
    fun next(from: ApplicationStatus): Set<ApplicationStatus> = table[from].orEmpty()

    /** True if an application may move from [from] to [to]. */
    fun isAllowed(
        from: ApplicationStatus,
        to: ApplicationStatus,
    ): Boolean = to in next(from)
}

/** The outcome of asking a repository to move a record from one status to another. */
sealed interface TransitionResult<out S> {
    /** The record had the expected status and now has the new one. */
    data object Moved : TransitionResult<Nothing>

    /** No record with that ID exists. */
    data object NotFound : TransitionResult<Nothing>

    /** The move is not in the transition table; nothing was written. */
    data class NotAllowed<S>(
        val from: S,
        val to: S,
    ) : TransitionResult<S>

    /** The record's status was not the expected one, so another writer got there first; nothing was written. */
    data class Conflict<S>(
        val actual: S,
    ) : TransitionResult<S>

    /**
     * The status was as expected but the record belongs to a different submission attempt, so the
     * caller is a stale session. Only application transitions that carry an attempt ID return this.
     */
    data object AttemptMismatch : TransitionResult<Nothing>
}
