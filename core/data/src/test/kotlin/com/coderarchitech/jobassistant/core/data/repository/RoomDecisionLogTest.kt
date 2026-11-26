package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.DatabaseTest
import com.coderarchitech.jobassistant.core.data.db.entity.toEntity
import com.coderarchitech.jobassistant.core.data.db.sampleJob
import com.coderarchitech.jobassistant.core.model.DecisionLog
import com.coderarchitech.jobassistant.core.model.Verdict
import com.coderarchitech.jobassistant.core.model.WorkMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class RoomDecisionLogTest : DatabaseTest() {
    private var now = Instant.parse("2026-11-26T10:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = now.also { now = now.plusSeconds(1) }
        }

    private fun log() = RoomDecisionLog(db.decisionDao(), clock)

    private suspend fun storeJob(
        externalId: String,
        hash: String,
    ): Long =
        db.jobDao().insert(
            sampleJob(
                externalId = externalId,
                canonicalUrl = "https://jobs.test/$externalId",
                dedupeHash = hash,
            ).toEntity(),
        )

    @Test
    fun theLatestDecisionPerJobComesBackWithItsJobNewestFirst() =
        runBlocking {
            val first = storeJob("1", "h1")
            val second = storeJob("2", "h2")
            val log = log()
            log.record(first, Verdict.APPROVE, "  Good fit  ")
            log.record(second, Verdict.REJECT, null)
            log.record(first, Verdict.REJECT, "Changed my mind")

            val examples = log.latestExamples(10)

            assertEquals(listOf(Verdict.REJECT, Verdict.REJECT), examples.map { it.verdict })
            assertEquals("Changed my mind", examples[0].reason)
            assertNull(examples[1].reason)
            assertEquals("Android Engineer", examples[0].title)
            assertEquals("Acme", examples[0].company)
            assertEquals("Bengaluru", examples[0].location)
            assertEquals(WorkMode.HYBRID, examples[0].workMode)
        }

    @Test
    fun theLimitAndTheReasonRulesHold() =
        runBlocking {
            val log = log()
            val ids = (1..12).map { storeJob("$it", "h$it") }
            ids.forEach { log.record(it, Verdict.APPROVE, " ") }
            val long = log.record(ids.first(), Verdict.REJECT, "x".repeat(500))

            assertEquals(10, log.latestExamples(10).size)
            assertNull("a blank reason is none", db.decisionDao().getLatestForJob(ids[5])!!.reason)
            assertEquals(DecisionLog.MAX_REASON_LENGTH, long.reason!!.length)
        }
}
