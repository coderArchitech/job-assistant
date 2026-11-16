package com.coderarchitech.jobassistant.core.model

import java.time.Instant

/** Whether the user tapped Submit or the portal's auto-submit setting did. */
enum class ApplyMode { REVIEWED, AUTO }

/**
 * State of one submission attempt. [SUBMITTING] is written before the click and is never retried
 * automatically; if the app dies there, the attempt becomes [CHECK_MANUALLY].
 */
enum class ApplicationStatus { SUBMITTING, SUBMITTED, CHECK_MANUALLY, APPLY_MANUALLY, FAILED }

/** One application to one job; there is at most one per job. */
data class Application(
    val id: Long,
    val jobId: Long,
    val portalId: Long?,
    val status: ApplicationStatus,
    val mode: ApplyMode,
    /** Random per attempt, so a resumed session can tell its own attempt from an earlier one. */
    val attemptId: String,
    val submittingAt: Instant?,
    val submittedAt: Instant?,
    /** App-private path of the final-page screenshot. */
    val proofPath: String?,
    /** Question to answer, as filled in on the form. */
    val answersUsed: Map<String, String>,
) {
    init {
        require(status != ApplicationStatus.SUBMITTED || submittedAt != null) {
            "A submitted application needs submittedAt"
        }
    }
}
