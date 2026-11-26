package com.coderarchitech.jobassistant.feature.settings.prices

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.ListedPrice
import com.coderarchitech.jobassistant.core.ui.JobAssistantTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The price editor on Robolectric: behaviour, and light and dark screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class PricesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sonnet = ListedPrice(AiProvider.ANTHROPIC, "claude-sonnet-x", 3_000_000, 15_000_000, 300_000, 3_750_000)
    private val haiku = ListedPrice(AiProvider.ANTHROPIC, "claude-haiku-x", 800_000, 4_000_000, 80_000, 1_000_000)
    private val mini = ListedPrice(AiProvider.OPENAI, "gpt-mini-x", 150_000, 600_000, 75_000, 0)

    private val rows =
        listOf(
            PriceRow(haiku.copy(inputMicrosPerMillion = 1_000_000), edited = true, signed = haiku),
            PriceRow(sonnet, edited = false, signed = sonnet),
            PriceRow(mini, edited = true, signed = null),
        )

    /** The form on its own, as the watchlist tests do: a sheet's window never idles on Robolectric. */
    private fun showForm(
        editor: PriceEditor,
        dark: Boolean = false,
        actions: PricesActions = PricesActions(),
    ) = compose.setContent {
        JobAssistantTheme(darkTheme = dark, dynamicColor = false) {
            Surface { PriceForm(editor, actions, Modifier.padding(top = 24.dp)) }
        }
    }

    private fun show(
        state: PricesUiState,
        dark: Boolean = false,
        actions: PricesActions = PricesActions(),
    ) = compose.setContent {
        JobAssistantTheme(darkTheme = dark, dynamicColor = false) {
            PricesScreen(state, actions, onBack = {})
        }
    }

    @Test
    fun eachModelShowsItsPricesAndWhoseTheyAre() {
        show(PricesUiState.Ready(rows))

        compose.onNodeWithText("In $1.00 · Out $4.00 · Cache $0.08 / $1.00").assertIsDisplayed()
        compose.onNodeWithText("In $0.15 · Out $0.60 · Cache $0.075 / $0.00").assertIsDisplayed()
        compose.onNodeWithText("Anthropic").assertIsDisplayed()
        compose.onNodeWithText("OpenAI").assertIsDisplayed()
    }

    @Test
    fun tappingAModelOpensItsEditor() {
        var opened: PriceRow? = null
        show(PricesUiState.Ready(rows), actions = PricesActions(open = { opened = it }))

        compose.onNodeWithTag("price:claude-sonnet-x").performClick()

        assertEquals(rows[1], opened)
    }

    @Test
    fun anEditedPriceCanGoBackToTheSignedOne() {
        var reset = false
        val editor =
            PriceEditor(
                false,
                AiProvider.ANTHROPIC,
                "claude-haiku-x",
                "1",
                "4",
                "0.08",
                "1",
                signed = haiku,
                edited = true,
            )
        showForm(editor, actions = PricesActions(useSigned = { reset = true }))

        compose.onNodeWithText("Use signed price").performClick()

        assertEquals(true, reset)
    }

    @Test
    fun screenshotLight() {
        show(PricesUiState.Ready(rows))
        compose.onRoot().captureRoboImage("src/test/screenshots/prices_light.png")
    }

    @Test
    fun screenshotDark() {
        show(PricesUiState.Ready(rows), dark = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/prices_dark.png")
    }

    @Test
    fun screenshotEmpty() {
        show(PricesUiState.Ready(emptyList()))
        compose.onNodeWithText("No prices listed yet").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/prices_empty_light.png")
    }

    @Test
    fun screenshotEditingDark() {
        val editor =
            PriceEditor(
                false,
                AiProvider.ANTHROPIC,
                "claude-haiku-x",
                "1",
                "4",
                "0.08",
                "1",
                signed = haiku,
                edited = true,
            )
        showForm(editor, dark = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/prices_edit_dark.png")
    }

    @Test
    fun screenshotAddingAModel() {
        val editor =
            PriceEditor(
                isNew = true,
                provider = AiProvider.OPENAI,
                model = "gpt-mini-x",
                input = "0",
                output = "0.6",
                cacheRead = "0",
                cacheWrite = "0",
                problem = PriceProblem.BAD_PRICE,
            )
        showForm(editor)
        compose.onNodeWithTag("priceMessage").assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/prices_add_light.png")
    }
}
