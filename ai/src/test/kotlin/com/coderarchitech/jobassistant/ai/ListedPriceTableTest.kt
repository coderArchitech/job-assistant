package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.ai.FallbackPricing.priceOrFallback
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.ListedPrice
import org.junit.Assert.assertEquals
import org.junit.Test

class ListedPriceTableTest {
    @Test
    fun listedModelsArePricedAndOthersFallBack() {
        var listed = listOf(ListedPrice(AiProvider.ANTHROPIC, "claude-a", 3_000_000, 15_000_000, 300_000, 3_750_000))
        val table = ListedPriceTable { listed }

        assertEquals(
            ModelPrice(3_000_000, 15_000_000, 300_000, 3_750_000),
            table.priceOf(AiProvider.ANTHROPIC, "claude-a"),
        )
        // The same model id under another provider is a different model.
        assertEquals(FallbackPricing.PRICE, table.priceOrFallback(AiProvider.OPENAI, "claude-a"))

        // A newer table is used at once.
        listed = emptyList()
        assertEquals(FallbackPricing.PRICE, table.priceOrFallback(AiProvider.ANTHROPIC, "claude-a"))
    }
}
