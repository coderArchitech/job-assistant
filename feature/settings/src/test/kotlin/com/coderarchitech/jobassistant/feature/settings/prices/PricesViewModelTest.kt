package com.coderarchitech.jobassistant.feature.settings.prices

import com.coderarchitech.jobassistant.core.data.settings.SettingsRepository
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AppSettings
import com.coderarchitech.jobassistant.core.model.ListedPrice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PricesViewModelTest {
    /** Settings in memory; [failWrites] makes every update fail as storage would. */
    private class FakeSettings : SettingsRepository {
        val stored = MutableStateFlow(AppSettings())
        var failWrites = false

        override val settings: Flow<AppSettings> = stored

        override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
            if (failWrites) throw IOException("disk full")
            return transform(stored.value).also { stored.value = it }
        }

        override suspend fun reset() {
            stored.value = AppSettings()
        }
    }

    private val signedA = ListedPrice(AiProvider.ANTHROPIC, "fake-a", 3_000_000, 15_000_000, 300_000, 3_750_000)
    private val signedB = ListedPrice(AiProvider.OPENAI, "fake-b", 1_250_000, 10_000_000, 125_000, 0)
    private val settings = FakeSettings()
    private lateinit var viewModel: PricesViewModel

    @Before
    fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = PricesViewModel(settings) { listOf(signedB, signedA) }
    }

    @After
    fun stop() = Dispatchers.resetMain()

    private fun TestScope.collect() =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

    private fun ready() = viewModel.uiState.value as PricesUiState.Ready

    @Test
    fun signedPricesAreListedByProviderThenModel() =
        runTest {
            collect()

            assertEquals(
                listOf(PriceRow(signedA, edited = false, signed = signedA), PriceRow(signedB, false, signedB)),
                ready().rows,
            )
        }

    @Test
    fun editingASignedPriceMakesItTheUsersOwn() =
        runTest {
            collect()
            viewModel.open(ready().rows.first())
            assertEquals("3", ready().editor?.input)

            viewModel.onText(PriceField.INPUT, "2.5")
            viewModel.save()

            val edited = signedA.copy(inputMicrosPerMillion = 2_500_000)
            assertEquals(listOf(edited), settings.stored.value.priceEdits)
            assertEquals(PriceRow(edited, edited = true, signed = signedA), ready().rows.first())
            assertNull(ready().editor)
        }

    @Test
    fun useSignedPriceDropsTheEdit() =
        runTest {
            settings.stored.value = AppSettings(priceEdits = listOf(signedA.copy(outputMicrosPerMillion = 1)))
            collect()

            viewModel.open(ready().rows.first())
            viewModel.useSigned()

            assertEquals(emptyList<ListedPrice>(), settings.stored.value.priceEdits)
            assertEquals(PriceRow(signedA, edited = false, signed = signedA), ready().rows.first())
        }

    @Test
    fun aModelTheTableDoesNotListCanBeAdded() =
        runTest {
            collect()
            viewModel.add()
            viewModel.onProvider(AiProvider.OPENAI)
            viewModel.onText(PriceField.MODEL, " fake-c ")
            viewModel.onText(PriceField.INPUT, "$0.075")
            viewModel.onText(PriceField.OUTPUT, "0,3")
            viewModel.save()

            val added = ListedPrice(AiProvider.OPENAI, "fake-c", 75_000, 300_000, 0, 0)
            assertEquals(listOf(added), settings.stored.value.priceEdits)
            assertEquals(PriceRow(added, edited = true, signed = null), ready().rows.last())
        }

    @Test
    fun badEntriesAreRefusedAndNothingIsSaved() =
        runTest {
            collect()
            val cases =
                listOf(
                    Triple("fake-c", "0", PriceProblem.BAD_PRICE),
                    Triple("fake-c", "abc", PriceProblem.BAD_PRICE),
                    Triple("fake-c", "1001", PriceProblem.BAD_PRICE),
                    Triple("fake-c", "0.0000001", PriceProblem.BAD_PRICE),
                    Triple("two words", "1", PriceProblem.BAD_MODEL),
                    Triple("sk-fake-key", "1", PriceProblem.BAD_MODEL),
                    Triple("fake-a", "1", PriceProblem.DUPLICATE),
                )
            for ((model, input, problem) in cases) {
                viewModel.add()
                viewModel.onText(PriceField.MODEL, model)
                viewModel.onText(PriceField.INPUT, input)
                viewModel.onText(PriceField.OUTPUT, "1")
                viewModel.save()

                assertEquals("$model / $input", problem, ready().editor?.problem)
            }
            assertEquals(emptyList<ListedPrice>(), settings.stored.value.priceEdits)
        }

    @Test
    fun aFailedSaveReopensTheDialogWithWhatWasTyped() =
        runTest {
            collect()
            settings.failWrites = true
            viewModel.open(ready().rows.first())
            viewModel.onText(PriceField.OUTPUT, "12")

            viewModel.save()

            assertEquals(PriceProblem.SAVE_FAILED, ready().editor?.problem)
            assertEquals("12", ready().editor?.output)
        }

    @Test
    fun savingTheSignedPriceUnchangedStoresNoEdit() =
        runTest {
            settings.stored.value = AppSettings(priceEdits = listOf(signedA.copy(inputMicrosPerMillion = 1)))
            collect()
            viewModel.open(ready().rows.first())
            viewModel.onText(PriceField.INPUT, "3")

            viewModel.save()

            assertEquals(emptyList<ListedPrice>(), settings.stored.value.priceEdits)
        }

    @Test
    fun aThousandWithACommaIsNotReadAsOne() =
        runTest {
            collect()
            viewModel.open(ready().rows.first())
            viewModel.onText(PriceField.OUTPUT, "1,000")

            viewModel.save()

            assertEquals(PriceProblem.BAD_PRICE, ready().editor?.problem)
        }

    @Test
    fun theLimitOnEditsIsExplained() =
        runTest {
            settings.stored.value =
                AppSettings(
                    priceEdits = (1..AppSettings.MAX_PRICE_EDITS).map { signedA.copy(model = "fake-$it") },
                )
            collect()
            viewModel.add()
            viewModel.onText(PriceField.MODEL, "fake-new")
            viewModel.onText(PriceField.INPUT, "1")
            viewModel.onText(PriceField.OUTPUT, "1")

            viewModel.save()

            assertEquals(PriceProblem.TOO_MANY, ready().editor?.problem)
        }
}
