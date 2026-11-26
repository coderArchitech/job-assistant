package com.coderarchitech.jobassistant.feature.settings.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.coderarchitech.jobassistant.core.data.repository.UsageOverview
import com.coderarchitech.jobassistant.feature.settings.R
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** This month against the budget, today's spend, and the two budgets with a way to change each. */
@Composable
internal fun MonthCard(
    overview: UsageOverview,
    actions: UsageActions,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SpendSummary(overview)
            Text(
                pluralStringResource(
                    R.plurals.usage_today,
                    overview.today.calls,
                    Money.format(overview.today.costMicros),
                    overview.today.calls,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            BudgetLine(stringResource(R.string.usage_monthly_budget), Money.format(overview.budget.monthlyCapMicros)) {
                actions.editBudget(BudgetField.MONTHLY)
            }
            BudgetLine(
                stringResource(R.string.usage_agent_budget),
                agentBudget(overview.budget.browserAgentDailyMicros),
            ) {
                actions.editBudget(BudgetField.BROWSER_AGENT_DAILY)
            }
        }
    }
}

/** This month's spend against the cap, and what happens at the cap. */
@Composable
private fun SpendSummary(overview: UsageOverview) {
    val cap = overview.budget.monthlyCapMicros
    val spent = overview.thisMonth.costMicros
    val colors = MaterialTheme.colorScheme
    Text(
        stringResource(R.string.usage_this_month),
        style = MaterialTheme.typography.labelLarge,
        color = colors.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            Money.format(spent),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.testTag("monthSpend"),
        )
        Text(
            stringResource(R.string.usage_of_cap, Money.format(cap)),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    LinearProgressIndicator(
        progress = { if (cap == 0L) 1f else (spent.toFloat() / cap).coerceIn(0f, 1f) },
        color = if (overview.capReached) colors.error else colors.primary,
        modifier = Modifier.fillMaxWidth(),
    )
    val status =
        if (overview.capReached) {
            stringResource(
                R.string.usage_cap_reached,
                overview.resetsOn.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            )
        } else {
            stringResource(R.string.usage_cap_explained, Money.format(cap))
        }
    Text(
        status,
        style = MaterialTheme.typography.bodyMedium,
        color = if (overview.capReached) colors.error else colors.onSurfaceVariant,
        modifier = Modifier.testTag("capStatus"),
    )
}

@Composable
private fun agentBudget(micros: Long): String =
    if (micros ==
        0L
    ) {
        stringResource(R.string.usage_agent_off)
    } else {
        stringResource(R.string.usage_per_day, Money.format(micros))
    }

@Composable
private fun BudgetLine(
    label: String,
    value: String,
    onEdit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.usage_change)) }
    }
}
