package com.coderarchitech.jobassistant.feature.settings.watchlist

import com.coderarchitech.jobassistant.agent.watchlist.BoardChecker
import com.coderarchitech.jobassistant.core.data.repository.WatchlistRepository
import com.coderarchitech.jobassistant.core.data.repository.WatchlistSave
import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.CompanyWatchlistEntry
import com.coderarchitech.jobassistant.sources.BoardCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A watchlist kept in memory, with the same rule as the table: one row per (ATS, board). */
class FakeWatchlist(
    initial: List<CompanyWatchlistEntry> = emptyList(),
) : WatchlistRepository {
    val rows = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeAll(): Flow<List<CompanyWatchlistEntry>> = rows

    override suspend fun save(entry: CompanyWatchlistEntry): WatchlistSave {
        val clean = entry.copy(name = entry.name.trim(), boardSlug = entry.boardSlug.trim())
        val clash =
            rows.value.any {
                it.id != clean.id &&
                    it.atsType == clean.atsType &&
                    it.boardSlug.equals(clean.boardSlug, ignoreCase = true)
            }
        if (clash) return WatchlistSave.Duplicate
        val saved = if (clean.id == 0L) clean.copy(id = nextId++) else clean
        rows.value = (rows.value.filter { it.id != saved.id } + saved).sortedBy { it.name.lowercase() }
        return WatchlistSave.Saved(saved.id)
    }

    override suspend fun delete(entry: CompanyWatchlistEntry) {
        rows.value = rows.value.filter { it.id != entry.id }
    }
}

/** Answers checks from a table; a board missing from it is not found. [gate] holds every check until completed. */
class FakeChecker(
    private val boards: Map<Pair<AtsType, String>, BoardCheck> = emptyMap(),
) : BoardChecker {
    var gate: CompletableDeferred<Unit>? = null
    val asked = mutableListOf<Pair<AtsType, String>>()

    override suspend fun check(
        ats: AtsType,
        board: String,
    ): BoardCheck {
        asked += ats to board
        gate?.await()
        return boards[ats to board] ?: BoardCheck.NotFound
    }
}
