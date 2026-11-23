package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.DatabaseTest
import com.coderarchitech.jobassistant.core.model.CapLimit
import com.coderarchitech.jobassistant.core.model.CapSlot
import com.coderarchitech.jobassistant.core.model.RemainingSlots
import com.coderarchitech.jobassistant.core.model.SlotResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RoomCapSlotsTest : DatabaseTest() {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private var now = Instant.parse("2026-03-01T10:00:00Z") // 15:30 in Kolkata

    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = kolkata

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = now
        }

    private fun slots(database: com.coderarchitech.jobassistant.core.data.db.AgentDatabase = db) =
        RoomCapSlots(database, clock)

    private suspend fun RoomCapSlots.takeOne(
        portal: Long = 1,
        portalCap: Int = 10,
        overallCap: Int = 25,
    ) = take(portal, portalCap, overallCap)

    /** Task T3.20 Done when: a test cannot take an eleventh slot on one portal in a day. */
    @Test
    fun anEleventhSlotOnOnePortalCannotBeTaken() =
        runBlocking {
            val caps = slots()

            repeat(10) { assertTrue("slot ${it + 1}", caps.takeOne() is SlotResult.Taken) }

            assertEquals(SlotResult.Denied(CapLimit.PORTAL), caps.takeOne())
            assertEquals(RemainingSlots(portal = 0, overall = 15), caps.remaining(1, 10, 25))
        }

    @Test
    fun theOverallCapStopsAllPortalsTogether() =
        runBlocking {
            val caps = slots()
            repeat(3) { caps.takeOne(portal = 1, portalCap = 10, overallCap = 5) }
            repeat(2) { caps.takeOne(portal = 2, portalCap = 10, overallCap = 5) }

            assertEquals(SlotResult.Denied(CapLimit.OVERALL), caps.takeOne(portal = 3, portalCap = 10, overallCap = 5))
            assertEquals(SlotResult.Denied(CapLimit.OVERALL), caps.takeOne(portal = 1, portalCap = 10, overallCap = 5))
        }

    @Test
    fun aDeniedAttemptOnTheOverallCapDoesNotUseUpThePortalsSlot() =
        runBlocking {
            val caps = slots()
            repeat(5) { caps.takeOne(portal = 1, portalCap = 10, overallCap = 5) }

            caps.takeOne(portal = 2, portalCap = 3, overallCap = 5) // refused: overall is used up

            assertEquals(3, caps.remaining(2, 3, overallCap = 100).portal)
        }

    @Test
    fun eachPortalHasItsOwnCap() =
        runBlocking {
            val caps = slots()
            repeat(2) { caps.takeOne(portal = 1, portalCap = 2) }

            assertEquals(SlotResult.Denied(CapLimit.PORTAL), caps.takeOne(portal = 1, portalCap = 2))
            assertTrue(caps.takeOne(portal = 2, portalCap = 2) is SlotResult.Taken)
        }

    @Test
    fun aCapOfZeroTurnsApplyingOff() =
        runBlocking {
            val caps = slots()

            assertEquals(SlotResult.Denied(CapLimit.PORTAL), caps.takeOne(portalCap = 0))
            assertEquals(SlotResult.Denied(CapLimit.OVERALL), caps.takeOne(overallCap = 0))
            assertEquals(SlotResult.Denied(CapLimit.PORTAL), caps.takeOne(portalCap = -3))
            assertEquals("nothing was written", RemainingSlots(10, 25), caps.remaining(1, 10, 25))
        }

    @Test
    fun aCapLoweredDuringTheDayAppliesAtOnce() =
        runBlocking {
            val caps = slots()
            repeat(4) { caps.takeOne(portalCap = 10) }

            assertEquals(SlotResult.Denied(CapLimit.PORTAL), caps.takeOne(portalCap = 4))
            assertEquals(0, caps.remaining(1, portalCap = 3, overallCap = 25).portal)
        }

    @Test
    fun slotsStartAgainAtLocalMidnightNotUtcMidnight() =
        runBlocking {
            val caps = slots()
            repeat(10) { caps.takeOne() }
            assertEquals(SlotResult.Denied(CapLimit.PORTAL), caps.takeOne())

            // 18:29 UTC is 23:59 in Kolkata: still the same local day.
            now = Instant.parse("2026-03-01T18:29:00Z")
            assertEquals(SlotResult.Denied(CapLimit.PORTAL), caps.takeOne())

            // 18:31 UTC is 00:01 in Kolkata on 2 March: a new day.
            now = Instant.parse("2026-03-01T18:31:00Z")
            val taken = caps.takeOne() as SlotResult.Taken
            assertEquals(LocalDate.of(2026, 3, 2), taken.slot.day)
            assertEquals(RemainingSlots(9, 24), caps.remaining(1, 10, 25))
        }

    @Test
    fun aReleasedSlotCanBeTakenAgain() =
        runBlocking {
            val caps = slots()
            val taken = caps.takeOne(portalCap = 1) as SlotResult.Taken
            assertEquals(SlotResult.Denied(CapLimit.PORTAL), caps.takeOne(portalCap = 1))

            caps.release(taken.slot)

            assertTrue(caps.takeOne(portalCap = 1) is SlotResult.Taken)
            assertEquals(RemainingSlots(0, 24), caps.remaining(1, 1, 25))
        }

    @Test
    fun releasingNeverGoesBelowZero() =
        runBlocking {
            val caps = slots()
            val phantom = CapSlot(portalId = 1, day = LocalDate.of(2026, 3, 1))

            caps.release(phantom)
            caps.release(phantom)

            assertEquals(RemainingSlots(10, 25), caps.remaining(1, 10, 25))
        }

    @Test
    fun countsOlderThanAWeekAreTrimmedAndRecentOnesKept() =
        runBlocking {
            val caps = slots()
            repeat(3) { caps.takeOne() }
            now = Instant.parse("2026-03-05T10:00:00Z")
            caps.takeOne() // four days later: the first day is still kept

            now = Instant.parse("2026-03-01T10:00:00Z")
            assertEquals(RemainingSlots(7, 22), caps.remaining(1, 10, 25))

            now = Instant.parse("2026-03-20T10:00:00Z")
            caps.takeOne() // trims everything older than a week

            now = Instant.parse("2026-03-01T10:00:00Z")
            assertEquals(RemainingSlots(10, 25), caps.remaining(1, 10, 25))
        }

    /** Several sessions racing for the last slots on separate connections must never take more than the cap. */
    @Test
    fun racingSessionsCannotTakeMoreThanTheCap() =
        runBlocking {
            withFileDatabase { raceDb ->
                val caps = slots(raceDb)
                repeat(ROUNDS) { round ->
                    val portal = round + 1L
                    val gate = CompletableDeferred<Unit>()
                    val results =
                        List(RACERS) {
                            async(Dispatchers.Default) {
                                gate.await()
                                caps.takeOne(portal = portal, portalCap = PORTAL_CAP, overallCap = 1_000)
                            }
                        }.also { gate.complete(Unit) }.awaitAll()

                    assertEquals("round $round", PORTAL_CAP, results.count { it is SlotResult.Taken })
                    assertEquals(
                        RemainingSlots(0, 1_000 - PORTAL_CAP * (round + 1)),
                        caps.remaining(portal, PORTAL_CAP, 1_000),
                    )
                }
            }
        }

    @Test
    fun racingSessionsOnDifferentPortalsRespectTheOverallCap() =
        runBlocking {
            withFileDatabase { raceDb ->
                val caps = slots(raceDb)
                val gate = CompletableDeferred<Unit>()
                val results =
                    List(RACERS * 2) {
                        async(Dispatchers.Default) {
                            gate.await()
                            caps.takeOne(portal = (it % 4) + 1L, portalCap = 100, overallCap = OVERALL_CAP)
                        }
                    }.also { gate.complete(Unit) }.awaitAll()

                assertEquals(OVERALL_CAP, results.count { it is SlotResult.Taken })
                assertEquals(0, caps.remaining(1, 100, OVERALL_CAP).overall)
            }
        }

    private companion object {
        const val ROUNDS = 10
        const val RACERS = 8
        const val PORTAL_CAP = 3
        const val OVERALL_CAP = 5
    }
}
