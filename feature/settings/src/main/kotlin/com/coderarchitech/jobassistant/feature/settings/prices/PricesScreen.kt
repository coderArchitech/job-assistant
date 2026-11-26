package com.coderarchitech.jobassistant.feature.settings.prices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coderarchitech.jobassistant.ai.FallbackPricing
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.feature.settings.R
import com.coderarchitech.jobassistant.feature.settings.usage.Money

/** The prices destination: connects [PricesViewModel] to [PricesScreen]. */
@Composable
fun PricesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PricesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PricesScreen(
        state = state,
        actions =
            PricesActions(
                open = viewModel::open,
                add = viewModel::add,
                provider = viewModel::onProvider,
                text = viewModel::onText,
                save = viewModel::save,
                useSigned = viewModel::useSigned,
                close = viewModel::close,
            ),
        onBack = onBack,
        modifier = modifier,
    )
}

/** What the prices screen can ask of its ViewModel. */
data class PricesActions(
    val open: (PriceRow) -> Unit = {},
    val add: () -> Unit = {},
    val provider: (AiProvider) -> Unit = {},
    val text: (PriceField, String) -> Unit = { _, _ -> },
    val save: () -> Unit = {},
    val useSigned: () -> Unit = {},
    val close: () -> Unit = {},
)

/** The model prices the usage meter counts with, by provider, and the dialog that edits one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricesScreen(
    state: PricesUiState,
    actions: PricesActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.prices_title)) },
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
            if (state is PricesUiState.Ready) {
                ExtendedFloatingActionButton(
                    onClick = actions.add,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.prices_add)) },
                    modifier = Modifier.testTag("addPrice"),
                )
            }
        },
    ) { padding ->
        when (state) {
            PricesUiState.Loading ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            is PricesUiState.Ready -> PriceList(state.rows, actions, padding)
        }
    }
    (state as? PricesUiState.Ready)?.editor?.let { PriceEditorSheet(it, actions) }
}

@Composable
private fun PriceList(
    rows: List<PriceRow>,
    actions: PricesActions,
    padding: PaddingValues,
) {
    LazyColumn(
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                // Room for the floating button over the last row.
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag("prices"),
    ) {
        item {
            Text(
                stringResource(R.string.prices_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (rows.isEmpty()) {
            item { EmptyPrices() }
        }
        rows.groupBy { it.price.provider }.forEach { (provider, group) ->
            item(key = provider.name) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        providerName(provider),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                    ProviderCard(group, actions)
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    rows: List<PriceRow>,
    actions: PricesActions,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider()
            val price = row.price
            ListItem(
                headlineContent = {
                    Text(price.model, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.prices_summary,
                            Money.perMillion(price.inputMicrosPerMillion),
                            Money.perMillion(price.outputMicrosPerMillion),
                            Money.perMillion(price.cacheReadMicrosPerMillion),
                            Money.perMillion(price.cacheWriteMicrosPerMillion),
                        ),
                    )
                },
                trailingContent = { SourceLabel(row.edited) },
                // The whole row opens the editor.
                modifier =
                    Modifier
                        .clickable(
                            onClickLabel = stringResource(R.string.watchlist_edit),
                            role = Role.Button,
                        ) { actions.open(row) }
                        .testTag("price:${price.model}"),
            )
        }
    }
}

/** Whose price is in effect: the user's own, or the signed table's. */
@Composable
private fun SourceLabel(edited: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (edited) colors.secondaryContainer else colors.surfaceContainerHigh,
        contentColor = if (edited) colors.onSecondaryContainer else colors.onSurfaceVariant,
    ) {
        Text(
            stringResource(if (edited) R.string.prices_edited else R.string.prices_signed),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyPrices() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.prices_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.prices_empty_body,
                    Money.perMillion(FallbackPricing.PRICE.inputMicrosPerMillion),
                    Money.perMillion(FallbackPricing.PRICE.outputMicrosPerMillion),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun providerName(provider: AiProvider): String =
    stringResource(
        when (provider) {
            AiProvider.ANTHROPIC -> R.string.prices_provider_anthropic
            AiProvider.OPENAI -> R.string.prices_provider_openai
        },
    )
