package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.ai.LlmProviderContract.Companion.request
import com.coderarchitech.jobassistant.ai.LlmProviderContract.Scenario
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.model.Budget
import com.coderarchitech.jobassistant.core.model.BudgetSource
import com.coderarchitech.jobassistant.core.model.UsageLedger
import com.coderarchitech.jobassistant.core.model.UsageRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MeteredLlmProviderTest {
    private val now = Instant.parse("2026-03-15T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val price = ModelPrice(3_000_000, 15_000_000, 300_000, 3_750_000)
    private val ledger = InMemoryLedger()
    private var budget = Budget(monthlyCapMicros = ONE_DOLLAR)
    private val inner = FakeLlmProvider(Scenario.SUCCESS)

    private fun metered(
        delegate: LlmProvider = inner,
        prices: PriceTable = PriceTable { _, _ -> price },
        budgets: BudgetSource = BudgetSource { budget },
        usageLedger: UsageLedger = ledger,
    ) = MeteredLlmProvider(delegate, usageLedger, budgets, prices, { _, _ -> "fake-small" }, clock)

    /** A request whose worst case is [outputTokens] of output at the test price ($15 per million). */
    private fun requestWith(
        outputTokens: Int,
        task: AiTask = AiTask.SCORE,
    ) = request().let { it.copy(task = task, maxOutputTokens = outputTokens) }

    /** Task T1.14 Done when: a test with a $1 cap refuses the call that would cross it. */
    @Test
    fun aCallThatWouldCrossAOneDollarCapIsRefused() =
        runTest {
            ledger.add(now.minusSeconds(60), AiTask.SCORE, 900_000) // $0.90 spent this month

            // 20,000 output tokens at $15 per million is $0.30 worst case: $0.90 + $0.30 crosses $1.
            val refused = metered().generate(requestWith(outputTokens = 20_000))

            assertEquals(LlmResult.Blocked(BlockReason.BUDGET_EXCEEDED), refused)
            assertTrue("the provider was never called", inner.requests.isEmpty())
            assertEquals("nothing was recorded for a refused call", 1, ledger.rows.size)
        }

    @Test
    fun aCallThatFitsTheBudgetIsSentAndRecorded() =
        runTest {
            ledger.add(now.minusSeconds(60), AiTask.SCORE, 900_000)

            // 5,000 output tokens is $0.075 worst case (plus a little input): $0.90 + that stays under $1.
            val result = metered().generate(requestWith(outputTokens = 5_000))

            assertTrue(result is LlmResult.Success)
            assertEquals(1, inner.requests.size)
            assertEquals(2, ledger.rows.size)
        }

    @Test
    fun theUsageRowHoldsEveryTokenKindPricedFromTheModelThatAnswered() =
        runTest {
            val usage =
                TokenUsage(
                    inputTokens = 1_000,
                    outputTokens = 500,
                    cacheReadInputTokens = 8_000,
                    cacheWriteInputTokens = 2_000,
                )
            val provider = StubProvider(LlmResult.Truncated(usage))

            withContext(UsageRunId(42)) { metered(provider).generate(requestWith(1_000)) }

            val row = ledger.recorded.single()
            assertEquals(42L, row.runId)
            assertEquals(AiProvider.ANTHROPIC, row.provider)
            assertEquals(AiTask.SCORE, row.task)
            assertEquals(1_000L, row.inputTokens)
            assertEquals(500L, row.outputTokens)
            assertEquals(8_000L, row.cachedTokens)
            assertEquals(2_000L, row.cacheWriteTokens)
            // 1,000 x $3 + 500 x $15 + 8,000 x $0.30 + 2,000 x $3.75, per million: 3,000 + 7,500 + 2,400 + 7,500.
            assertEquals(20_400L, row.estimatedCostMicros)
            assertEquals(now, row.recordedAt)
        }

    @Test
    fun resultsThatReportTokensAreRecordedAndOthersAreNot() =
        runTest {
            val usage = TokenUsage(10, 5)
            val withUsage =
                listOf(
                    LlmResult.Refused(usage, null),
                    LlmResult.Truncated(usage),
                    LlmResult.InvalidOutput("raw", "bad", usage),
                )
            val withoutUsage =
                listOf(
                    LlmResult.InvalidKey,
                    LlmResult.RateLimited(null),
                    LlmResult.ProviderError(500, "boom"),
                    LlmResult.NetworkError("down"),
                )

            for (result in withUsage + withoutUsage) metered(StubProvider(result)).generate(requestWith(100))

            assertEquals(withUsage.size, ledger.recorded.size)
        }

    @Test
    fun anUnlistedModelIsPricedHighSoTheBudgetFailsSafe() =
        runTest {
            val unlisted = PriceTable { _, _ -> null }
            // 20,000 output tokens fit at the test price ($0.30) but not at the fallback price ($1.50).
            assertTrue(metered().generate(requestWith(20_000)) is LlmResult.Success)

            val refused = metered(prices = unlisted).generate(requestWith(20_000))
            assertEquals(LlmResult.Blocked(BlockReason.BUDGET_EXCEEDED), refused)
        }

    @Test
    fun spendFromEarlierMonthsDoesNotCount() =
        runTest {
            ledger.add(Instant.parse("2026-02-28T23:59:59Z"), AiTask.SCORE, 5_000_000)

            assertTrue(metered().generate(requestWith(5_000)) is LlmResult.Success)
        }

    @Test
    fun anUnreadableBudgetRefusesTheCall() =
        runTest {
            val broken = BudgetSource { error("settings storage failed") }

            assertEquals(
                LlmResult.Blocked(BlockReason.BUDGET_UNAVAILABLE),
                metered(budgets = broken).generate(requestWith(100)),
            )
            assertTrue(inner.requests.isEmpty())
        }

    @Test
    fun anUnreadableLedgerRefusesTheCall() =
        runTest {
            val broken =
                object : UsageLedger {
                    override suspend fun spentMicrosSince(
                        from: Instant,
                        task: AiTask?,
                    ): Long = error("database failed")

                    override suspend fun record(usage: UsageRecord) = Unit
                }

            assertEquals(
                LlmResult.Blocked(BlockReason.BUDGET_UNAVAILABLE),
                metered(usageLedger = broken).generate(requestWith(100)),
            )
            assertTrue(inner.requests.isEmpty())
        }

    @Test
    fun aFailedUsageWriteDoesNotHideTheResult() =
        runTest {
            val failingWrites =
                object : UsageLedger {
                    override suspend fun spentMicrosSince(
                        from: Instant,
                        task: AiTask?,
                    ): Long = 0

                    override suspend fun record(usage: UsageRecord) = error("disk full")
                }

            assertTrue(metered(usageLedger = failingWrites).generate(requestWith(100)) is LlmResult.Success)
        }

    @Test
    fun theBrowserAgentIsOffUntilItHasADailyBudget() =
        runTest {
            val step = requestWith(100, AiTask.BROWSER_STEP)

            assertEquals(LlmResult.Blocked(BlockReason.BUDGET_EXCEEDED), metered().generate(step))

            budget = Budget(monthlyCapMicros = ONE_DOLLAR, browserAgentDailyMicros = 500_000)
            assertTrue(metered().generate(step) is LlmResult.Success)
        }

    @Test
    fun theBrowserAgentsDailyBudgetCountsOnlyItsOwnCallsToday() =
        runTest {
            budget = Budget(monthlyCapMicros = ONE_DOLLAR, browserAgentDailyMicros = 500_000)
            ledger.add(Instant.parse("2026-03-14T23:00:00Z"), AiTask.BROWSER_STEP, 490_000) // yesterday
            ledger.add(now.minusSeconds(60), AiTask.SCORE, 100_000) // other tasks do not use it
            val step = requestWith(100, AiTask.BROWSER_STEP)

            assertTrue(metered().generate(step) is LlmResult.Success)

            ledger.add(now.minusSeconds(30), AiTask.BROWSER_STEP, 499_000) // today
            assertEquals(LlmResult.Blocked(BlockReason.BUDGET_EXCEEDED), metered().generate(step))
        }

    @Test
    fun callsStartedTogetherCannotTogetherPassTheCap() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val slow = StubProvider(LlmResult.Truncated(TokenUsage(1, 1))) { release.await() }
            val provider = metered(slow)

            // Each call's worst case is about $0.60 against a $1 cap: either fits alone, not both.
            val first = async { provider.generate(requestWith(40_000)) }
            testScheduler.runCurrent()
            val second = provider.generate(requestWith(40_000))
            release.complete(Unit)

            assertEquals(LlmResult.Blocked(BlockReason.BUDGET_EXCEEDED), second)
            assertTrue(first.await() is LlmResult.Truncated)
        }

    @Test
    fun aFinishedCallNoLongerHoldsBudget() =
        runTest {
            val provider = metered()

            // Each worst case is about $0.60, but the calls run one after the other and the first costs little.
            assertTrue(provider.generate(requestWith(40_000)) is LlmResult.Success)
            assertTrue(provider.generate(requestWith(40_000)) is LlmResult.Success)
        }

    @Test
    fun aCancelledCallGivesItsReservationBack() =
        runTest {
            var calls = 0
            val provider =
                metered(
                    StubProvider(LlmResult.Truncated(TokenUsage(1, 1))) {
                        if (calls++ ==
                            0
                        ) {
                            CompletableDeferred<Unit>().await()
                        }
                    },
                )

            // Each worst case is about $0.60 against a $1 cap, so a leaked reservation would block the next call.
            val stuck = async { provider.generate(requestWith(40_000)) }
            testScheduler.runCurrent()
            stuck.cancel()
            testScheduler.runCurrent()

            assertTrue(provider.generate(requestWith(40_000)) is LlmResult.Truncated)
        }

    @Test
    fun keyChecksAndModelListsPassThrough() =
        runTest {
            val broken = BudgetSource { error("settings storage failed") }

            assertEquals(KeyCheck.Valid, metered(budgets = broken).validateKey())
            assertTrue(metered(budgets = broken).listModels() is ModelList.Available)
        }

    private class InMemoryLedger : UsageLedger {
        data class Entry(
            val at: Instant,
            val task: AiTask,
            val cost: Long,
        )

        val rows = mutableListOf<Entry>()
        val recorded = mutableListOf<UsageRecord>()

        fun add(
            at: Instant,
            task: AiTask,
            cost: Long,
        ) {
            rows += Entry(at, task, cost)
        }

        override suspend fun spentMicrosSince(
            from: Instant,
            task: AiTask?,
        ): Long = rows.filter { it.at >= from && (task == null || it.task == task) }.sumOf { it.cost }

        override suspend fun record(usage: UsageRecord) {
            recorded += usage
            rows += Entry(usage.recordedAt, usage.task, usage.estimatedCostMicros)
        }
    }

    private class StubProvider(
        private val result: LlmResult<Nothing>,
        private val beforeAnswer: suspend () -> Unit = {},
    ) : LlmProvider {
        override val id = AiProvider.ANTHROPIC

        override suspend fun validateKey() = KeyCheck.Valid

        override suspend fun listModels() = ModelList.Available(emptyList())

        override suspend fun <T> generate(request: LlmRequest<T>): LlmResult<T> {
            beforeAnswer()
            return result
        }
    }

    private companion object {
        const val ONE_DOLLAR = 1_000_000L
    }
}
