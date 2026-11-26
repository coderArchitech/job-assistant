package com.coderarchitech.jobassistant.portals.web.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PacerTest {
    @Test
    fun everyPauseIsBetweenOneAndAHalfAndFourSeconds() =
        runBlocking {
            val pauses = mutableListOf<Long>()
            val pacer = Pacer(Random(SEED)) { pauses += it }

            repeat(TIMES) { pacer.beforeAction() }

            assertTrue(pauses.all { it in Pacer.MIN_MS..Pacer.MAX_MS })
            // Random, not a fixed beat: the pauses spread across the range.
            assertTrue(pauses.min() < 2_000 && pauses.max() > 3_500)
        }

    private companion object {
        const val SEED = 42
        const val TIMES = 1_000
    }
}
