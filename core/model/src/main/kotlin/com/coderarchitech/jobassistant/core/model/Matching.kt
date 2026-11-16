package com.coderarchitech.jobassistant.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** Match tier shown on job cards and used by filters (PRD FR4). Computed from a score, never stored. */
@Serializable
enum class Tier { HIGH, PARTIAL, LOW }

/**
 * The user's tier thresholds. Defaults are 75 and 50; changing them re-tiers stored scores
 * without any AI call (BR4).
 */
data class TierThresholds(
    val high: Int = DEFAULT_HIGH,
    val partial: Int = DEFAULT_PARTIAL,
) {
    init {
        require(partial in 0..MAX_SCORE && high in 0..MAX_SCORE) { "Thresholds must be between 0 and $MAX_SCORE" }
        require(partial < high) { "The Partial threshold must be below the High threshold" }
    }

    /** The tier for a 0–100 [score]. */
    fun tierFor(score: Int): Tier {
        require(score in 0..MAX_SCORE) { "Score must be between 0 and $MAX_SCORE" }
        return when {
            score >= high -> Tier.HIGH
            score >= partial -> Tier.PARTIAL
            else -> Tier.LOW
        }
    }

    companion object {
        const val DEFAULT_HIGH = 75
        const val DEFAULT_PARTIAL = 50
        const val MAX_SCORE = 100
    }
}

/** Whether a "remote" job is actually open to the user's location. */
enum class RemoteEligibility { YES, NO, UNCLEAR }

/** Which layer of the scoring cascade produced the score. */
enum class ScoreStage { HARD_FILTER, EMBEDDING, LLM }

/** The scoring result for one job against one profile version (design: Matching and scoring). */
data class MatchResult(
    val jobId: Long,
    val profileVersion: Int,
    val score: Int,
    val remoteEligible: RemoteEligibility,
    val matchedSkills: List<String>,
    val missingSkills: List<String>,
    val redFlags: List<String>,
    /** One line; the hard-filter rule name when [decidedBy] is [ScoreStage.HARD_FILTER]. */
    val reason: String,
    val decidedBy: ScoreStage,
    /** The model that scored the job; null when no LLM was called. */
    val model: String?,
    val scoredAt: Instant,
) {
    init {
        require(score in 0..TierThresholds.MAX_SCORE) { "Score must be between 0 and ${TierThresholds.MAX_SCORE}" }
        require(reason.length <= MAX_REASON_LENGTH) { "Reason is limited to $MAX_REASON_LENGTH characters" }
        require(decidedBy != ScoreStage.LLM || model != null) { "An LLM score must name its model" }
    }

    /** The tier under the user's current [thresholds]. */
    fun tier(thresholds: TierThresholds): Tier = thresholds.tierFor(score)

    companion object {
        /** Matches the scoring schema's `reason.maxLength`. */
        const val MAX_REASON_LENGTH = 140
    }
}
