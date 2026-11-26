package com.coderarchitech.jobassistant.feature.settings.prices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.feature.settings.R

/** Edits one model's price, or adds a model the table does not list, in a bottom sheet like the watchlist's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PriceEditorSheet(
    editor: PriceEditor,
    actions: PricesActions,
) {
    ModalBottomSheet(
        onDismissRequest = actions.close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        PriceForm(editor, actions)
    }
}

/** The price form; separate from the sheet so it can be tested and captured on its own. */
@Composable
internal fun PriceForm(
    editor: PriceEditor,
    actions: PricesActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (editor.isNew) {
            Text(stringResource(R.string.prices_add), style = MaterialTheme.typography.titleLarge)
            NewModelFields(editor, actions)
        } else {
            Column {
                Text(editor.model, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                Text(
                    providerName(editor.provider),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PricePair(
            editor,
            actions,
            PriceField.INPUT to R.string.prices_input,
            PriceField.OUTPUT to R.string.prices_output,
        )
        PricePair(
            editor,
            actions,
            PriceField.CACHE_READ to R.string.prices_cache_read,
            PriceField.CACHE_WRITE to R.string.prices_cache_write,
        )
        val colors = MaterialTheme.colorScheme
        Text(
            problemText(editor.problem) ?: stringResource(R.string.prices_per_million),
            style = MaterialTheme.typography.bodySmall,
            color = if (editor.problem != null) colors.error else colors.onSurfaceVariant,
            modifier = Modifier.testTag("priceMessage"),
        )
        FormButtons(editor, actions)
    }
}

/** For a new entry: which provider, and the model id. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewModelFields(
    editor: PriceEditor,
    actions: PricesActions,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        AiProvider.entries.forEachIndexed { i, provider ->
            SegmentedButton(
                selected = editor.provider == provider,
                onClick = { actions.provider(provider) },
                shape = SegmentedButtonDefaults.itemShape(i, AiProvider.entries.size),
            ) { Text(providerName(provider)) }
        }
    }
    OutlinedTextField(
        value = editor.model,
        onValueChange = { actions.text(PriceField.MODEL, it) },
        label = { Text(stringResource(R.string.prices_model)) },
        singleLine = true,
        isError = editor.problem == PriceProblem.BAD_MODEL || editor.problem == PriceProblem.DUPLICATE,
        modifier = Modifier.fillMaxWidth().testTag("priceModel"),
    )
}

/** Two price fields side by side. */
@Composable
private fun PricePair(
    editor: PriceEditor,
    actions: PricesActions,
    first: Pair<PriceField, Int>,
    second: Pair<PriceField, Int>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((field, label) in listOf(first, second)) {
            OutlinedTextField(
                value = editor.textOf(field),
                onValueChange = { actions.text(field, it) },
                label = { Text(stringResource(label)) },
                prefix = { Text("$") },
                singleLine = true,
                isError = editor.problem == PriceProblem.BAD_PRICE,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("price:$field"),
            )
        }
    }
}

/** Save and Cancel, and "Use signed price" (or "Remove" for a model only the user prices) for the user's own. */
@Composable
private fun FormButtons(
    editor: PriceEditor,
    actions: PricesActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        if (editor.edited && !editor.isNew) {
            val label = if (editor.signed != null) R.string.prices_use_signed else R.string.prices_remove
            TextButton(onClick = actions.useSigned, modifier = Modifier.testTag("useSigned")) {
                Text(stringResource(label))
            }
        }
        TextButton(onClick = actions.close) { Text(stringResource(R.string.editor_cancel)) }
        Button(onClick = actions.save, modifier = Modifier.testTag("savePrice")) {
            Text(stringResource(R.string.editor_save))
        }
    }
}

private fun PriceEditor.textOf(field: PriceField): String =
    when (field) {
        PriceField.MODEL -> model
        PriceField.INPUT -> input
        PriceField.OUTPUT -> output
        PriceField.CACHE_READ -> cacheRead
        PriceField.CACHE_WRITE -> cacheWrite
    }

@Composable
private fun problemText(problem: PriceProblem?): String? =
    when (problem) {
        null -> null
        PriceProblem.BAD_MODEL -> stringResource(R.string.prices_bad_model)
        PriceProblem.BAD_PRICE -> stringResource(R.string.prices_bad_price)
        PriceProblem.DUPLICATE -> stringResource(R.string.prices_duplicate)
        PriceProblem.TOO_MANY -> stringResource(R.string.prices_too_many)
        PriceProblem.SAVE_FAILED -> stringResource(R.string.prices_save_failed)
    }
