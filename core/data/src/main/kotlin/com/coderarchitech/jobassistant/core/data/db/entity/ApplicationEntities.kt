package com.coderarchitech.jobassistant.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coderarchitech.jobassistant.core.model.Application
import com.coderarchitech.jobassistant.core.model.ApplicationStatus
import com.coderarchitech.jobassistant.core.model.ApplyMode
import com.coderarchitech.jobassistant.core.model.OutreachChannel
import com.coderarchitech.jobassistant.core.model.OutreachMessage
import com.coderarchitech.jobassistant.core.model.OutreachStatus
import java.time.Instant

/**
 * Stored form of [Application]. The unique index on [jobId] enforces one application per job. The
 * job link has no cascade: a job that has an application cannot be deleted, so a `SUBMITTED` record
 * is never lost and the job cannot be applied to a second time after it is found again.
 */
@Entity(
    tableName = "application",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PortalEntity::class,
            parentColumns = ["id"],
            childColumns = ["portalId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["jobId"], unique = true),
        Index(value = ["portalId"]),
        Index(value = ["status"]),
    ],
)
data class ApplicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val portalId: Long?,
    val status: ApplicationStatus,
    val mode: ApplyMode,
    val attemptId: String,
    val submittingAt: Instant?,
    val submittedAt: Instant?,
    val proofPath: String?,
    val answersUsed: Map<String, String>,
)

/** Stored form of [OutreachMessage]. */
@Entity(
    tableName = "outreach_message",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["status"]),
        Index(value = ["followUpDueAt"]),
    ],
)
data class OutreachMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val channel: OutreachChannel,
    val recipient: String,
    val draft: String,
    val status: OutreachStatus,
    val gmailThreadId: String?,
    val sentAt: Instant?,
    val repliedAt: Instant?,
    val followUpDueAt: Instant?,
)

/** Maps an application to its stored row. */
fun Application.toEntity() =
    ApplicationEntity(
        id = id,
        jobId = jobId,
        portalId = portalId,
        status = status,
        mode = mode,
        attemptId = attemptId,
        submittingAt = submittingAt,
        submittedAt = submittedAt,
        proofPath = proofPath,
        answersUsed = answersUsed,
    )

/** Maps a stored application row back to the domain type. */
fun ApplicationEntity.toModel() =
    Application(
        id = id,
        jobId = jobId,
        portalId = portalId,
        status = status,
        mode = mode,
        attemptId = attemptId,
        submittingAt = submittingAt,
        submittedAt = submittedAt,
        proofPath = proofPath,
        answersUsed = answersUsed,
    )

/** Maps an outreach message to its stored row. */
fun OutreachMessage.toEntity() =
    OutreachMessageEntity(
        id = id,
        jobId = jobId,
        channel = channel,
        recipient = recipient,
        draft = draft,
        status = status,
        gmailThreadId = gmailThreadId,
        sentAt = sentAt,
        repliedAt = repliedAt,
        followUpDueAt = followUpDueAt,
    )

/** Maps a stored outreach row back to the domain type. */
fun OutreachMessageEntity.toModel() =
    OutreachMessage(
        id = id,
        jobId = jobId,
        channel = channel,
        recipient = recipient,
        draft = draft,
        status = status,
        gmailThreadId = gmailThreadId,
        sentAt = sentAt,
        repliedAt = repliedAt,
        followUpDueAt = followUpDueAt,
    )
