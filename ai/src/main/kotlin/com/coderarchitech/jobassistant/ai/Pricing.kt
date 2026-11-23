package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.core.model.AiProvider

/**
 * What a model costs, in micro-US-dollars per million tokens. Numerically this equals the list price
 * in US dollars per million tokens times 1,000,000, so $3.00 per million is 3,000,000. Whole numbers
 * keep every sum exact.
 */
data class ModelPrice(
    val inputMicrosPerMillion: Long,
    val outputMicrosPerMillion: Long,
    /** Input read from the prompt cache. */
    val cacheReadMicrosPerMillion: Long,
    /** Input written to the prompt cache; Anthropic bills this above the base input rate. */
    val cacheWriteMicrosPerMillion: Long,
) {
    init {
        require(
            inputMicrosPerMillion >= 0 &&
                outputMicrosPerMillion >= 0 &&
                cacheReadMicrosPerMillion >= 0 &&
                cacheWriteMicrosPerMillion >= 0,
        ) { "Prices cannot be negative" }
    }

    /** The cost of [usage] in micro-US-dollars, rounded up so a sum never undercounts. */
    fun cost(usage: TokenUsage): Long =
        perMillion(usage.inputTokens, inputMicrosPerMillion) +
            perMillion(usage.outputTokens, outputMicrosPerMillion) +
            perMillion(usage.cacheReadInputTokens, cacheReadMicrosPerMillion) +
            perMillion(usage.cacheWriteInputTokens, cacheWriteMicrosPerMillion)

    /**
     * The most [inputTokens] of input and [outputTokens] of output could cost: every input token at the
     * dearer of the base and cache-write rates, since the prefix may be written to the cache.
     */
    fun worstCase(
        inputTokens: Long,
        outputTokens: Long,
    ): Long =
        perMillion(inputTokens, maxOf(inputMicrosPerMillion, cacheWriteMicrosPerMillion)) +
            perMillion(outputTokens, outputMicrosPerMillion)

    private fun perMillion(
        tokens: Long,
        price: Long,
    ): Long = (tokens * price + MILLION - 1) / MILLION

    private companion object {
        const val MILLION = 1_000_000L
    }
}

/**
 * Prices by provider and model id. The signed price table from the config host (task T3.09) fills it
 * and the user can edit it; until then [FallbackPricing] applies.
 */
fun interface PriceTable {
    /** The price of [model], or null when the table does not list it. */
    fun priceOf(
        provider: AiProvider,
        model: String,
    ): ModelPrice?
}

/** Used for any model the price table does not list, so an unknown model is over-counted, never free. */
object FallbackPricing {
    /**
     * Deliberately above the list price of any model the app offers, so budgets fail safe: $15 per
     * million input, $75 output, $1.50 cache reads, $18.75 cache writes.
     */
    val PRICE =
        ModelPrice(
            inputMicrosPerMillion = 15_000_000,
            outputMicrosPerMillion = 75_000_000,
            cacheReadMicrosPerMillion = 1_500_000,
            cacheWriteMicrosPerMillion = 18_750_000,
        )

    /** The listed price of [model], or [PRICE] when [PriceTable] has none. */
    fun PriceTable.priceOrFallback(
        provider: AiProvider,
        model: String,
    ): ModelPrice = priceOf(provider, model) ?: PRICE
}
