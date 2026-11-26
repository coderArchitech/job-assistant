package com.coderarchitech.jobassistant.feature.settings.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.CompanyWatchlistEntry
import com.coderarchitech.jobassistant.feature.settings.R

/** The watchlist destination: connects [WatchlistViewModel] to [WatchlistScreen]. */
@Composable
fun WatchlistRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WatchlistViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WatchlistScreen(state = state, actions = viewModel.actions(), onBack = onBack, modifier = modifier)
}

/** Everything the screen can ask for, so it stays stateless and previewable. */
data class WatchlistActions(
    val add: () -> Unit = {},
    val edit: (CompanyWatchlistEntry) -> Unit = {},
    val check: (CompanyWatchlistEntry) -> Unit = {},
    val setEnabled: (CompanyWatchlistEntry, Boolean) -> Unit = { _, _ -> },
    val remove: (CompanyWatchlistEntry) -> Unit = {},
    val undoRemove: () -> Unit = {},
    val messageShown: () -> Unit = {},
    val editor: EditorActions = EditorActions(),
)

data class EditorActions(
    val name: (String) -> Unit = {},
    val ats: (AtsType) -> Unit = {},
    val board: (String) -> Unit = {},
    val check: () -> Unit = {},
    val save: () -> Unit = {},
    val close: () -> Unit = {},
)

private fun WatchlistViewModel.actions() =
    WatchlistActions(
        add = ::startAdding,
        edit = ::startEditing,
        check = ::check,
        setEnabled = ::setEnabled,
        remove = ::remove,
        undoRemove = ::undoRemove,
        messageShown = ::messageShown,
        editor =
            EditorActions(
                name = ::onNameChange,
                ats = ::onAtsChange,
                board = ::onBoardChange,
                check = ::checkEditorBoard,
                save = ::save,
                close = ::closeEditor,
            ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    state: WatchlistUiState,
    actions: WatchlistActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbar, actions)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.watchlist_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.watchlist_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.companies.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = actions.add,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.watchlist_add)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            state.companies.isEmpty() -> EmptyWatchlist(onAdd = actions.add, modifier = Modifier.padding(padding))
            else -> CompanyList(state.companies, actions, PaddingValues(top = padding.calculateTopPadding()))
        }
    }
    state.editor?.let { CompanyEditor(it, actions.editor) }
}

@Composable
private fun MessageEffect(
    message: WatchlistMessage?,
    snackbar: SnackbarHostState,
    actions: WatchlistActions,
) {
    val removed = message as? WatchlistMessage.Removed
    val text =
        when (message) {
            is WatchlistMessage.Removed -> stringResource(R.string.watchlist_removed, message.name)
            is WatchlistMessage.Saved -> stringResource(R.string.watchlist_saved, message.name)
            null -> null
        }
    val undo = stringResource(R.string.watchlist_undo)
    LaunchedEffect(message) {
        if (text == null) return@LaunchedEffect
        actions.messageShown()
        val result =
            snackbar.showSnackbar(
                text,
                actionLabel =
                    if (removed !=
                        null
                    ) {
                        undo
                    } else {
                        null
                    },
                withDismissAction = removed == null,
            )
        if (result == SnackbarResult.ActionPerformed) actions.undoRemove()
    }
}

@Composable
private fun EmptyWatchlist(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.watchlist_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.watchlist_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.size(24.dp))
        FilledTonalButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.watchlist_add))
        }
    }
}

@Composable
private fun CompanyList(
    companies: List<CompanyRow>,
    actions: WatchlistActions,
    padding: PaddingValues,
) {
    LazyColumn(
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = 96.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().testTag("watchlist"),
    ) {
        item {
            Text(
                stringResource(R.string.watchlist_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        items(companies, key = { it.entry.id }) { row -> CompanyCard(row, actions) }
    }
}

@Composable
private fun CompanyCard(
    row: CompanyRow,
    actions: WatchlistActions,
) {
    val entry = row.entry
    OutlinedCard(onClick = { actions.edit(entry) }, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Monogram(entry.name, dimmed = !entry.enabled) },
            headlineContent = { Text(entry.name, style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(
                            R.string.watchlist_board_summary,
                            stringResource(entry.atsType.label()),
                            entry.boardSlug,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    StatusLine(row.check, paused = !entry.enabled)
                }
            },
            trailingContent = { RowMenu(entry, actions) },
        )
    }
}

/** The company's first letter in a tonal circle, so rows are easy to scan. */
@Composable
private fun Monogram(
    name: String,
    dimmed: Boolean,
) {
    Surface(
        shape = CircleShape,
        color = if (dimmed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = with(MaterialTheme.colorScheme) { if (dimmed) onSurfaceVariant else onSecondaryContainer },
            )
        }
    }
}

@Composable
private fun StatusLine(
    check: CheckState,
    paused: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val (text, color) =
        when {
            paused -> stringResource(R.string.watchlist_paused) to colors.onSurfaceVariant
            check is CheckState.Found ->
                pluralStringResource(R.plurals.watchlist_status_found, check.postings, check.postings) to
                    colors.primary
            check is CheckState.NotFound -> stringResource(R.string.watchlist_status_not_found) to colors.error
            check is CheckState.Failed -> stringResource(R.string.watchlist_status_failed) to colors.error
            check is CheckState.Checking ->
                stringResource(R.string.watchlist_status_checking) to
                    colors.onSurfaceVariant
            else -> stringResource(R.string.watchlist_status_not_checked) to colors.onSurfaceVariant
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (check is CheckState.Checking && !paused) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
        } else {
            Surface(shape = CircleShape, color = color, modifier = Modifier.size(8.dp)) {}
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun RowMenu(
    entry: CompanyWatchlistEntry,
    actions: WatchlistActions,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.watchlist_more, entry.name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(action: () -> Unit) {
                open = false
                action()
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.watchlist_check)) },
                onClick = { pick { actions.check(entry) } },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.watchlist_edit)) },
                onClick = { pick { actions.edit(entry) } },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(if (entry.enabled) R.string.watchlist_pause else R.string.watchlist_resume),
                    )
                },
                onClick = { pick { actions.setEnabled(entry, !entry.enabled) } },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.watchlist_remove), color = MaterialTheme.colorScheme.error) },
                onClick = { pick { actions.remove(entry) } },
            )
        }
    }
}
