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
 * Reads applications and inserts new ones. Like [JobDao], it has no `@Update`: the
 * `SUBMITTING` and `SUBMITTED` writes go through guarded transitions (tasks T1.05 and T3.21), so an
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
