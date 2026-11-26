package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.dao.UsageRecordDao
import com.coderarchitech.jobassistant.core.data.db.entity.UsageRecordEntity
import com.coderarchitech.jobassistant.core.data.settings.SettingsRepository
import com.coderarchitech.jobassistant.core.model.Budget
import com.coderarchitech.jobassistant.core.model.RunUsage
import com.coderarchitech.jobassistant.core.model.UsageTotal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** This month's AI usage against the budget, by day and by run (task T4.13). */
data class UsageOverview(
    val month: YearMonth,
    val budget: Budget,
    val thisMonth: UsageTotal,
    val today: UsageTotal,
    /** Days of this month with any usage, newest first. */
    val days: List<Pair<LocalDate, UsageTotal>>,
    /** The most recent runs, newest first. */
    val runs: List<RunUsage>,
) {
    /** True once this month's spend has reached the cap: AI calls are refused, so runs stop scoring. */
    val capReached: Boolean get() = thisMonth.costMicros >= budget.monthlyCapMicros

    /** The first day of next month, when the cap resets. */
    val resetsOn: LocalDate get() = month.plusMonths(1).atDay(1)
}

/** Reads AI usage for the usage meter and changes the budget. */
interface UsageReport {
    /** This month's usage, updated whenever a call is recorded or the budget changes. Days are local days. */
    fun observe(): Flow<UsageOverview>

    /** Saves a new budget; the metered provider reads it on its next call. */
    suspend fun setBudget(budget: Budget)
}

/** The [UsageReport] over `usage_record` and the settings. The month is the device's local calendar month. */
internal class RoomUsageReport
    @Inject
    constructor(
        private val dao: UsageRecordDao,
        private val settings: SettingsRepository,
        private val clock: Clock,
    ) : UsageReport {
        override fun observe(): Flow<UsageOverview> {
            val zone: ZoneId = clock.zone
            val month = YearMonth.now(clock)
            val records = dao.observeSince(month.atDay(1).atStartOfDay(zone).toInstant())
            return combine(
                records,
                dao.observeRuns(RECENT_RUNS),
                settings.settings.map { it.budget },
            ) { rows, runs, budget ->
                val byDay =
                    rows
                        .groupBy { it.recordedAt.atZone(zone).toLocalDate() }
                        .mapValues { (_, day) -> day.fold(UsageTotal()) { sum, r -> sum + r.total() } }
                UsageOverview(
                    month = month,
                    budget = budget,
                    thisMonth = byDay.values.fold(UsageTotal()) { sum, day -> sum + day },
                    today = byDay[LocalDate.now(clock)] ?: UsageTotal(),
                    days = byDay.entries.sortedByDescending { it.key }.map { it.key to it.value },
                    runs =
                        runs.map {
                            RunUsage(
                                it.runId,
                                it.startedAt,
                                UsageTotal(it.calls, it.inputTokens, it.outputTokens, it.costMicros),
                            )
                        },
                )
            }
        }

        override suspend fun setBudget(budget: Budget) {
            settings.update { it.copy(budget = budget) }
        }

        private fun UsageRecordEntity.total() = UsageTotal(1, inputTokens, outputTokens, estimatedCostMicros)

        private companion object {
            const val RECENT_RUNS = 20
        }
    }
