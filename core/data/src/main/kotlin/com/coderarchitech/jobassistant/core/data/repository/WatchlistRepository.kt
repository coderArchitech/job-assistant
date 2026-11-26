package com.coderarchitech.jobassistant.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.coderarchitech.jobassistant.core.data.db.dao.CompanyWatchlistDao
import com.coderarchitech.jobassistant.core.data.db.entity.toEntity
import com.coderarchitech.jobassistant.core.data.db.entity.toModel
import com.coderarchitech.jobassistant.core.model.CompanyWatchlistEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** What saving a watchlist company did. */
sealed interface WatchlistSave {
    /** Saved; [id] is the company's row. */
    data class Saved(
        val id: Long,
    ) : WatchlistSave

    /** Another company already uses this ATS and board, so it would be fetched twice. */
    data object Duplicate : WatchlistSave
}

/** The companies whose public job boards every run checks (PRD FR3, task T2.09). */
interface WatchlistRepository {
    /** Every company, by name, updated whenever the list changes. */
    fun observeAll(): Flow<List<CompanyWatchlistEntry>>

    /**
     * Adds [entry] when its id is 0, or updates the company with that id. [WatchlistSave.Duplicate] when another
     * company already has the same ATS and board, ignoring case, since board names are not case sensitive.
     */
    suspend fun save(entry: CompanyWatchlistEntry): WatchlistSave

    /** Removes the company; its board is no longer checked. */
    suspend fun delete(entry: CompanyWatchlistEntry)
}

/** The [WatchlistRepository] over the `company_watchlist` table. Names and boards are stored trimmed. */
internal class RoomWatchlistRepository
    @Inject
    constructor(
        private val dao: CompanyWatchlistDao,
    ) : WatchlistRepository {
        override fun observeAll(): Flow<List<CompanyWatchlistEntry>> =
            dao.observeAll().map { rows -> rows.map { it.toModel() } }

        @Suppress("SwallowedException") // The only constraint that can fail is the unique board; it is the result.
        override suspend fun save(entry: CompanyWatchlistEntry): WatchlistSave {
            val clean = entry.copy(name = entry.name.trim(), boardSlug = entry.boardSlug.trim())
            // The unique index is case sensitive and board names are not, so `Stripe` must find `stripe`.
            if (dao
                    .findByBoard(
                        clean.atsType,
                        clean.boardSlug,
                    ).any { it.id != clean.id }
            ) {
                return WatchlistSave.Duplicate
            }
            return try {
                if (clean.id == 0L) {
                    WatchlistSave.Saved(dao.insert(clean.toEntity()))
                } else {
                    dao.update(clean.toEntity())
                    WatchlistSave.Saved(clean.id)
                }
            } catch (e: SQLiteConstraintException) {
                // The (ATS, board) pair is unique; a second row would fetch the same board twice.
                WatchlistSave.Duplicate
            }
        }

        override suspend fun delete(entry: CompanyWatchlistEntry) = dao.delete(entry.toEntity())
    }
