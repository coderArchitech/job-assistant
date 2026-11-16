package com.coderarchitech.jobassistant.core.model

import java.time.Instant

/** Recruiter email goes through Gmail; LinkedIn is drafted only and sent by the user. */
enum class OutreachChannel { EMAIL, LINKEDIN }

/** Lifecycle of one outreach message. */
enum class OutreachStatus { DRAFT, APPROVED, SENT, REPLIED, FAILED }

/** A recruiter message for one job (PRD FR7). */
data class OutreachMessage(
    val id: Long,
    val jobId: Long,
    val channel: OutreachChannel,
    /** Email address, or LinkedIn profile URL. */
    val recipient: String,
    val draft: String,
    val status: OutreachStatus,
    val gmailThreadId: String?,
    val sentAt: Instant?,
    val repliedAt: Instant?,
    val followUpDueAt: Instant?,
) {
    init {
        require(channel == OutreachChannel.EMAIL || gmailThreadId == null) { "Only email has a Gmail thread" }
    }
}
