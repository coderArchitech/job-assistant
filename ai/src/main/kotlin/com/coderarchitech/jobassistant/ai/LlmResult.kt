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
    ) : LlmResult<T>

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
}

/** Tokens one call used, as the provider reported them. Priced into a `UsageRecord` by T1.14. */
data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    /** Input tokens read from the prompt cache; part of, not added to, [inputTokens]. */
    val cachedInputTokens: Long = 0,
) {
    init {
        require(inputTokens >= 0 && outputTokens >= 0 && cachedInputTokens >= 0) { "Token counts cannot be negative" }
        require(cachedInputTokens <= inputTokens) { "Cached tokens cannot exceed input tokens" }
    }

    /** Shared values. */
    companion object {
        /** No tokens; for calls that failed before the provider counted any. */
        val NONE = TokenUsage(0, 0, 0)
    }
}
