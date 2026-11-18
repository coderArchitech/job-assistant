package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.core.model.AiProvider

/**
 * One AI provider (Anthropic or OpenAI) behind the user's own key. The pipeline only talks to this
 * interface, so it never knows which provider is active (design: AI provider layer).
 *
 * Implementations never throw for provider or network failures: every outcome is a typed result.
 * Only coroutine cancellation propagates.
 */
interface LlmProvider {
    /** Which provider this is. */
    val id: AiProvider

    /** Checks the stored key with a call that costs no tokens. */
    suspend fun validateKey(): KeyCheck

    /** The models this key can use, for the per-task model picker. */
    suspend fun listModels(): ModelList

    /** Runs one request and decodes the reply into [T] using the request's output schema. */
    suspend fun <T> generate(request: LlmRequest<T>): LlmResult<T>
}

/** Result of [LlmProvider.validateKey]. */
sealed interface KeyCheck {
    /** The provider accepted the key. */
    data object Valid : KeyCheck

    /** The provider rejected the key (HTTP 401 or 403). */
    data object Invalid : KeyCheck

    /** The provider could not be reached or failed; the key is unknown. Try again later. */
    data class NetworkError(
        val message: String,
    ) : KeyCheck
}

/** Result of [LlmProvider.listModels]. */
sealed interface ModelList {
    /** The models the key can use, in the provider's order. */
    data class Available(
        val models: List<ModelInfo>,
    ) : ModelList

    /** The provider rejected the key. */
    data object InvalidKey : ModelList

    /** The provider could not be reached or failed. */
    data class NetworkError(
        val message: String,
    ) : ModelList
}

/** One model in the provider's live list. Limits are null when the provider does not report them. */
data class ModelInfo(
    val id: String,
    val displayName: String? = null,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
) {
    init {
        require(id.isNotBlank()) { "A model id cannot be blank" }
        require(contextWindowTokens == null || contextWindowTokens > 0) { "Context window must be positive" }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "Max output tokens must be positive" }
    }
}
