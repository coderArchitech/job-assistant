package com.coderarchitech.jobassistant.feature.settings.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coderarchitech.jobassistant.core.data.repository.UsageOverview
import com.coderarchitech.jobassistant.core.data.repository.UsageReport
import com.coderarchitech.jobassistant.core.model.Budget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/** Which budget the dialog edits. */
enum class BudgetField { MONTHLY, BROWSER_AGENT_DAILY }

/** The budget dialog: the text as typed, in dollars. */
data class BudgetEditor(
    val field: BudgetField,
    val text: String,
    val invalid: Boolean = false,
)

data class UsageUiState(
    val overview: UsageOverview? = null,
    val editor: BudgetEditor? = null,
)

/** The usage meter (task T4.13): AI spend this month against the budget, by day and by run. */
@HiltViewModel
class UsageViewModel
    @Inject
    constructor(
        private val report: UsageReport,
    ) : ViewModel() {
        private val editor = MutableStateFlow<BudgetEditor?>(null)

        val uiState: StateFlow<UsageUiState> =
            combine(report.observe(), editor) { overview, edit -> UsageUiState(overview, edit) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UsageUiState())

        fun editBudget(field: BudgetField) {
            val budget = uiState.value.overview?.budget ?: return
            val micros = if (field == BudgetField.MONTHLY) budget.monthlyCapMicros else budget.browserAgentDailyMicros
            editor.value = BudgetEditor(field, Money.plainDollars(micros))
        }

        fun onBudgetText(text: String) = editor.update { it?.copy(text = text, invalid = false) }

        fun closeEditor() {
            editor.value = null
        }

        fun saveBudget() {
            val edit = editor.value
            val budget = uiState.value.overview?.budget
            val micros = edit?.let { Money.parseDollars(it.text) }
            when {
                edit == null || budget == null -> Unit
                micros == null -> editor.update { it?.copy(invalid = true) }
                else -> store(edit.field, budget, micros)
            }
        }

        private fun store(
            field: BudgetField,
            budget: Budget,
            micros: Long,
        ) {
            val updated =
                when (field) {
                    BudgetField.MONTHLY -> budget.copy(monthlyCapMicros = micros)
                    BudgetField.BROWSER_AGENT_DAILY -> budget.copy(browserAgentDailyMicros = micros)
                }
            editor.value = null
            viewModelScope.launch { report.setBudget(updated) }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/** Dollar amounts in micro-US-dollars (1_000_000 = $1), as the budget and usage store them. */
object Money {
    private val MICROS_PER_DOLLAR = BigDecimal(1_000_000)
    private val MAX_DOLLARS = BigDecimal(10_000)
    private const val CENTS = 2

    /** Under ten cents, two decimals would hide most of the amount. */
    private const val SMALL_MICROS = 100_000L
    private const val SMALL_SCALE = 4

    /** `$3.42`; small amounts keep more digits (`$0.0123`) so a cheap run does not read as free. */
    fun format(micros: Long): String {
        val dollars = BigDecimal(micros).divide(MICROS_PER_DOLLAR)
        val scale = if (micros in 1 until SMALL_MICROS) SMALL_SCALE else CENTS
        return "$" + dollars.setScale(scale, RoundingMode.HALF_UP).toPlainString()
    }

    /** `10` or `2.5`, for editing. */
    fun plainDollars(micros: Long): String =
        BigDecimal(micros).divide(MICROS_PER_DOLLAR).stripTrailingZeros().toPlainString()

    /** Dollars as typed (`10`, `$2.50`), to micros; null for anything else, a negative or more than $10,000. */
    fun parseDollars(text: String): Long? =
        text
            .trim()
            .removePrefix("$")
            .trim()
            .toBigDecimalOrNull()
            ?.takeIf { it.signum() >= 0 && it <= MAX_DOLLARS && it.scale() <= CENTS }
            ?.multiply(MICROS_PER_DOLLAR)
            ?.toLong()

    /** `12.3k` rather than `12345`; exact token counts do not help a reader here. */
    fun tokens(count: Long): String =
        when {
            count >= MILLION -> "%.1fM".format(count / MILLION.toDouble())
            count >= THOUSAND -> "%.1fk".format(count / THOUSAND.toDouble())
            else -> count.toString()
        }

    private const val THOUSAND = 1_000L
    private const val MILLION = 1_000_000L
}
