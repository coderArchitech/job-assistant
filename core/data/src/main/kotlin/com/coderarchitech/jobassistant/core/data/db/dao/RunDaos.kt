package com.coderarchitech.jobassistant.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coderarchitech.jobassistant.core.data.db.entity.DailyReportEntity
import com.coderarchitech.jobassistant.core.data.db.entity.RunEntity
import com.coderarchitech.jobassistant.core.data.db.entity.UsageRecordEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** Reads runs and inserts new ones; finishing a run is a guarded status transition added with the pipeline. */
@Dao
interface RunDao {
    /** Starts a run record and returns its id. */
    @Insert
    suspend fun insert(run: RunEntity): Long

    /** The run with [id], or null. */
    @Query("SELECT * FROM run WHERE id = :id")
    suspend fun getById(id: Long): RunEntity?

    /** Emits the latest [limit] runs, newest first, for the run log. */
    @Query("SELECT * FROM run ORDER BY startedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RunEntity>>
}

/** Writes and sums AI usage. */
@Dao
interface UsageRecordDao {
    /** Records one AI call and returns its id. */
    @Insert
    suspend fun insert(record: UsageRecordEntity): Long

    /** Total estimated cost in micro-US-dollars for calls recorded at or after [from]. */
    @Query("SELECT COALESCE(SUM(estimatedCostMicros), 0) FROM usage_record WHERE recordedAt >= :from")
    suspend fun sumCostMicrosSince(from: Instant): Long

    /** Emits the total estimated cost for one run whenever usage changes. */
    @Query("SELECT COALESCE(SUM(estimatedCostMicros), 0) FROM usage_record WHERE runId = :runId")
    fun observeCostMicrosForRun(runId: Long): Flow<Long>

    /** All calls recorded for one run, oldest first. */
    @Query("SELECT * FROM usage_record WHERE runId = :runId ORDER BY id")
    suspend fun getForRun(runId: Long): List<UsageRecordEntity>
}

/** Reads and writes the daily reports. */
@Dao
interface DailyReportDao {
    /** Inserts or replaces the report for its date. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: DailyReportEntity)

    /** The report for [date], or null. */
    @Query("SELECT * FROM daily_report WHERE date = :date")
    suspend fun get(date: LocalDate): DailyReportEntity?

    /** Emits the latest [limit] reports, newest first, for the history chart. */
    @Query("SELECT * FROM daily_report ORDER BY date DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DailyReportEntity>>
}
