package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.ListedPrice

/**
 * A [PriceTable] over the signed price table's entries (task T3.09). [listed] is read on every lookup, so a
 * newer table is used as soon as it is loaded.
 */
class ListedPriceTable(
    private val listed: () -> List<ListedPrice>,
) : PriceTable {
    override fun priceOf(
        provider: AiProvider,
        model: String,
    ): ModelPrice? =
        listed()
            .firstOrNull { it.provider == provider && it.model == model }
            ?.let {
                ModelPrice(
                    inputMicrosPerMillion = it.inputMicrosPerMillion,
                    outputMicrosPerMillion = it.outputMicrosPerMillion,
                    cacheReadMicrosPerMillion = it.cacheReadMicrosPerMillion,
                    cacheWriteMicrosPerMillion = it.cacheWriteMicrosPerMillion,
                )
            }
}
