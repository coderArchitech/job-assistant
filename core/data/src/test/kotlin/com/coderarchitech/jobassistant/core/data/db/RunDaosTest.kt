package com.coderarchitech.jobassistant.core.data.db

import com.coderarchitech.jobassistant.core.data.db.entity.toEntity
import com.coderarchitech.jobassistant.core.data.db.entity.toModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RunDaosTest : DatabaseTest() {
    @Test
    fun runRoundTripsWithItsCountsAndErrors() =
        runTest {
            val id = db.runDao().insert(sampleRun().toEntity())

            assertEquals(sampleRun().copy(id = id), db.runDao().getById(id)?.toModel())
        }

    @Test
    fun recentRunsComeNewestFirstAndRespectTheLimit() =
        runTest {
            val oldest = db.runDao().insert(sampleRun(startedAt = T0).toEntity())
            val middle = db.runDao().insert(sampleRun(startedAt = T0.plusSeconds(60)).toEntity())
            val newest = db.runDao().insert(sampleRun(startedAt = T0.plusSeconds(120)).toEntity())

            assertEquals(
                listOf(newest, middle),
                db
                    .runDao()
                    .observeRecent(limit = 2)
                    .first()
                    .map { it.id },
            )
            assertEquals(
                3,
                db
                    .runDao()
                    .observeRecent(limit = 10)
                    .first()
                    .size,
            )
            assertEquals(oldest, db.runDao().getById(oldest)?.id)
        }

    @Test
    fun usageRoundTripsAndIsLinkedToItsRun() =
        runTest {
            val run = db.runDao().insert(sampleRun().toEntity())
            val id = db.usageRecordDao().insert(sampleUsage(run, costMicros = 1_500).toEntity())

            assertEquals(
                sampleUsage(run, costMicros = 1_500).copy(id = id),
                db
                    .usageRecordDao()
                    .getForRun(run)
                    .single()
                    .toModel(),
            )
        }

    @Test
    fun costSinceCountsOnlyCallsFromThatMomentOn() =
        runTest {
            val dao = db.usageRecordDao()
            dao.insert(sampleUsage(null, costMicros = 700_000, recordedAt = T0.minusSeconds(1)).toEntity())
            dao.insert(sampleUsage(null, costMicros = 300_000, recordedAt = T0).toEntity())
            dao.insert(sampleUsage(null, costMicros = 200_000, recordedAt = T0.plusSeconds(5)).toEntity())

            assertEquals(500_000L, dao.sumCostMicrosSince(T0))
            assertEquals(1_200_000L, dao.sumCostMicrosSince(T0.minusSeconds(10)))
        }

    @Test
    fun costIsZeroWhenNothingWasSpent() =
        runTest {
            assertEquals(0L, db.usageRecordDao().sumCostMicrosSince(T0))
            assertEquals(0L, db.usageRecordDao().observeCostMicrosForRun(runId = 1).first())
        }

    @Test
    fun costForARunSumsOnlyThatRun() =
        runTest {
            val one = db.runDao().insert(sampleRun().toEntity())
            val two = db.runDao().insert(sampleRun().toEntity())
            db.usageRecordDao().insert(sampleUsage(one, costMicros = 100).toEntity())
            db.usageRecordDao().insert(sampleUsage(one, costMicros = 250).toEntity())
            db.usageRecordDao().insert(sampleUsage(two, costMicros = 9_000).toEntity())

            assertEquals(350L, db.usageRecordDao().observeCostMicrosForRun(one).first())
        }

    @Test
    fun usageSurvivesItsRunBeingDeletedAndKeepsItsCost() =
        runTest {
            val run = db.runDao().insert(sampleRun().toEntity())
            db.usageRecordDao().insert(sampleUsage(run, costMicros = 400).toEntity())

            db.openHelper.writableDatabase.execSQL("DELETE FROM run WHERE id = $run")

            assertEquals(400L, db.usageRecordDao().sumCostMicrosSince(T0))
            assertNull(db.usageRecordDao().getForRun(run).firstOrNull())
        }

    @Test
    fun dailyReportRoundTripsAndIsReplacedPerDate() =
        runTest {
            val day = LocalDate.of(2026, 11, 19)
            db.dailyReportDao().upsert(sampleReport(day).toEntity())
            assertEquals(sampleReport(day), db.dailyReportDao().get(day)?.toModel())

            db.dailyReportDao().upsert(sampleReport(day).copy(replies = 5).toEntity())
            assertEquals(5, db.dailyReportDao().get(day)?.replies)
            assertEquals(
                1,
                db
                    .dailyReportDao()
                    .observeRecent(limit = 30)
                    .first()
                    .size,
            )
            assertNull(db.dailyReportDao().get(day.plusDays(1)))
        }

    @Test
    fun recentReportsComeNewestFirst() =
        runTest {
            val first = LocalDate.of(2026, 11, 17)
            listOf(0L, 1L, 2L).forEach { db.dailyReportDao().upsert(sampleReport(first.plusDays(it)).toEntity()) }

            assertEquals(
                listOf(first.plusDays(2), first.plusDays(1)),
                db
                    .dailyReportDao()
                    .observeRecent(limit = 2)
                    .first()
                    .map { it.date },
            )
        }
}
