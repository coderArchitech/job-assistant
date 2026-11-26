package com.coderarchitech.jobassistant.feature.settings.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coderarchitech.jobassistant.core.data.repository.UsageOverview
import com.coderarchitech.jobassistant.core.data.repository.UsageReport
import com.coderarchitech.jobassistant.core.model.Budget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    /** The last save could not be written; try again. */
    val saveFailed: Boolean = false,
)

/** The usage screen: loading until the first overview arrives, then the overview and any open dialog. */
sealed interface UsageUiState {
    data object Loading : UsageUiState

    data class Ready(
        val overview: UsageOverview,
        val editor: BudgetEditor? = null,
    ) : UsageUiState
}

/** The usage meter (task T4.13): AI spend this month against the budget, by day and by run. */
@HiltViewModel
class UsageViewModel
    @Inject
    constructor(
        private val report: UsageReport,
    ) : ViewModel() {
        private val editor = MutableStateFlow<BudgetEditor?>(null)

        val uiState: StateFlow<UsageUiState> =
            combine(report.observe(), editor) { overview, edit -> UsageUiState.Ready(overview, edit) as UsageUiState }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UsageUiState.Loading)

        fun editBudget(field: BudgetField) {
            val budget = currentBudget() ?: return
            val micros = if (field == BudgetField.MONTHLY) budget.monthlyCapMicros else budget.browserAgentDailyMicros
            editor.value = BudgetEditor(field, Money.plainDollars(micros))
        }

        fun onBudgetText(text: String) = editor.update { it?.copy(text = text, invalid = false, saveFailed = false) }

        fun closeEditor() {
            editor.value = null
        }

        fun saveBudget() {
            val edit = editor.value
            val budget = currentBudget()
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
            viewModelScope.launch {
                // A failed write reopens the dialog with what was typed, rather than losing it or crashing.
                runCatching { report.setBudget(updated) }.onFailure { e ->
                    if (e is CancellationException) throw e
                    editor.value = BudgetEditor(field, Money.plainDollars(micros), saveFailed = true)
                }
            }
        }

        private fun currentBudget(): Budget? = (uiState.value as? UsageUiState.Ready)?.overview?.budget

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
            // A decimal keypad types a comma in many locales; a single comma is the decimal point here.
            .let { if (it.count { c -> c == ',' } == 1 && '.' !in it) it.replace(',', '.') else it }
            .toBigDecimalOrNull()
            ?.stripTrailingZeros()
            ?.takeIf { it.signum() >= 0 && it <= MAX_DOLLARS && it.scale() <= CENTS }
            ?.multiply(MICROS_PER_DOLLAR)
            ?.toLong()

    /** A price per million tokens: `$3.00`, `$0.075`; at least cents, never rounded away. */
    fun perMillion(micros: Long): String {
        val dollars = BigDecimal(micros).divide(MICROS_PER_DOLLAR).stripTrailingZeros()
        return "$" + dollars.setScale(maxOf(CENTS, dollars.scale())).toPlainString()
    }

    /**
     * A price per million tokens as typed (`3`, `$0.075`, `0,075`), to micros; null for anything else, a negative,
     * more than $1,000 or finer than a millionth of a dollar. A comma is a decimal point, except that `1,000` could
     * be a thousand or one, so a comma before exactly three digits is refused unless the whole part is 0.
     */
    fun parsePerMillion(text: String): Long? =
        text
            .trim()
            .removePrefix("$")
            .trim()
            .let(::decimalComma)
            ?.toBigDecimalOrNull()
            ?.stripTrailingZeros()
            ?.takeIf { it.signum() >= 0 && it <= MAX_PRICE_DOLLARS && it.scale() <= MICRO_SCALE }
            ?.multiply(MICROS_PER_DOLLAR)
            ?.toLong()

    private fun decimalComma(text: String): String? {
        val whole = text.substringBefore(',')
        val fraction = text.substringAfter(',', "")
        return when {
            ',' !in text -> text
            text.count { it == ',' } > 1 || '.' in text -> null
            fraction.length == THOUSANDS_GROUP && whole.trimStart('0').isNotEmpty() -> null
            else -> "$whole.$fraction"
        }
    }

    private const val THOUSANDS_GROUP = 3
    private val MAX_PRICE_DOLLARS = BigDecimal(1_000)
    private const val MICRO_SCALE = 6

    /** `12.3k` rather than `12345`; exact token counts do not help a reader here. */
    fun tokens(count: Long): String {
        // Rounded first, so 999,960 is 1.0M rather than 1000.0k; a dot always, like the dollar amounts.
        val thousands = BigDecimal(count).divide(BigDecimal(THOUSAND)).setScale(1, RoundingMode.HALF_UP)
        return when {
            thousands >= BigDecimal(THOUSAND) ->
                BigDecimal(count).divide(BigDecimal(MILLION)).setScale(1, RoundingMode.HALF_UP).toPlainString() + "M"
            count >= THOUSAND -> thousands.toPlainString() + "k"
            else -> count.toString()
        }
    }

    private const val THOUSAND = 1_000L
    private const val MILLION = 1_000_000L
}
