package com.coderarchitech.jobassistant.feature.settings.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coderarchitech.jobassistant.agent.watchlist.BoardChecker
import com.coderarchitech.jobassistant.core.data.repository.WatchlistRepository
import com.coderarchitech.jobassistant.core.data.repository.WatchlistSave
import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.CompanyWatchlistEntry
import com.coderarchitech.jobassistant.sources.BoardCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a board check stands. */
sealed interface CheckState {
    data object NotChecked : CheckState

    data object Checking : CheckState

    data class Found(
        val postings: Int,
    ) : CheckState

    data object NotFound : CheckState

    data class Failed(
        val reason: String,
    ) : CheckState
}

/** One company in the list, with the result of its last check in this session. */
data class CompanyRow(
    val entry: CompanyWatchlistEntry,
    val check: CheckState,
)

/** The add or edit form. [id] is 0 for a new company. */
data class EditorState(
    val id: Long = 0,
    val name: String = "",
    val ats: AtsType = AtsType.GREENHOUSE,
    val board: String = "",
    val enabled: Boolean = true,
    val check: CheckState = CheckState.NotChecked,
    /** Set once the user tries to save, so fields are not marked wrong while still being typed. */
    val showErrors: Boolean = false,
    val duplicate: Boolean = false,
) {
    val nameMissing: Boolean get() = name.isBlank()
    val boardInvalid: Boolean get() = !BoardChecker.isWellFormed(ats, board)
    val publicPage: String? get() = if (boardInvalid) null else BoardChecker.publicPage(ats, board)
}

/** A one-time message for the snackbar. */
sealed interface WatchlistMessage {
    data class Removed(
        val name: String,
    ) : WatchlistMessage

    data class Saved(
        val name: String,
    ) : WatchlistMessage
}

data class WatchlistUiState(
    val loading: Boolean = true,
    val companies: List<CompanyRow> = emptyList(),
    val editor: EditorState? = null,
    val message: WatchlistMessage? = null,
)

/**
 * The company watchlist (task T2.09): companies whose Greenhouse, Lever or Ashby boards every run checks.
 * Board checks are kept for this screen only; they say whether the board answered when the user asked.
 */
@Suppress("TooManyFunctions") // One public function per thing the user can do on the screen.
@HiltViewModel
class WatchlistViewModel
    @Inject
    constructor(
        private val repository: WatchlistRepository,
        private val checker: BoardChecker,
    ) : ViewModel() {
        private val checks = MutableStateFlow<Map<Long, CheckState>>(emptyMap())
        private val local = MutableStateFlow(LocalState())
        private var editorCheck: Job? = null
        private var lastRemoved: CompanyWatchlistEntry? = null

        private data class LocalState(
            val editor: EditorState? = null,
            val message: WatchlistMessage? = null,
        )

        val uiState: StateFlow<WatchlistUiState> =
            combine(repository.observeAll(), checks, local) { entries, checkMap, state ->
                WatchlistUiState(
                    loading = false,
                    companies = entries.map { CompanyRow(it, checkMap[it.id] ?: CheckState.NotChecked) },
                    editor = state.editor,
                    message = state.message,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WatchlistUiState())

        fun startAdding() = local.update { it.copy(editor = EditorState()) }

        fun startEditing(entry: CompanyWatchlistEntry) =
            local.update {
                it.copy(
                    editor =
                        EditorState(
                            id = entry.id,
                            name = entry.name,
                            ats = entry.atsType,
                            board = entry.boardSlug,
                            enabled = entry.enabled,
                            check = checks.value[entry.id] ?: CheckState.NotChecked,
                        ),
                )
            }

        fun closeEditor() {
            editorCheck?.cancel()
            local.update { it.copy(editor = null) }
        }

        fun onNameChange(name: String) = editEditor { it.copy(name = name, duplicate = false) }

        // A different ATS or board is a different board, so an earlier check no longer applies.
        fun onAtsChange(ats: AtsType) =
            editEditor { it.copy(ats = ats, check = CheckState.NotChecked, duplicate = false) }

        fun onBoardChange(board: String) =
            editEditor { it.copy(board = board, check = CheckState.NotChecked, duplicate = false) }

        fun checkEditorBoard() {
            val editor = local.value.editor ?: return
            if (editor.boardInvalid) {
                editEditor { it.copy(showErrors = true) }
                return
            }
            editorCheck?.cancel()
            editEditor { it.copy(check = CheckState.Checking) }
            editorCheck =
                viewModelScope.launch {
                    val result = checker.check(editor.ats, editor.board).toState()
                    // Apply it only if the form still shows the board that was checked.
                    editEditor { current ->
                        if (current.ats == editor.ats &&
                            current.board == editor.board
                        ) {
                            current.copy(check = result)
                        } else {
                            current
                        }
                    }
                }
        }

        fun save() {
            val editor = local.value.editor ?: return
            if (editor.nameMissing || editor.boardInvalid) {
                editEditor { it.copy(showErrors = true) }
                return
            }
            viewModelScope.launch {
                val entry = CompanyWatchlistEntry(editor.id, editor.name, editor.ats, editor.board, editor.enabled)
                when (val saved = repository.save(entry)) {
                    WatchlistSave.Duplicate -> editEditor { it.copy(duplicate = true, showErrors = true) }
                    is WatchlistSave.Saved -> {
                        editorCheck?.cancel()
                        if (editor.check != CheckState.NotChecked && editor.check != CheckState.Checking) {
                            checks.update { it + (saved.id to editor.check) }
                        }
                        local.update { it.copy(editor = null, message = WatchlistMessage.Saved(editor.name.trim())) }
                    }
                }
            }
        }

        fun check(entry: CompanyWatchlistEntry) {
            if (checks.value[entry.id] == CheckState.Checking) return
            checks.update { it + (entry.id to CheckState.Checking) }
            viewModelScope.launch {
                val result = checker.check(entry.atsType, entry.boardSlug).toState()
                checks.update { it + (entry.id to result) }
            }
        }

        fun setEnabled(
            entry: CompanyWatchlistEntry,
            enabled: Boolean,
        ) {
            viewModelScope.launch { repository.save(entry.copy(enabled = enabled)) }
        }

        fun remove(entry: CompanyWatchlistEntry) {
            viewModelScope.launch {
                repository.delete(entry)
                lastRemoved = entry
                checks.update { it - entry.id }
                local.update { it.copy(message = WatchlistMessage.Removed(entry.name)) }
            }
        }

        /** Puts the company removed last back, as a new row. */
        fun undoRemove() {
            val entry = lastRemoved ?: return
            lastRemoved = null
            viewModelScope.launch { repository.save(entry.copy(id = 0)) }
        }

        fun messageShown() = local.update { it.copy(message = null) }

        private fun editEditor(change: (EditorState) -> EditorState) =
            local.update { state -> state.editor?.let { state.copy(editor = change(it)) } ?: state }

        private fun BoardCheck.toState(): CheckState =
            when (this) {
                is BoardCheck.Valid -> CheckState.Found(jobCount)
                BoardCheck.NotFound -> CheckState.NotFound
                is BoardCheck.Failed -> CheckState.Failed(message)
            }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
