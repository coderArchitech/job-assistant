package com.coderarchitech.jobassistant.core.model

/**
 * One model's price as the signed price table lists it (task T3.09), in micro-US-dollars per million tokens:
 * $3.00 per million is 3,000,000. The AI layer turns it into its own `ModelPrice`.
 */
data class ListedPrice(
    val provider: AiProvider,
    val model: String,
    val inputMicrosPerMillion: Long,
    val outputMicrosPerMillion: Long,
    val cacheReadMicrosPerMillion: Long,
    val cacheWriteMicrosPerMillion: Long,
) {
    init {
        require(model.isNotBlank()) { "A listed price names its model" }
        require(
            inputMicrosPerMillion >= 0 &&
                outputMicrosPerMillion >= 0 &&
                cacheReadMicrosPerMillion >= 0 &&
                cacheWriteMicrosPerMillion >= 0,
        ) { "Prices cannot be negative" }
    }

    /** True when [other] prices the same model. */
    fun isSameModel(other: ListedPrice): Boolean = provider == other.provider && model == other.model
}

/** The signed prices with the user's [edits] in place of the entries they cover, edits first. */
fun List<ListedPrice>.withEdits(edits: List<ListedPrice>): List<ListedPrice> =
    edits + filterNot { listed -> edits.any(listed::isSameModel) }
