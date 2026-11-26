package com.coderarchitech.jobassistant.feature.settings.prices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coderarchitech.jobassistant.core.data.settings.SettingsRepository
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiSettings
import com.coderarchitech.jobassistant.core.model.AppSettings
import com.coderarchitech.jobassistant.core.model.ListedPrice
import com.coderarchitech.jobassistant.feature.settings.usage.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** The signed price table as the editor reads it (task T3.09). */
fun interface SignedPrices {
    /** Every price the signed table lists. */
    suspend fun load(): List<ListedPrice>
}

/** One model on the prices screen: the price in effect, whether the user set it, and the signed one if any. */
data class PriceRow(
    val price: ListedPrice,
    val edited: Boolean,
    val signed: ListedPrice?,
)

/** A field of the price editor. */
enum class PriceField { MODEL, INPUT, OUTPUT, CACHE_READ, CACHE_WRITE }

/** Why the editor cannot save. */
enum class PriceProblem {
    /** The model is not a model id. */
    BAD_MODEL,

    /** A price is not a dollar amount, or input or output is zero. */
    BAD_PRICE,

    /** A new entry for a model that already has a row. */
    DUPLICATE,

    /** The user already has the most edits the settings keep. */
    TOO_MANY,

    /** The settings could not be written; try again. */
    SAVE_FAILED,
}

/** The price dialog: what is typed, in dollars per million tokens. */
data class PriceEditor(
    val isNew: Boolean,
    val provider: AiProvider,
    val model: String,
    val input: String,
    val output: String,
    val cacheRead: String,
    val cacheWrite: String,
    /** The signed price for this model, which "Use signed price" goes back to. */
    val signed: ListedPrice? = null,
    /** True when the user's own price is in effect now. */
    val edited: Boolean = false,
    val problem: PriceProblem? = null,
)

/** The prices screen: loading until the settings and the signed table are read. */
sealed interface PricesUiState {
    data object Loading : PricesUiState

    data class Ready(
        val rows: List<PriceRow>,
        val editor: PriceEditor? = null,
    ) : PricesUiState
}

/**
 * The model prices the usage meter counts with (task T3.09): the signed table, and the user's own prices, which
 * win over it. A user price can be changed or dropped to go back to the signed one.
 */
@HiltViewModel
class PricesViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        signed: SignedPrices,
    ) : ViewModel() {
        private val editor = MutableStateFlow<PriceEditor?>(null)

        val uiState: StateFlow<PricesUiState> =
            combine(
                flow { emit(signed.load()) },
                // Unreadable settings leave the screen loading rather than ending the app.
                settings.settings.map { it.priceEdits }.catch { e -> if (e !is IOException) throw e },
                editor,
            ) { listed, edits, edit -> PricesUiState.Ready(rowsOf(listed, edits), edit) as PricesUiState }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PricesUiState.Loading)

        /** Opens the editor on [row]. */
        fun open(row: PriceRow) {
            val price = row.price
            editor.value =
                PriceEditor(
                    isNew = false,
                    provider = price.provider,
                    model = price.model,
                    input = Money.plainDollars(price.inputMicrosPerMillion),
                    output = Money.plainDollars(price.outputMicrosPerMillion),
                    cacheRead = Money.plainDollars(price.cacheReadMicrosPerMillion),
                    cacheWrite = Money.plainDollars(price.cacheWriteMicrosPerMillion),
                    signed = row.signed,
                    edited = row.edited,
                )
        }

        /** Opens an empty editor for a model the table does not list. */
        fun add() {
            editor.value = PriceEditor(true, AiProvider.ANTHROPIC, "", "", "", "0", "0")
        }

        fun onProvider(provider: AiProvider) = editor.update { it?.copy(provider = provider, problem = null) }

        fun onText(
            field: PriceField,
            text: String,
        ) = editor.update { edit ->
            when (field) {
                PriceField.MODEL -> edit?.copy(model = text.trim())
                PriceField.INPUT -> edit?.copy(input = text)
                PriceField.OUTPUT -> edit?.copy(output = text)
                PriceField.CACHE_READ -> edit?.copy(cacheRead = text)
                PriceField.CACHE_WRITE -> edit?.copy(cacheWrite = text)
            }?.copy(problem = null)
        }

        fun close() {
            editor.value = null
        }

        /** Saves the typed price as the user's own for that model. */
        fun save() {
            val edit = editor.value ?: return
            val price = priceOf(edit)
            val problem = problemWith(edit, price)
            when {
                problem != null || price == null -> editor.update { it?.copy(problem = problem) }
                // The signed price unchanged is not an edit: storing it would pin today's price for good.
                price == edit.signed -> write(edit) { edits -> edits.filterNot(price::isSameModel) }
                else -> write(edit) { edits -> listOf(price) + edits.filterNot(price::isSameModel) }
            }
        }

        /** Why [price], typed in [edit], cannot be saved; null when it can. */
        private fun problemWith(
            edit: PriceEditor,
            price: ListedPrice?,
        ): PriceProblem? {
            val rows = (uiState.value as? PricesUiState.Ready)?.rows.orEmpty()
            val addsAnEdit = !edit.edited && edit.signed != price
            return when {
                !AiSettings.isValidModelId(edit.model) -> PriceProblem.BAD_MODEL
                price == null || !AppSettings.isValidPriceEdit(price) -> PriceProblem.BAD_PRICE
                edit.isNew && rows.any { it.price.isSameModel(price) } -> PriceProblem.DUPLICATE
                addsAnEdit && rows.count { it.edited } >= AppSettings.MAX_PRICE_EDITS -> PriceProblem.TOO_MANY
                else -> null
            }
        }

        /** Drops the user's price for the open model, so the signed one (or the fallback) applies again. */
        fun useSigned() {
            val edit = editor.value ?: return
            write(edit) { edits -> edits.filterNot { it.provider == edit.provider && it.model == edit.model } }
        }

        private fun write(
            edit: PriceEditor,
            change: (List<ListedPrice>) -> List<ListedPrice>,
        ) {
            editor.value = null
            viewModelScope.launch {
                // A failed write reopens the dialog with what was typed, rather than losing it or crashing.
                val problem =
                    try {
                        settings.update { it.copy(priceEdits = change(it.priceEdits)) }
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        PriceProblem.SAVE_FAILED
                    } catch (_: IllegalArgumentException) {
                        // A settings rule the checks above did not foresee; nothing was written.
                        PriceProblem.SAVE_FAILED
                    }
                // Reopens this edit only if the user has not opened another meanwhile.
                if (problem != null) editor.compareAndSet(null, edit.copy(problem = problem))
            }
        }

        private fun priceOf(edit: PriceEditor): ListedPrice? {
            val numbers = listOf(edit.input, edit.output, edit.cacheRead, edit.cacheWrite).map(Money::parsePerMillion)
            return if (numbers.any { it == null } || edit.model.isBlank()) {
                null
            } else {
                ListedPrice(
                    provider = edit.provider,
                    model = edit.model,
                    inputMicrosPerMillion = numbers[0] ?: 0,
                    outputMicrosPerMillion = numbers[1] ?: 0,
                    cacheReadMicrosPerMillion = numbers[2] ?: 0,
                    cacheWriteMicrosPerMillion = numbers[3] ?: 0,
                )
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L

            /** Every model the table or the user prices, by provider then model id. */
            fun rowsOf(
                listed: List<ListedPrice>,
                edits: List<ListedPrice>,
            ): List<PriceRow> {
                val signedRows =
                    listed.map { signed ->
                        PriceRow(
                            edits.firstOrNull(signed::isSameModel) ?: signed,
                            edits.any(signed::isSameModel),
                            signed,
                        )
                    }
                val userOnly =
                    edits
                        .filter { edit ->
                            listed.none(edit::isSameModel)
                        }.map { PriceRow(it, edited = true, signed = null) }
                return (signedRows + userOnly).sortedWith(compareBy({ it.price.provider }, { it.price.model }))
            }
        }
    }
