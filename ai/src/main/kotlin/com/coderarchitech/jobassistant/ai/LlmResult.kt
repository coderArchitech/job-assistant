package com.coderarchitech.jobassistant.ai

import java.time.Duration

/**
 * Outcome of [LlmProvider.generate]. Check the type before using a value: a provider never returns
 * half-decoded content (design: check `stop_reason` or `finish_reason` before reading).
 */
sealed interface LlmResult<out T> {
    /** The reply decoded into [value]. */
    data class Success<T>(
        val value: T,
        val usage: TokenUsage,
        /** The model that answered, as the provider reported it. */
        val model: String,
    ) : LlmResult<T> {
        // The value can hold profile-derived text (a cover letter, a parsed resume); keep it out of logs.
        override fun toString(): String = "Success(model=$model, usage=$usage)"
    }

    /** Every outcome that is not a decoded value. */
    sealed interface Failure : LlmResult<Nothing>

    /** The model declined to answer (Anthropic `refusal`, OpenAI refusal field). */
    data class Refused(
        val usage: TokenUsage,
        /** The provider's explanation, when it gives one. */
        val explanation: String?,
    ) : Failure

    /** The reply hit `maxOutputTokens` before it finished. */
    data class Truncated(
        val usage: TokenUsage,
    ) : Failure

    /**
     * The reply did not match the schema. [rawText] is kept for debugging only; it is model output
     * about a job or profile, never a secret, but it is still not logged in release builds.
     */
    data class InvalidOutput(
        val rawText: String,
        val problem: String,
        val usage: TokenUsage,
    ) : Failure {
        override fun toString(): String =
            "InvalidOutput(problem=$problem, rawText=${rawText.length} chars, usage=$usage)"
    }

    /** The provider rejected the key (HTTP 401 or 403); the agent pauses until the user fixes it. */
    data object InvalidKey : Failure

    /** Still rate limited (HTTP 429 or 529) after the network layer's retries. */
    data class RateLimited(
        /** From `retry-after`, when the provider sent it. */
        val retryAfter: Duration?,
    ) : Failure

    /** Any other HTTP failure, such as a 400 or a 5xx that outlasted the retries. */
    data class ProviderError(
        val httpStatus: Int,
        val message: String,
    ) : Failure

    /** The provider could not be reached. */
    data class NetworkError(
        val message: String,
    ) : Failure

    /** The call was never sent, because a guard stopped it. */
    data class Blocked(
        val reason: BlockReason,
    ) : Failure
}

/** Why a guard stopped a call before it left the phone. */
enum class BlockReason {
    /** Month-to-date spend plus this call's worst case would pass the budget (T1.14). */
    BUDGET_EXCEEDED,

    /** The request contained a stored secret or the last OTP (T1.15). */
    SECRET_IN_PROMPT,

    /** The vault could not be read, so the request could not be checked for secrets (T1.15). Nothing is sent. */
    SECRET_CHECK_UNAVAILABLE,
}

/**
 * Tokens one call used, split by how each kind is billed, so T1.14 can price them. The three input
 * counts do not overlap. Providers normalize into this shape: Anthropic reports the three directly
 * (`input_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`); OpenAI's
 * `prompt_tokens` includes `cached_tokens`, so its uncached input is the difference.
 */
data class TokenUsage(
    /** Input billed at the base rate (not read from or written to the cache). */
    val inputTokens: Long,
    val outputTokens: Long,
    /** Input read from the prompt cache, billed below the base rate. */
    val cacheReadInputTokens: Long = 0,
    /** Input written to the prompt cache, billed above the base rate (Anthropic only). */
    val cacheWriteInputTokens: Long = 0,
) {
    init {
        require(
            inputTokens >= 0 && outputTokens >= 0 && cacheReadInputTokens >= 0 && cacheWriteInputTokens >= 0,
        ) { "Token counts cannot be negative" }
    }

    /** Every input token, however it was billed. */
    val totalInputTokens: Long get() = inputTokens + cacheReadInputTokens + cacheWriteInputTokens

    /** Shared values. */
    companion object {
        /** No tokens; for calls that failed before the provider counted any. */
        val NONE = TokenUsage(0, 0)
    }
}
