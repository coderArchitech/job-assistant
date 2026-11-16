package com.coderarchitech.jobassistant.core.model

import java.time.Instant

/** The kinds of text tailored for each job (PRD FR5). */
enum class DraftKind { COVER_LETTER, ANSWERS, EMAIL, LINKEDIN }

/** Generated text for one job. What the user edits is what gets used. */
data class Draft(
    val id: Long,
    val jobId: Long,
    val kind: DraftKind,
    val content: String,
    /** Questions or fields the profile could not answer; non-empty means NeedsInput. */
    val unknownFields: List<String>,
    val editedByUser: Boolean,
    val createdAt: Instant,
)

/** Approve or reject. */
enum class Verdict { APPROVE, REJECT }

/** A user's approve or reject decision; recent decisions feed scoring prompts as examples. */
data class Decision(
    val id: Long,
    val jobId: Long,
    val verdict: Verdict,
    val reason: String?,
    val decidedAt: Instant,
)
