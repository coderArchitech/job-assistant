package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.DatabaseTest
import com.coderarchitech.jobassistant.core.data.db.entity.toEntity
import com.coderarchitech.jobassistant.core.data.db.sampleRun
import com.coderarchitech.jobassistant.core.data.db.sampleUsage
import com.coderarchitech.jobassistant.core.data.settings.SettingsRepository
import com.coderarchitech.jobassistant.core.model.AppSettings
import com.coderarchitech.jobassistant.core.model.Budget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RoomUsageReportTest : DatabaseTest() {
    private val kolkata = ZoneId.of("Asia/Kolkata")

    /** 26 Nov 2026, 10:00 in Kolkata. */
    private val clock = Clock.fixed(Instant.parse("2026-11-26T04:30:00Z"), kolkata)

    private class MemorySettings : SettingsRepository {
        val state = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = state

        override suspend fun update(transform: (AppSettings) -> AppSettings) =
            transform(state.value).also {
                state.value =
                    it
            }

        override suspend fun reset() {
            state.value = AppSettings()
        }
    }

    private val settings = MemorySettings()

    private fun report() = RoomUsageReport(db.usageRecordDao(), settings, clock)

    private suspend fun run(startedAt: Instant) = db.runDao().insert(sampleRun(startedAt = startedAt).toEntity())

    private suspend fun spend(
        runId: Long?,
        micros: Long,
        at: String,
    ) = db.usageRecordDao().insert(sampleUsage(runId, micros, Instant.parse(at)).toEntity())

    @Test
    fun usageIsAddedUpByLocalDayByRunAndForTheMonth() =
        runBlocking {
            val earlier = run(Instant.parse("2026-11-25T03:00:00Z"))
            val latest = run(Instant.parse("2026-11-26T03:00:00Z"))
            spend(null, 999_000, "2026-10-31T10:00:00Z") // Last month: not counted.
            spend(earlier, 100_000, "2026-11-25T03:00:00Z")
            spend(earlier, 50_000, "2026-11-25T19:00:00Z") // 26 Nov 00:30 in Kolkata.
            spend(latest, 200_000, "2026-11-26T03:00:00Z")
            spend(latest, 25_000, "2026-11-26T03:05:00Z")

            val overview = report().observe().first()

            assertEquals(375_000, overview.thisMonth.costMicros)
            assertEquals(4, overview.thisMonth.calls)
            assertEquals("today in the device's zone", 275_000, overview.today.costMicros)
            assertEquals(
                listOf(LocalDate.of(2026, 11, 26) to 275_000L, LocalDate.of(2026, 11, 25) to 100_000L),
                overview.days.map { it.first to it.second.costMicros },
            )
            assertEquals(listOf(latest, earlier), overview.runs.map { it.runId })
            assertEquals(listOf(225_000L, 150_000L), overview.runs.map { it.total.costMicros })
            assertEquals(Instant.parse("2026-11-26T03:00:00Z"), overview.runs.first().startedAt)
            assertFalse(overview.capReached)
            assertEquals(LocalDate.of(2026, 12, 1), overview.resetsOn)
        }

    @Test
    fun aNewBudgetIsSavedAndTheCapIsReachedAtIt() =
        runBlocking {
            spend(null, 2_000_000, "2026-11-20T10:00:00Z")
            val report = report()

            report.setBudget(Budget(monthlyCapMicros = 2_000_000))

            assertEquals(2_000_000, settings.state.value.budget.monthlyCapMicros)
            assertTrue(report.observe().first().capReached)
        }
}
