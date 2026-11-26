package com.coderarchitech.jobassistant.feature.settings.usage

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.coderarchitech.jobassistant.core.data.repository.UsageOverview
import com.coderarchitech.jobassistant.core.model.Budget
import com.coderarchitech.jobassistant.core.model.RunUsage
import com.coderarchitech.jobassistant.core.model.UsageTotal
import com.coderarchitech.jobassistant.core.ui.JobAssistantTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** The usage meter on Robolectric: behaviour, and light and dark screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class UsageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val runs =
        listOf(
            RunUsage(42, Instant.parse("2026-11-26T03:30:00Z"), UsageTotal(31, 182_000, 9_400, 412_000)),
            RunUsage(41, Instant.parse("2026-11-25T03:30:00Z"), UsageTotal(27, 150_200, 8_100, 355_000)),
            RunUsage(40, Instant.parse("2026-11-24T03:30:00Z"), UsageTotal(4, 9_800, 700, 8_400)),
        )

    private fun overview(spentMicros: Long) =
        UsageOverview(
            month = YearMonth.of(2026, 11),
            budget = Budget(monthlyCapMicros = 10_000_000, browserAgentDailyMicros = 0),
            thisMonth = UsageTotal(62, 342_000, 18_200, spentMicros),
            today = UsageTotal(31, 182_000, 9_400, 412_000),
            days =
                listOf(
                    LocalDate.of(2026, 11, 26) to UsageTotal(31, 182_000, 9_400, 412_000),
                    LocalDate.of(2026, 11, 25) to UsageTotal(27, 150_200, 8_100, 355_000),
                    LocalDate.of(2026, 11, 24) to UsageTotal(4, 9_800, 700, 8_400),
                ),
            runs = runs,
        )

    private fun show(
        spentMicros: Long = 775_400,
        dark: Boolean = false,
        actions: UsageActions = UsageActions(),
    ) = compose.setContent {
        JobAssistantTheme(darkTheme = dark, dynamicColor = false) {
            UsageScreen(
                UsageUiState(overview(spentMicros)),
                actions,
                onBack = {},
                zone = ZoneOffset.ofHoursMinutes(5, 30),
            )
        }
    }

    /** Task T4.13 Done when, first half: per-run cost is visible (BR2). */
    @Test
    fun eachRunShowsWhatItCost() {
        show()

        val costs =
            compose
                .onAllNodesWithTag("runCost", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
        assertEquals(listOf("$0.41", "$0.36", "$0.0084"), costs)
        compose.onAllNodesWithText("31 calls · 191.4k tokens", useUnmergedTree = true)[0].assertExists()
    }

    /** Task T4.13 Done when, second half as the screen shows it: at the cap, runs are paused. */
    @Test
    fun atTheCapTheScreenSaysRunsArePaused() {
        show(spentMicros = 10_000_000)

        compose.onNodeWithTag("capStatus").assertIsDisplayed()
        compose.onNodeWithText("Budget reached", substring = true).assertIsDisplayed()
    }

    @Test
    fun changeOpensTheBudgetEditor() {
        var field: BudgetField? = null
        show(actions = UsageActions(editBudget = { field = it }))

        compose.onNodeWithText("Monthly budget").assertIsDisplayed()
        compose.onAllNodesWithText("Change")[0].performClick()

        assertEquals(BudgetField.MONTHLY, field)
    }

    @Test
    fun screenshotLight() {
        show()
        compose.onRoot().captureRoboImage("src/test/screenshots/usage_light.png")
    }

    @Test
    fun screenshotDark() {
        show(dark = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/usage_dark.png")
    }

    @Test
    fun screenshotCapReachedLight() {
        show(spentMicros = 10_000_000)
        compose.onRoot().captureRoboImage("src/test/screenshots/usage_cap_reached_light.png")
    }
}
