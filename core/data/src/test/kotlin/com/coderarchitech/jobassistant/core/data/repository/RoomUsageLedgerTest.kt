package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.DatabaseTest
import com.coderarchitech.jobassistant.core.data.db.T0
import com.coderarchitech.jobassistant.core.data.db.sampleUsage
import com.coderarchitech.jobassistant.core.model.AiTask
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomUsageLedgerTest : DatabaseTest() {
    private val ledger get() = RoomUsageLedger(db.usageRecordDao())

    @Test
    fun recordedUsageIsSummedFromTheStartInstant() =
        runTest {
            ledger.record(sampleUsage(null, costMicros = 700_000, recordedAt = T0.minusSeconds(1)))
            ledger.record(sampleUsage(null, costMicros = 300_000, recordedAt = T0))
            ledger.record(sampleUsage(null, costMicros = 200_000, recordedAt = T0.plusSeconds(5)))

            assertEquals(500_000L, ledger.spentMicrosSince(T0))
            assertEquals(1_200_000L, ledger.spentMicrosSince(T0.minusSeconds(10)))
        }

    @Test
    fun spendCanBeLimitedToOneTask() =
        runTest {
            ledger.record(sampleUsage(null, costMicros = 100, recordedAt = T0).copy(task = AiTask.BROWSER_STEP))
            ledger.record(sampleUsage(null, costMicros = 900, recordedAt = T0).copy(task = AiTask.SCORE))

            assertEquals(100L, ledger.spentMicrosSince(T0, AiTask.BROWSER_STEP))
            assertEquals(1_000L, ledger.spentMicrosSince(T0))
        }

    @Test
    fun theCacheWriteCountIsStoredWithTheRow() =
        runTest {
            ledger.record(sampleUsage(null, costMicros = 1, recordedAt = T0).copy(cacheWriteTokens = 1_234))

            db.openHelper.readableDatabase.query("SELECT cacheWriteTokens FROM usage_record").use {
                assertEquals(1, it.count)
                it.moveToFirst()
                assertEquals(1_234L, it.getLong(0))
            }
        }

    @Test
    fun nothingRecordedMeansNothingSpent() =
        runTest {
            assertEquals(0L, ledger.spentMicrosSince(T0))
        }
}
