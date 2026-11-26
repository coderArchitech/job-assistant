package com.coderarchitech.jobassistant.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coderarchitech.jobassistant.core.data.db.entity.DecisionEntity
import com.coderarchitech.jobassistant.core.data.db.entity.DraftEntity
import com.coderarchitech.jobassistant.core.data.db.entity.JobEntity
import com.coderarchitech.jobassistant.core.data.db.entity.MatchResultEntity
import com.coderarchitech.jobassistant.core.model.DecisionExample
import com.coderarchitech.jobassistant.core.model.DraftKind
import com.coderarchitech.jobassistant.core.model.JobStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** The number of jobs in one [status]. */
data class StatusCount(
    val status: JobStatus,
    val count: Int,
)

/**
 * Reads jobs and inserts new ones. There is deliberately no `@Update`: the only way to change a status
 * is [compareAndSetStatus], and only `JobRepository` calls it, after checking the move against
 * `JobTransitions`. Nothing else may write a status, so a stale writer can never overwrite a newer one.
 */
@Dao
@Suppress("TooManyFunctions") // A DAO is a list of queries; the job table has that many.
interface JobDao {
    /** Inserts a job and returns its id, or -1 if (source, externalId) already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(job: JobEntity): Long

    /** The job with [id], or null. */
    @Query("SELECT * FROM job WHERE id = :id")
    suspend fun getById(id: Long): JobEntity?

    /** Emits the job whenever it changes. */
    @Query("SELECT * FROM job WHERE id = :id")
    fun observeById(id: Long): Flow<JobEntity?>

    /** Dedupe step 1: the same posting seen again from the same source. */
    @Query("SELECT * FROM job WHERE source = :source AND externalId = :externalId")
    suspend fun findBySourceAndExternalId(
        source: String,
        externalId: String,
    ): JobEntity?

    /** Which of [externalIds] are stored for [source]. Keep the list under 990: SQLite allows 999 variables. */
    @Query("SELECT externalId FROM job WHERE source = :source AND externalId IN (:externalIds)")
    suspend fun existingExternalIds(
        source: String,
        externalIds: List<String>,
    ): List<String>

    /** Dedupe step 2: the same canonical URL. */
    @Query("SELECT * FROM job WHERE canonicalUrl = :canonicalUrl ORDER BY id")
    suspend fun findByCanonicalUrl(canonicalUrl: String): List<JobEntity>

    /** Dedupe step 3: the same normalized company and title. */
    @Query("SELECT * FROM job WHERE dedupeHash = :dedupeHash ORDER BY id")
    suspend fun findByDedupeHash(dedupeHash: String): List<JobEntity>

    /** Jobs in [status], oldest first, for a pipeline stage to pick up. */
    @Query("SELECT * FROM job WHERE status = :status ORDER BY id LIMIT :limit")
    suspend fun getByStatus(
        status: JobStatus,
        limit: Int,
    ): List<JobEntity>

    /** Emits jobs in [status], newest first, whenever the table changes. */
    @Query("SELECT * FROM job WHERE status = :status ORDER BY firstSeenAt DESC, id DESC")
    fun observeByStatus(status: JobStatus): Flow<List<JobEntity>>

    /** Emits every job, newest first, whenever the table changes. */
    @Query("SELECT * FROM job ORDER BY firstSeenAt DESC, id DESC")
    fun observeAll(): Flow<List<JobEntity>>

    /** The status of the job with [id], or null if there is no such job. */
    @Query("SELECT status FROM job WHERE id = :id")
    suspend fun getStatus(id: Long): JobStatus?

    /**
     * Sets the status to [to] only if it is still [from], in one statement, and returns the number of
     * rows changed (1, or 0 if the job is missing or another writer moved it first). Call it only
     * through `JobRepository`, which validates the move.
     */
    @Query("UPDATE job SET status = :to WHERE id = :id AND status = :from")
    suspend fun compareAndSetStatus(
        id: Long,
        from: JobStatus,
        to: JobStatus,
    ): Int

    /** Emits the number of jobs per status; statuses with no job are absent. */
    @Query("SELECT status AS status, COUNT(*) AS count FROM job GROUP BY status")
    fun observeStatusCounts(): Flow<List<StatusCount>>
}

/** Reads and writes match results. */
@Dao
interface MatchResultDao {
    /** Inserts or replaces the score for a (job, profile version). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(result: MatchResultEntity)

    /** The score for [jobId] under [profileVersion], or null if it was not scored under that version. */
    @Query("SELECT * FROM match_result WHERE jobId = :jobId AND profileVersion = :profileVersion")
    suspend fun get(
        jobId: Long,
        profileVersion: Int,
    ): MatchResultEntity?

    /** The most recent score for [jobId] under any profile version. */
    @Query("SELECT * FROM match_result WHERE jobId = :jobId ORDER BY profileVersion DESC LIMIT 1")
    suspend fun getLatestForJob(jobId: Long): MatchResultEntity?

    /** Emits every score made under [profileVersion], best first, whenever the table changes. */
    @Query("SELECT * FROM match_result WHERE profileVersion = :profileVersion ORDER BY score DESC, jobId")
    fun observeByProfileVersion(profileVersion: Int): Flow<List<MatchResultEntity>>
}

/** Reads and writes tailored drafts. */
@Dao
interface DraftDao {
    /**
     * Inserts a draft and returns its id, or -1 if the job already has a draft of that kind. It never
     * overwrites: a draft the user edited must not be lost to a new generation.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(draft: DraftEntity): Long

    /**
     * Replaces the text of a generated draft with newly generated text, keeping its id. A draft the
     * user has edited is left alone. Returns the number of rows changed: 1, or 0 if there is no draft
     * of that kind or the user edited it.
     */
    @Query(
        "UPDATE draft SET content = :content, unknownFields = :unknownFields, createdAt = :createdAt " +
            "WHERE jobId = :jobId AND kind = :kind AND editedByUser = 0",
    )
    suspend fun regenerate(
        jobId: Long,
        kind: DraftKind,
        content: String,
        unknownFields: List<String>,
        createdAt: Instant,
    ): Int

    /** Saves the user's edit and marks the draft as edited. Returns the number of rows changed (0 or 1). */
    @Query(
        "UPDATE draft SET content = :content, unknownFields = :unknownFields, editedByUser = 1 " +
            "WHERE jobId = :jobId AND kind = :kind",
    )
    suspend fun saveEdit(
        jobId: Long,
        kind: DraftKind,
        content: String,
        unknownFields: List<String>,
    ): Int

    /** The draft of [kind] for [jobId], or null. */
    @Query("SELECT * FROM draft WHERE jobId = :jobId AND kind = :kind")
    suspend fun get(
        jobId: Long,
        kind: DraftKind,
    ): DraftEntity?

    /** Emits all drafts for a job whenever they change. */
    @Query("SELECT * FROM draft WHERE jobId = :jobId ORDER BY id")
    fun observeForJob(jobId: Long): Flow<List<DraftEntity>>
}

/** Reads and writes approve and reject decisions. */
@Dao
interface DecisionDao {
    /** Records a decision and returns its id. */
    @Insert
    suspend fun insert(decision: DecisionEntity): Long

    /**
     * The latest decision on each of the [limit] most recently decided jobs, newest first, with the job's
     * title, company, location and work mode. A job decided twice appears once, with its later decision.
     * "Later" is insert order (the id), not `decidedAt`, so a clock set back cannot revive an older verdict;
     * `MAX(id) GROUP BY jobId` reads the `jobId` index, so the cost does not grow with the whole history.
     */
    @Query(
        """
        SELECT d.verdict AS verdict, d.reason AS reason, d.decidedAt AS decidedAt,
               j.title AS title, j.company AS company, j.location AS location, j.workMode AS workMode
        FROM (SELECT MAX(id) AS id FROM decision GROUP BY jobId) latest
        JOIN decision d ON d.id = latest.id
        JOIN job j ON j.id = d.jobId
        ORDER BY d.id DESC
        LIMIT :limit
        """,
    )
    suspend fun getLatestExamples(limit: Int): List<DecisionExample>

    /** The most recent decision for [jobId], or null. */
    @Query("SELECT * FROM decision WHERE jobId = :jobId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestForJob(jobId: Long): DecisionEntity?
}
