package com.coderarchitech.jobassistant.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/** Pipeline stages, in order (design: Agent pipeline and scheduling). */
@Serializable
enum class RunStage { DISCOVER, SCORE, TAILOR, APPLY, OUTREACH, REPORT }

/** How a run ended. */
enum class RunStatus { RUNNING, SUCCEEDED, PARTIALLY_FAILED, FAILED, CANCELLED }

/** One error recorded during a run, shown in the run log. */
@Serializable
data class RunError(
    val stage: RunStage,
    val message: String,
    /** Source, portal or job the error is about, when there is one. */
    val subject: String? = null,
)

/** One pipeline run and its per-stage counts. */
data class Run(
    val id: Long,
    /** Null for a manual "Run now" without a preset. */
    val presetId: Long?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val status: RunStatus,
    val countsPerStage: Map<RunStage, Int>,
    val errors: List<RunError>,
)

/** Where incremental discovery left off for one source. */
data class SourceCursor(
    val sourceKey: String,
    val lastSuccessAt: Instant?,
    val lastSeenExternalId: String?,
)

/** The AI providers the user can bring a key for. */
enum class AiProvider { ANTHROPIC, OPENAI }

/** What an AI call was for; each task can use its own model (PRD FR10). */
enum class AiTask { PARSE_RESUME, EXPAND_CONTEXT, SCORE, EXTRACT_POSTINGS, TAILOR, MAP_QUESTION, BROWSER_STEP }

/**
 * Tokens and estimated cost of one AI call. Cost is in micro-US-dollars (1_000_000 = $1) so sums
 * stay exact.
 */
data class UsageRecord(
    val id: Long,
    val runId: Long?,
    val provider: AiProvider,
    val model: String,
    val task: AiTask,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val estimatedCostMicros: Long,
    val recordedAt: Instant,
) {
    init {
        require(inputTokens >= 0 && outputTokens >= 0 && cachedTokens >= 0) { "Token counts cannot be negative" }
        require(estimatedCostMicros >= 0) { "Cost cannot be negative" }
    }
}

/** The daily summary (PRD FR11), written by the report worker from the same queries the screens use. */
data class DailyReport(
    val date: LocalDate,
    val countsByStatus: Map<JobStatus, Int>,
    val appliedByPortal: Map<Long, Int>,
    val emailsSent: Int,
    val linkedInDraftsPending: Int,
    val replies: Int,
    val failures: Int,
    val costMicros: Long,
)
