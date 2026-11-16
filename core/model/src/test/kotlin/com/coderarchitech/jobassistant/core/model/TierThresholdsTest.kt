package com.coderarchitech.jobassistant.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TierThresholdsTest {
    @Test
    fun defaultThresholdsMatchThePrd() {
        val cases =
            listOf(
                100 to Tier.HIGH,
                75 to Tier.HIGH,
                74 to Tier.PARTIAL,
                50 to Tier.PARTIAL,
                49 to Tier.LOW,
                0 to Tier.LOW,
            )
        val thresholds = TierThresholds()
        for ((score, tier) in cases) assertEquals("score $score", tier, thresholds.tierFor(score))
    }

    @Test
    fun changedThresholdsReTierTheSameScore() {
        val strict = TierThresholds(high = 85, partial = 60)
        assertEquals(Tier.PARTIAL, strict.tierFor(80))
        assertEquals(Tier.LOW, strict.tierFor(55))
    }

    @Test
    fun invalidThresholdsAndScoresAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TierThresholds(high = 50, partial = 50) }
        assertThrows(IllegalArgumentException::class.java) { TierThresholds(high = 101, partial = 50) }
        assertThrows(IllegalArgumentException::class.java) { TierThresholds().tierFor(-1) }
        assertThrows(IllegalArgumentException::class.java) { TierThresholds().tierFor(101) }
    }
}
