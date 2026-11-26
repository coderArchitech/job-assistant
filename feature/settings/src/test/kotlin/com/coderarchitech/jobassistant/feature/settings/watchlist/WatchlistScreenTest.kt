package com.coderarchitech.jobassistant.feature.settings.watchlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.CompanyWatchlistEntry
import com.coderarchitech.jobassistant.core.ui.JobAssistantTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The watchlist screen on Robolectric: behaviour checks, and light and dark screenshots
 * (`./gradlew :feature:settings:recordRoborazziDebug` writes them to `src/test/screenshots`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class WatchlistScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val companies =
        listOf(
            CompanyRow(CompanyWatchlistEntry(1, "Ashby", AtsType.ASHBY, "Ashby"), CheckState.Found(76)),
            CompanyRow(CompanyWatchlistEntry(2, "Lever Demo", AtsType.LEVER, "leverdemo"), CheckState.Checking),
            CompanyRow(CompanyWatchlistEntry(3, "Northwind", AtsType.GREENHOUSE, "northwind"), CheckState.NotFound),
            CompanyRow(CompanyWatchlistEntry(4, "Stripe", AtsType.GREENHOUSE, "stripe"), CheckState.Found(512)),
            CompanyRow(CompanyWatchlistEntry(5, "Zeta", AtsType.LEVER, "zeta", enabled = false), CheckState.NotChecked),
        )

    private fun show(
        state: WatchlistUiState,
        dark: Boolean = false,
        actions: WatchlistActions = WatchlistActions(),
    ) = compose.setContent {
        JobAssistantTheme(darkTheme = dark, dynamicColor = false) {
            WatchlistScreen(state = state, actions = actions, onBack = {})
        }
    }

    private fun showForm(
        editor: EditorState,
        dark: Boolean = false,
    ) = compose.setContent {
        JobAssistantTheme(darkTheme = dark, dynamicColor = false) {
            Sheet { CompanyForm(editor, EditorActions()) }
        }
    }

    /** The sheet's surface, without the modal window Robolectric cannot capture with the screen. */
    @Composable
    private fun Sheet(content: @Composable () -> Unit) =
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { Box(Modifier.padding(top = 24.dp)) { content() } }

    @Test
    fun theListShowsEachCompanyWithItsBoardAndCheck() {
        show(WatchlistUiState(loading = false, companies = companies))

        compose.onNodeWithText("Greenhouse · stripe").assertIsDisplayed()
        compose.onNodeWithText("512 open roles").assertIsDisplayed()
        compose.onNodeWithText("Board not found").assertIsDisplayed()
        compose.onNodeWithText("Paused").assertIsDisplayed()
        // The button floats at the window's bottom edge, so it exists rather than being fully in view.
        compose.onNodeWithText("Add company", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theEmptyStateExplainsAndOffersToAdd() {
        var added = false
        show(WatchlistUiState(loading = false), actions = WatchlistActions(add = { added = true }))

        compose.onNodeWithText("No companies yet").assertIsDisplayed()
        compose.onNodeWithText("Add company").performClick()

        assert(added)
    }

    @Test
    fun theFormShowsTheBoardsPublicPageAndTheCheckResult() {
        showForm(
            EditorState(name = "Stripe", ats = AtsType.GREENHOUSE, board = "stripe", check = CheckState.Found(512)),
        )

        compose.onNodeWithText("https://boards.greenhouse.io/stripe").assertIsDisplayed()
        compose.onNodeWithTag("checkResult").assertIsDisplayed()
        compose
            .onNodeWithText(
                "512 open roles. The board answered. Runs will pick up its openings.",
            ).assertIsDisplayed()
    }

    @Test
    fun theFormMarksMistakesOnceTheUserTriesToSave() {
        showForm(EditorState(board = "a/b", showErrors = true))

        compose.onNodeWithText("Enter the company's name").assertIsDisplayed()
        compose.onNodeWithText("Use letters, digits, dashes or underscores").assertIsDisplayed()
    }

    @Test
    fun typingInTheFormReachesTheViewModel() {
        var typed = ""
        compose.setContent {
            JobAssistantTheme(dynamicColor = false) { CompanyForm(EditorState(), EditorActions(name = { typed = it })) }
        }

        compose.onNodeWithTag("name").performTextInput("Acme")

        assert(typed == "Acme")
    }

    @Test
    fun screenshotListLight() {
        show(WatchlistUiState(loading = false, companies = companies))
        compose.onRoot().captureRoboImage("src/test/screenshots/watchlist_list_light.png")
    }

    @Test
    fun screenshotListDark() {
        show(WatchlistUiState(loading = false, companies = companies), dark = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/watchlist_list_dark.png")
    }

    @Test
    fun screenshotEmptyLight() {
        show(WatchlistUiState(loading = false))
        compose.onRoot().captureRoboImage("src/test/screenshots/watchlist_empty_light.png")
    }

    @Test
    fun screenshotEmptyDark() {
        show(WatchlistUiState(loading = false), dark = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/watchlist_empty_dark.png")
    }

    @Test
    fun screenshotFormLight() {
        showForm(
            EditorState(name = "Stripe", ats = AtsType.GREENHOUSE, board = "stripe", check = CheckState.Found(512)),
        )
        compose.onRoot().captureRoboImage("src/test/screenshots/watchlist_form_light.png")
    }

    @Test
    fun screenshotFormDark() {
        showForm(
            EditorState(name = "Northwind", ats = AtsType.ASHBY, board = "northwind", check = CheckState.NotFound),
            dark = true,
        )
        compose.onRoot().captureRoboImage("src/test/screenshots/watchlist_form_dark.png")
    }
}
