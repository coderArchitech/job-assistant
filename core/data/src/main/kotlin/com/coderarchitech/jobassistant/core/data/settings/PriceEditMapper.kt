package com.coderarchitech.jobassistant.core.data.settings

import com.coderarchitech.jobassistant.core.data.settings.proto.PriceEditProto
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AppSettings
import com.coderarchitech.jobassistant.core.model.ListedPrice

/** Stores one of the user's model prices (task T3.09). */
internal fun ListedPrice.toPriceEditProto(): PriceEditProto =
    PriceEditProto
        .newBuilder()
        .setProvider(provider.name)
        .setModel(model)
        .setInputMicrosPerMillion(inputMicrosPerMillion)
        .setOutputMicrosPerMillion(outputMicrosPerMillion)
        .setCacheReadMicrosPerMillion(cacheReadMicrosPerMillion)
        .setCacheWriteMicrosPerMillion(cacheWriteMicrosPerMillion)
        .build()

/** Keeps each valid stored edit, the first one per model, up to the limit; drops the rest. */
internal fun List<PriceEditProto>.toPriceEdits(): List<ListedPrice> {
    val edits = mapNotNull { it.toPriceEditOrNull() ?: null.also { logFallback("priceEdits") } }
    return edits
        .distinctBy { it.provider to it.model }
        .take(AppSettings.MAX_PRICE_EDITS)
        .also { if (it.size < edits.size) logFallback("priceEdits") }
}

private fun PriceEditProto.toPriceEditOrNull(): ListedPrice? {
    val known = AiProvider.entries.firstOrNull { it.name == provider }
    val complete =
        listOf(
            hasInputMicrosPerMillion(),
            hasOutputMicrosPerMillion(),
            hasCacheReadMicrosPerMillion(),
            hasCacheWriteMicrosPerMillion(),
        ).all { it }
    val nonNegative =
        listOf(inputMicrosPerMillion, outputMicrosPerMillion, cacheReadMicrosPerMillion, cacheWriteMicrosPerMillion)
            .all { it >= 0 }
    val price =
        if (known != null && complete && nonNegative) {
            ListedPrice(
                provider = known,
                model = model,
                inputMicrosPerMillion = inputMicrosPerMillion,
                outputMicrosPerMillion = outputMicrosPerMillion,
                cacheReadMicrosPerMillion = cacheReadMicrosPerMillion,
                cacheWriteMicrosPerMillion = cacheWriteMicrosPerMillion,
            )
        } else {
            null
        }
    return price?.takeIf(AppSettings::isValidPriceEdit)
}
