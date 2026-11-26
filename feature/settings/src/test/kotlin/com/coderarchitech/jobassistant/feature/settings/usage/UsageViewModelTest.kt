package com.coderarchitech.jobassistant.feature.settings.usage

import com.coderarchitech.jobassistant.core.data.repository.UsageOverview
import com.coderarchitech.jobassistant.core.data.repository.UsageReport
import com.coderarchitech.jobassistant.core.model.Budget
import com.coderarchitech.jobassistant.core.model.UsageTotal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModelTest {
    private class FakeReport : UsageReport {
        val budget = MutableStateFlow(Budget())

        override fun observe(): Flow<UsageOverview> =
            budget.map {
                UsageOverview(
                    YearMonth.of(2026, 11),
                    it,
                    UsageTotal(),
                    UsageTotal(),
                    emptyList(),
                    emptyList(),
                )
            }

        override suspend fun setBudget(budget: Budget) {
            this.budget.value = budget
        }
    }

    private val report = FakeReport()
    private lateinit var viewModel: UsageViewModel

    @Before
    fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = UsageViewModel(report)
    }

    @After
    fun stop() = Dispatchers.resetMain()

    @Test
    fun theMonthlyBudgetIsChangedInDollars() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.editBudget(BudgetField.MONTHLY)
            assertEquals(
                "10",
                viewModel.uiState.value.editor!!
                    .text,
            )
            viewModel.onBudgetText("$25.50")
            viewModel.saveBudget()

            assertEquals(25_500_000L, report.budget.value.monthlyCapMicros)
            assertNull(viewModel.uiState.value.editor)
        }

    @Test
    fun aBadAmountIsRefusedAndTheBudgetKept() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            viewModel.editBudget(BudgetField.BROWSER_AGENT_DAILY)

            viewModel.onBudgetText("-3")
            viewModel.saveBudget()

            assertTrue(
                viewModel.uiState.value.editor!!
                    .invalid,
            )
            assertEquals(Budget().browserAgentDailyMicros, report.budget.value.browserAgentDailyMicros)
        }

    @Test
    fun moneyIsShownAndReadAsPeopleWriteIt() {
        assertEquals("$3.42", Money.format(3_420_000))
        assertEquals("small amounts keep their digits", "$0.0123", Money.format(12_300))
        assertEquals("$0.00", Money.format(0))
        assertEquals("$10.00", Money.format(10_000_000))
        assertEquals("2.5", Money.plainDollars(2_500_000))
        assertEquals(2_500_000L, Money.parseDollars(" $2.50 "))
        assertEquals(0L, Money.parseDollars("0"))
        for (bad in listOf("", "abc", "-1", "10001", "1.234", "1,000")) assertNull(bad, Money.parseDollars(bad))
    }
}
