package com.coderarchitech.jobassistant.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.model.DailyReport
import com.coderarchitech.jobassistant.core.model.JobStatus
import com.coderarchitech.jobassistant.core.model.Run
import com.coderarchitech.jobassistant.core.model.RunError
import com.coderarchitech.jobassistant.core.model.RunStage
import com.coderarchitech.jobassistant.core.model.RunStatus
import com.coderarchitech.jobassistant.core.model.UsageRecord
import java.time.Instant
import java.time.LocalDate

/** Stored form of [Run]; the source of the run log. A deleted preset leaves its runs in the log. */
@Entity(
    tableName = "run",
    foreignKeys = [
        ForeignKey(
            entity = SearchPresetEntity::class,
            parentColumns = ["id"],
            childColumns = ["presetId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["presetId"]), Index(value = ["startedAt"])],
)
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val presetId: Long?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val status: RunStatus,
    val countsPerStage: Map<RunStage, Int>,
    val errors: List<RunError>,
)

/** Stored form of [UsageRecord]: one row per AI call. */
@Entity(
    tableName = "usage_record",
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["runId"]), Index(value = ["recordedAt"])],
)
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long?,
    val provider: AiProvider,
    val model: String,
    val task: AiTask,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val estimatedCostMicros: Long,
    val recordedAt: Instant,
)

/**
 * Stored form of [DailyReport], one row per date. [countsByStatus] is keyed by [JobStatus] constant
 * name, which is a storage format.
 */
@Entity(tableName = "daily_report")
data class DailyReportEntity(
    @PrimaryKey val date: LocalDate,
    val countsByStatus: Map<String, Int>,
    val appliedByPortal: Map<Long, Int>,
    val emailsSent: Int,
    val linkedInDraftsPending: Int,
    val replies: Int,
    val failures: Int,
    val costMicros: Long,
)

/** Maps a run to its stored row. */
fun Run.toEntity() = RunEntity(id, presetId, startedAt, endedAt, status, countsPerStage, errors)

/** Maps a stored run row back to the domain type. */
fun RunEntity.toModel() = Run(id, presetId, startedAt, endedAt, status, countsPerStage, errors)

/** Maps a usage record to its stored row. */
fun UsageRecord.toEntity() =
    UsageRecordEntity(
        id = id,
        runId = runId,
        provider = provider,
        model = model,
        task = task,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedTokens = cachedTokens,
        estimatedCostMicros = estimatedCostMicros,
        recordedAt = recordedAt,
    )

/** Maps a stored usage row back to the domain type. */
fun UsageRecordEntity.toModel() =
    UsageRecord(
        id = id,
        runId = runId,
        provider = provider,
        model = model,
        task = task,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedTokens = cachedTokens,
        estimatedCostMicros = estimatedCostMicros,
        recordedAt = recordedAt,
    )

/** Maps a daily report to its stored row. */
fun DailyReport.toEntity() =
    DailyReportEntity(
        date = date,
        countsByStatus = countsByStatus.mapKeys { it.key.name },
        appliedByPortal = appliedByPortal,
        emailsSent = emailsSent,
        linkedInDraftsPending = linkedInDraftsPending,
        replies = replies,
        failures = failures,
        costMicros = costMicros,
    )

/**
 * Maps a stored report row back to the domain type. A status name this app version does not know
 * (a row restored from a newer version) is dropped rather than failing the whole report.
 */
fun DailyReportEntity.toModel() =
    DailyReport(
        date = date,
        countsByStatus =
            countsByStatus
                .mapNotNull { (name, count) -> JobStatus.entries.firstOrNull { it.name == name }?.let { it to count } }
                .toMap(),
        appliedByPortal = appliedByPortal,
        emailsSent = emailsSent,
        linkedInDraftsPending = linkedInDraftsPending,
        replies = replies,
        failures = failures,
        costMicros = costMicros,
    )
