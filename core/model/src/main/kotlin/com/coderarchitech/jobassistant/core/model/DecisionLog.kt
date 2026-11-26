package com.coderarchitech.jobassistant.core.model

import java.time.Instant

/** A user's decision with the job it was about, as the scoring prompt shows it (design, Prompt layout for caching). */
data class DecisionExample(
    val verdict: Verdict,
    val reason: String?,
    val decidedAt: Instant,
    val title: String,
    val company: String,
    val location: String,
    val workMode: WorkMode,
)

/** Approve and reject decisions (task T2.18). Implemented over Room in `:core:data`. */
interface DecisionLog {
    /**
     * Records the user's [verdict] on job [jobId]. A blank [reason] is stored as none, and a long one is
     * cut to [MAX_REASON_LENGTH] characters. A later decision on the same job replaces the earlier one as
     * its example.
     */
    suspend fun record(
        jobId: Long,
        verdict: Verdict,
        reason: String?,
    ): Decision

    /** The latest decision on each of the [limit] most recently decided jobs, newest first. */
    suspend fun latestExamples(limit: Int): List<DecisionExample>

    companion object {
        /** A reason is a short note, not an essay; it goes into every scoring prompt. */
        const val MAX_REASON_LENGTH = 280
    }
}
