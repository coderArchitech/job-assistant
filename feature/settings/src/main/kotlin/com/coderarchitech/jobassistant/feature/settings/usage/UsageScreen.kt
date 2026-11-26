package com.coderarchitech.jobassistant.feature.settings.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coderarchitech.jobassistant.core.data.repository.UsageOverview
import com.coderarchitech.jobassistant.core.model.RunUsage
import com.coderarchitech.jobassistant.core.model.UsageTotal
import com.coderarchitech.jobassistant.feature.settings.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The usage destination: connects [UsageViewModel] to [UsageScreen]. */
@Composable
fun UsageRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UsageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UsageScreen(
        state = state,
        actions =
            UsageActions(
                editBudget = viewModel::editBudget,
                budgetText = viewModel::onBudgetText,
                saveBudget = viewModel::saveBudget,
                closeEditor = viewModel::closeEditor,
            ),
        onBack = onBack,
        modifier = modifier,
    )
}

data class UsageActions(
    val editBudget: (BudgetField) -> Unit = {},
    val budgetText: (String) -> Unit = {},
    val saveBudget: () -> Unit = {},
    val closeEditor: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    state: UsageUiState,
    actions: UsageActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usage_title)) },
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
    ) { padding ->
        when (state) {
            UsageUiState.Loading ->
                Box(
                    Modifier.padding(padding).fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            is UsageUiState.Ready -> UsageContent(state.overview, actions, zone, padding)
        }
    }
    (state as? UsageUiState.Ready)?.editor?.let { BudgetDialog(it, actions) }
}

@Composable
private fun UsageContent(
    overview: UsageOverview,
    actions: UsageActions,
    zone: ZoneId,
    padding: PaddingValues,
) {
    LazyColumn(
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag("usage"),
    ) {
        item { MonthCard(overview, actions) }
        item { SectionTitle(stringResource(R.string.usage_runs)) }
        if (overview.runs.isEmpty()) {
            item { Hint(stringResource(R.string.usage_no_runs)) }
        } else {
            item { RunsCard(overview.runs, zone) }
        }
        item { SectionTitle(stringResource(R.string.usage_days)) }
        if (overview.days.isEmpty()) {
            item { Hint(stringResource(R.string.usage_no_days)) }
        } else {
            items(overview.days, key = { it.first.toString() }) { (day, total) -> DayRow(day, total) }
        }
    }
}

@Composable
private fun RunsCard(
    runs: List<RunUsage>,
    zone: ZoneId,
) {
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        runs.forEachIndexed { i, run ->
            if (i > 0) HorizontalDivider()
            ListItem(
                headlineContent = { Text(format.format(run.startedAt)) },
                supportingContent = { Text(callsAndTokens(run.total)) },
                trailingContent = {
                    Text(
                        Money.format(run.total.costMicros),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("runCost"),
                    )
                },
            )
        }
    }
}

@Composable
private fun DayRow(
    day: LocalDate,
    total: UsageTotal,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                callsAndTokens(total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(Money.format(total.costMicros), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun callsAndTokens(total: UsageTotal): String =
    pluralStringResource(
        R.plurals.usage_calls_tokens,
        total.calls,
        total.calls,
        Money.tokens(
            total.inputTokens + total.outputTokens,
        ),
    )

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 8.dp))

@Composable
private fun Hint(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )

@Composable
private fun BudgetDialog(
    editor: BudgetEditor,
    actions: UsageActions,
) {
    AlertDialog(
        onDismissRequest = actions.closeEditor,
        title = {
            Text(
                stringResource(
                    if (editor.field ==
                        BudgetField.MONTHLY
                    ) {
                        R.string.usage_monthly_budget
                    } else {
                        R.string.usage_agent_budget
                    },
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = editor.text,
                onValueChange = actions.budgetText,
                prefix = { Text("$") },
                singleLine = true,
                isError = editor.invalid || editor.saveFailed,
                supportingText = {
                    Text(
                        stringResource(
                            when {
                                editor.saveFailed -> R.string.usage_budget_save_failed
                                editor.invalid -> R.string.usage_budget_invalid
                                editor.field == BudgetField.MONTHLY -> R.string.usage_budget_monthly_hint
                                else -> R.string.usage_budget_agent_hint
                            },
                        ),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.testTag("budgetField"),
            )
        },
        confirmButton = { TextButton(onClick = actions.saveBudget) { Text(stringResource(R.string.editor_save)) } },
        dismissButton = { TextButton(onClick = actions.closeEditor) { Text(stringResource(R.string.editor_cancel)) } },
    )
}
