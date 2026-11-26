package com.coderarchitech.jobassistant.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.coderarchitech.jobassistant.core.data.db.entity.CompanyWatchlistEntity
import com.coderarchitech.jobassistant.core.data.db.entity.ProfileEntity
import com.coderarchitech.jobassistant.core.data.db.entity.SearchPresetEntity
import com.coderarchitech.jobassistant.core.data.db.entity.SourceCursorEntity
import com.coderarchitech.jobassistant.core.model.AtsType
import kotlinx.coroutines.flow.Flow

/** Reads and writes the single profile row. */
@Dao
interface ProfileDao {
    /** The profile, or null before onboarding saves one. */
    @Query("SELECT * FROM profile WHERE id = ${ProfileEntity.SINGLE_ID}")
    suspend fun get(): ProfileEntity?

    /** Emits the profile whenever it changes. */
    @Query("SELECT * FROM profile WHERE id = ${ProfileEntity.SINGLE_ID}")
    fun observe(): Flow<ProfileEntity?>

    /** Inserts or replaces the profile; the entity's id is always [ProfileEntity.SINGLE_ID]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)
}

/** Reads and writes saved search presets. */
@Dao
interface SearchPresetDao {
    /** Inserts a preset and returns its new id. */
    @Insert
    suspend fun insert(preset: SearchPresetEntity): Long

    /** Updates a preset by id. */
    @Update
    suspend fun update(preset: SearchPresetEntity)

    /** Deletes a preset; its past runs stay in the run log. */
    @Delete
    suspend fun delete(preset: SearchPresetEntity)

    /** The preset with [id], or null. */
    @Query("SELECT * FROM search_preset WHERE id = :id")
    suspend fun getById(id: Long): SearchPresetEntity?

    /** Presets that run on a schedule. */
    @Query("SELECT * FROM search_preset WHERE enabled = 1 ORDER BY id")
    suspend fun getEnabled(): List<SearchPresetEntity>

    /** Emits every preset, oldest first, whenever the table changes. */
    @Query("SELECT * FROM search_preset ORDER BY id")
    fun observeAll(): Flow<List<SearchPresetEntity>>
}

/** Reads and writes the company watchlist. */
@Dao
interface CompanyWatchlistDao {
    /** Inserts a company and returns its id; a duplicate (ATS, board) fails with a constraint error. */
    @Insert
    suspend fun insert(entry: CompanyWatchlistEntity): Long

    /** Updates a company by id. */
    @Update
    suspend fun update(entry: CompanyWatchlistEntity)

    /** Deletes a company. */
    @Delete
    suspend fun delete(entry: CompanyWatchlistEntity)

    /** Companies on [atsType]'s board named [boardSlug], ignoring case, as the ATS does. */
    @Query("SELECT * FROM company_watchlist WHERE atsType = :atsType AND boardSlug = :boardSlug COLLATE NOCASE")
    suspend fun findByBoard(
        atsType: AtsType,
        boardSlug: String,
    ): List<CompanyWatchlistEntity>

    /** Companies whose boards are fetched on every run. */
    @Query("SELECT * FROM company_watchlist WHERE enabled = 1 ORDER BY name COLLATE NOCASE")
    suspend fun getEnabled(): List<CompanyWatchlistEntity>

    /** Emits every company, by name, whenever the table changes. */
    @Query("SELECT * FROM company_watchlist ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<CompanyWatchlistEntity>>
}

/** Reads and writes per-source discovery cursors. */
@Dao
interface SourceCursorDao {
    /** Inserts or replaces the cursor for a source. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SourceCursorEntity)

    /** The cursor for [sourceKey], or null before the source's first successful run. */
    @Query("SELECT * FROM source_cursor WHERE sourceKey = :sourceKey")
    suspend fun get(sourceKey: String): SourceCursorEntity?

    /** Every stored cursor. */
    @Query("SELECT * FROM source_cursor ORDER BY sourceKey")
    suspend fun getAll(): List<SourceCursorEntity>
}
