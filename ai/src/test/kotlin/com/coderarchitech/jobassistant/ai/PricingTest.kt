package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.ai.FallbackPricing.priceOrFallback
import com.coderarchitech.jobassistant.core.model.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingTest {
    private val price = ModelPrice(3_000_000, 15_000_000, 300_000, 3_750_000)

    @Test
    fun costAddsEveryTokenKindAtItsOwnRate() {
        val usage =
            TokenUsage(
                inputTokens = 1_000_000,
                outputTokens = 1_000_000,
                cacheReadInputTokens = 1_000_000,
                cacheWriteInputTokens = 1_000_000,
            )

        // $3 + $15 + $0.30 + $3.75 = $22.05
        assertEquals(22_050_000L, price.cost(usage))
    }

    @Test
    fun costRoundsUpSoASumNeverUndercounts() {
        // One token of input is 3 micro-dollars; one cache-read token is 0.3, which rounds up to 1.
        assertEquals(3L, price.cost(TokenUsage(1, 0)))
        assertEquals(1L, price.cost(TokenUsage(0, 0, cacheReadInputTokens = 1)))
        assertEquals(0L, price.cost(TokenUsage.NONE))
    }

    @Test
    fun theWorstCaseCountsInputAtTheDearerOfBaseAndCacheWriteRates() {
        // 1,000,000 input at the cache-write rate ($3.75) plus 1,000,000 output ($15).
        assertEquals(18_750_000L, price.worstCase(1_000_000, 1_000_000))
        // When base input is dearer than cache writes, the base rate applies.
        val cheapWrites = price.copy(cacheWriteMicrosPerMillion = 1_000_000)
        assertEquals(18_000_000L, cheapWrites.worstCase(1_000_000, 1_000_000))
    }

    @Test
    fun anUnlistedModelFallsBackToAPriceAboveAnyListedOne() {
        val table = PriceTable { _, model -> price.takeIf { model == "known" } }

        assertEquals(price, table.priceOrFallback(AiProvider.ANTHROPIC, "known"))
        val fallback = table.priceOrFallback(AiProvider.OPENAI, "something-new")
        assertEquals(FallbackPricing.PRICE, fallback)
        assertTrue(fallback.inputMicrosPerMillion > price.inputMicrosPerMillion)
        assertTrue(fallback.outputMicrosPerMillion > price.outputMicrosPerMillion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePricesAreRejected() {
        ModelPrice(-1, 0, 0, 0)
    }
}
