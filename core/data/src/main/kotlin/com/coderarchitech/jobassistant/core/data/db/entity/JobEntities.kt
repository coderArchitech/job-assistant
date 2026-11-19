package com.coderarchitech.jobassistant.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coderarchitech.jobassistant.core.model.Decision
import com.coderarchitech.jobassistant.core.model.Draft
import com.coderarchitech.jobassistant.core.model.DraftKind
import com.coderarchitech.jobassistant.core.model.Job
import com.coderarchitech.jobassistant.core.model.JobStatus
import com.coderarchitech.jobassistant.core.model.MatchResult
import com.coderarchitech.jobassistant.core.model.RemoteEligibility
import com.coderarchitech.jobassistant.core.model.Salary
import com.coderarchitech.jobassistant.core.model.SalaryPeriod
import com.coderarchitech.jobassistant.core.model.ScoreStage
import com.coderarchitech.jobassistant.core.model.Verdict
import com.coderarchitech.jobassistant.core.model.WorkMode
import java.time.Instant

/**
 * Stored form of [Job]. The salary is flattened into four columns so the Jobs screen can filter on it
 * in SQL. A duplicate points at its primary copy through [aliasOfJobId]; deleting the primary
 * leaves the duplicate as a standalone job.
 */
@Entity(
    tableName = "job",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["aliasOfJobId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["source", "externalId"], unique = true),
        Index(value = ["dedupeHash"]),
        Index(value = ["canonicalUrl"]),
        Index(value = ["status"]),
        Index(value = ["aliasOfJobId"]),
    ],
)
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val externalId: String,
    val company: String,
    val title: String,
    val location: String,
    val workMode: WorkMode,
    val regionRestriction: String?,
    val salaryMin: Long?,
    val salaryMax: Long?,
    val salaryCurrency: String?,
    val salaryPeriod: SalaryPeriod?,
    val description: String,
    val url: String,
    val canonicalUrl: String,
    val postedAt: Instant?,
    val dedupeHash: String,
    val status: JobStatus,
    val firstSeenAt: Instant,
    val aliasOfJobId: Long?,
)

/** Stored form of [MatchResult]: one score per job and profile version. */
@Entity(
    tableName = "match_result",
    primaryKeys = ["jobId", "profileVersion"],
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["score"])],
)
data class MatchResultEntity(
    val jobId: Long,
    val profileVersion: Int,
    val score: Int,
    val remoteEligible: RemoteEligibility,
    val matchedSkills: List<String>,
    val missingSkills: List<String>,
    val redFlags: List<String>,
    val reason: String,
    val decidedBy: ScoreStage,
    val model: String?,
    val scoredAt: Instant,
)

/** Stored form of [Draft]: one draft of each kind per job. */
@Entity(
    tableName = "draft",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["jobId", "kind"], unique = true)],
)
data class DraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val kind: DraftKind,
    val content: String,
    val unknownFields: List<String>,
    val editedByUser: Boolean,
    val createdAt: Instant,
)

/** Stored form of [Decision]; the latest rows feed scoring prompts as examples. */
@Entity(
    tableName = "decision",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["jobId"]), Index(value = ["decidedAt"])],
)
data class DecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobId: Long,
    val verdict: Verdict,
    val reason: String?,
    val decidedAt: Instant,
)

/** Maps a job to its stored row. */
fun Job.toEntity() =
    JobEntity(
        id = id,
        source = source,
        externalId = externalId,
        company = company,
        title = title,
        location = location,
        workMode = workMode,
        regionRestriction = regionRestriction,
        salaryMin = salary?.min,
        salaryMax = salary?.max,
        salaryCurrency = salary?.currency,
        salaryPeriod = salary?.period,
        description = description,
        url = url,
        canonicalUrl = canonicalUrl,
        postedAt = postedAt,
        dedupeHash = dedupeHash,
        status = status,
        firstSeenAt = firstSeenAt,
        aliasOfJobId = aliasOfJobId,
    )

/** Maps a stored job row back to the domain type. */
fun JobEntity.toModel() =
    Job(
        id = id,
        source = source,
        externalId = externalId,
        company = company,
        title = title,
        location = location,
        workMode = workMode,
        regionRestriction = regionRestriction,
        salary = salaryCurrency?.let { Salary(salaryMin, salaryMax, it, salaryPeriod ?: SalaryPeriod.YEAR) },
        description = description,
        url = url,
        canonicalUrl = canonicalUrl,
        postedAt = postedAt,
        dedupeHash = dedupeHash,
        status = status,
        firstSeenAt = firstSeenAt,
        aliasOfJobId = aliasOfJobId,
    )

/** Maps a match result to its stored row. */
fun MatchResult.toEntity() =
    MatchResultEntity(
        jobId = jobId,
        profileVersion = profileVersion,
        score = score,
        remoteEligible = remoteEligible,
        matchedSkills = matchedSkills,
        missingSkills = missingSkills,
        redFlags = redFlags,
        reason = reason,
        decidedBy = decidedBy,
        model = model,
        scoredAt = scoredAt,
    )

/** Maps a stored match row back to the domain type. */
fun MatchResultEntity.toModel() =
    MatchResult(
        jobId = jobId,
        profileVersion = profileVersion,
        score = score,
        remoteEligible = remoteEligible,
        matchedSkills = matchedSkills,
        missingSkills = missingSkills,
        redFlags = redFlags,
        reason = reason,
        decidedBy = decidedBy,
        model = model,
        scoredAt = scoredAt,
    )

/** Maps a draft to its stored row. */
fun Draft.toEntity() = DraftEntity(id, jobId, kind, content, unknownFields, editedByUser, createdAt)

/** Maps a stored draft row back to the domain type. */
fun DraftEntity.toModel() = Draft(id, jobId, kind, content, unknownFields, editedByUser, createdAt)

/** Maps a decision to its stored row. */
fun Decision.toEntity() = DecisionEntity(id, jobId, verdict, reason, decidedAt)

/** Maps a stored decision row back to the domain type. */
fun DecisionEntity.toModel() = Decision(id, jobId, verdict, reason, decidedAt)
