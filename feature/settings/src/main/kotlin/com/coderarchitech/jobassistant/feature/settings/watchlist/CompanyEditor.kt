package com.coderarchitech.jobassistant.feature.settings.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.feature.settings.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanyEditor(
    editor: EditorState,
    actions: EditorActions,
) {
    ModalBottomSheet(
        onDismissRequest = actions.close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CompanyForm(editor, actions)
    }
}

/** The add or edit form; separate from the sheet so it can be previewed and tested on its own. */
@Composable
internal fun CompanyForm(
    editor: EditorState,
    actions: EditorActions,
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
        Text(
            stringResource(if (editor.id == 0L) R.string.editor_add_title else R.string.editor_edit_title),
            style = MaterialTheme.typography.titleLarge,
        )
        OutlinedTextField(
            value = editor.name,
            onValueChange = actions.name,
            label = { Text(stringResource(R.string.editor_name)) },
            singleLine = true,
            isError = editor.showErrors && editor.nameMissing,
            supportingText = {
                if (editor.showErrors &&
                    editor.nameMissing
                ) {
                    Text(stringResource(R.string.editor_name_missing))
                }
            },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
            modifier = Modifier.fillMaxWidth().testTag("name"),
        )
        AtsPicker(editor.ats, actions.ats)
        BoardField(editor, actions)
        CheckRow(editor, actions.check)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = actions.close) { Text(stringResource(R.string.editor_cancel)) }
            Button(
                onClick = actions.save,
                modifier = Modifier.testTag("save"),
            ) { Text(stringResource(R.string.editor_save)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AtsPicker(
    selected: AtsType,
    onSelect: (AtsType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.editor_ats), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AtsType.entries.forEachIndexed { i, ats ->
                SegmentedButton(
                    selected = ats == selected,
                    onClick = { onSelect(ats) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = AtsType.entries.size),
                ) { Text(stringResource(ats.label())) }
            }
        }
    }
}

@Composable
private fun BoardField(
    editor: EditorState,
    actions: EditorActions,
) {
    val error =
        when {
            editor.duplicate -> stringResource(R.string.editor_duplicate)
            editor.showErrors && editor.boardInvalid ->
                stringResource(
                    if (editor.ats ==
                        AtsType.ASHBY
                    ) {
                        R.string.editor_board_invalid_dotted
                    } else {
                        R.string.editor_board_invalid_plain
                    },
                )
            else -> null
        }
    OutlinedTextField(
        value = editor.board,
        onValueChange = actions.board,
        label = { Text(stringResource(R.string.editor_board)) },
        singleLine = true,
        isError = error != null,
        supportingText = { Text(error ?: editor.publicPage ?: stringResource(R.string.editor_board_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag("board"),
    )
}

@Composable
private fun CheckRow(
    editor: EditorState,
    onCheck: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onCheck,
            enabled = editor.check != CheckState.Checking,
            modifier = Modifier.testTag("check"),
        ) {
            Text(stringResource(R.string.editor_check))
        }
        val detail =
            when (val check = editor.check) {
                is CheckState.Found ->
                    pluralStringResource(R.plurals.watchlist_status_found, check.postings, check.postings) + ". " +
                        stringResource(R.string.editor_found_detail)
                CheckState.NotFound -> stringResource(R.string.editor_not_found_detail)
                is CheckState.Failed -> stringResource(R.string.watchlist_status_failed) + ": " + check.reason
                CheckState.Checking -> stringResource(R.string.watchlist_status_checking)
                CheckState.NotChecked -> null
            }
        detail?.let {
            val color =
                when (editor.check) {
                    is CheckState.Found -> MaterialTheme.colorScheme.primary
                    CheckState.NotFound, is CheckState.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.weight(1f).testTag("checkResult"),
            )
        }
    }
}

internal fun AtsType.label(): Int =
    when (this) {
        AtsType.GREENHOUSE -> R.string.ats_greenhouse
        AtsType.LEVER -> R.string.ats_lever
        AtsType.ASHBY -> R.string.ats_ashby
    }
