package com.coderarchitech.jobassistant.core.model

import java.time.Instant

/** A normalized job posting from any source (PRD FR3). */
data class Job(
    val id: Long,
    /** Source key, such as "greenhouse:stripe", "remotive" or "naukri". */
    val source: String,
    val externalId: String,
    val company: String,
    val title: String,
    val location: String,
    val workMode: WorkMode,
    /** Region or work-authorization limit found in the posting, such as "US only". */
    val regionRestriction: String?,
    val salary: Salary?,
    val description: String,
    val url: String,
    val canonicalUrl: String,
    val postedAt: Instant?,
    /** SHA-256 of normalized company and title, used to merge duplicates across sources. */
    val dedupeHash: String,
    val status: JobStatus,
    val firstSeenAt: Instant,
    /** Set when this row is a duplicate of another job, which is the primary copy. */
    val aliasOfJobId: Long? = null,
)

/**
 * Where a job is in the pipeline. The first ten values are the PRD statuses; [READY],
 * [NEEDS_INPUT] and [SUBMITTING] are internal and shown to the user as [APPROVED] with a badge.
 */
enum class JobStatus(
    val isInternal: Boolean = false,
) {
    FOUND,
    SCORED,
    APPROVED,
    READY(isInternal = true),
    NEEDS_INPUT(isInternal = true),
    SUBMITTING(isInternal = true),
    APPLIED,
    REPLIED,
    INTERVIEW,
    OFFER,
    REJECTED,
    SKIPPED,
    FAILED,
    ;

    /** The status shown in the UI: internal states display as [APPROVED]. */
    val userFacing: JobStatus
        get() = if (isInternal) APPROVED else this

    /** True when nothing more happens to the job automatically. */
    val isFinal: Boolean
        get() = this == OFFER || this == REJECTED || this == SKIPPED || this == FAILED
}
