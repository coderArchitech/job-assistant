package com.coderarchitech.jobassistant.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.coderarchitech.jobassistant.core.data.db.entity.ApplicationEntity
import com.coderarchitech.jobassistant.core.data.db.entity.OutreachMessageEntity
import com.coderarchitech.jobassistant.core.model.ApplicationStatus
import com.coderarchitech.jobassistant.core.model.OutreachStatus
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Reads applications and inserts new ones. Like [JobDao], it has no `@Update`: the only status
 * writes are the compare-and-set queries below, called only through `ApplicationRepository`, so an
 * attempt can never be rewritten by a stale caller.
 */
@Dao
interface ApplicationDao {
    /** Inserts an application and returns its id; a second application for the same job fails. */
    @Insert
    suspend fun insert(application: ApplicationEntity): Long

    /** The application for [jobId], or null. */
    @Query("SELECT * FROM application WHERE jobId = :jobId")
    suspend fun getByJobId(jobId: Long): ApplicationEntity?

    /** Applications in [status], oldest first. */
    @Query("SELECT * FROM application WHERE status = :status ORDER BY id")
    suspend fun getByStatus(status: ApplicationStatus): List<ApplicationEntity>

    /** Emits every application, newest first, whenever the table changes. */
    @Query("SELECT * FROM application ORDER BY id DESC")
    fun observeAll(): Flow<List<ApplicationEntity>>

    /**
     * Sets the status of the job's application to [to] only if it is still [from] and, when [attemptId]
     * is given, only if it belongs to that attempt, in one statement. A non-null [submittedAt] or
     * [proofPath] is stored; a null one leaves the stored value alone. Returns the rows changed (1 or 0).
     * Call it only through `ApplicationRepository`, which validates the move.
     */
    @Query(
        "UPDATE application SET status = :to, submittedAt = COALESCE(:submittedAt, submittedAt), " +
            "proofPath = COALESCE(:proofPath, proofPath) " +
            "WHERE jobId = :jobId AND status = :from AND (:attemptId IS NULL OR attemptId = :attemptId)",
    )
    @Suppress("LongParameterList") // One SQL statement with one named value per column it touches.
    suspend fun compareAndSetStatus(
        jobId: Long,
        from: ApplicationStatus,
        to: ApplicationStatus,
        submittedAt: Instant?,
        proofPath: String?,
        attemptId: String?,
    ): Int

    /** Moves every application in [from] to [to] and returns how many moved; see `recoverInterruptedSubmissions`. */
    @Query("UPDATE application SET status = :to WHERE status = :from")
    suspend fun compareAndSetAllStatus(
        from: ApplicationStatus,
        to: ApplicationStatus,
    ): Int
}

/** Reads outreach messages and inserts new ones; status changes follow the same rule as [ApplicationDao]. */
@Dao
interface OutreachMessageDao {
    /** Inserts a message and returns its id. */
    @Insert
    suspend fun insert(message: OutreachMessageEntity): Long

    /** The message with [id], or null. */
    @Query("SELECT * FROM outreach_message WHERE id = :id")
    suspend fun getById(id: Long): OutreachMessageEntity?

    /** Emits the messages for one job, oldest first. */
    @Query("SELECT * FROM outreach_message WHERE jobId = :jobId ORDER BY id")
    fun observeForJob(jobId: Long): Flow<List<OutreachMessageEntity>>

    /** Emits the messages in [status], newest first. */
    @Query("SELECT * FROM outreach_message WHERE status = :status ORDER BY id DESC")
    fun observeByStatus(status: OutreachStatus): Flow<List<OutreachMessageEntity>>

    /** Sent messages whose follow-up is due at [now] and that have not been replied to. */
    @Query(
        "SELECT * FROM outreach_message WHERE status = 'SENT' AND repliedAt IS NULL " +
            "AND followUpDueAt IS NOT NULL AND followUpDueAt <= :now ORDER BY followUpDueAt",
    )
    suspend fun getFollowUpsDue(now: Instant): List<OutreachMessageEntity>
}
