package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.ai.LlmProviderContract.Scenario
import com.coderarchitech.jobassistant.core.model.AiProvider
import java.time.Duration

/**
 * In-memory [LlmProvider] that answers as a [Scenario] describes. It decodes replies with the
 * request's schema, as the real providers do, so it exercises [OutputSchema] the same way.
 */
class FakeLlmProvider(
    private val scenario: Scenario,
    override val id: AiProvider = AiProvider.ANTHROPIC,
) : LlmProvider {
    /** Requests passed to [generate], in order. */
    val requests = mutableListOf<LlmRequest<*>>()

    override suspend fun validateKey(): KeyCheck =
        when (scenario) {
            Scenario.INVALID_KEY -> KeyCheck.Invalid
            Scenario.UNREACHABLE -> KeyCheck.NetworkError("Unable to resolve host")
            Scenario.RATE_LIMITED, Scenario.SERVER_ERROR -> KeyCheck.NetworkError("Provider unavailable")
            else -> KeyCheck.Valid
        }

    override suspend fun listModels(): ModelList =
        when (scenario) {
            Scenario.INVALID_KEY -> ModelList.InvalidKey
            Scenario.UNREACHABLE, Scenario.RATE_LIMITED, Scenario.SERVER_ERROR ->
                ModelList.NetworkError("Provider unavailable")
            else -> ModelList.Available(MODELS)
        }

    override suspend fun <T> generate(request: LlmRequest<T>): LlmResult<T> {
        requests += request
        return when (scenario) {
            Scenario.SUCCESS -> decode(request, LlmProviderContract.SUCCESS_REPLY)
            Scenario.MALFORMED_OUTPUT -> decode(request, LlmProviderContract.MALFORMED_REPLY)
            Scenario.REFUSAL -> LlmResult.Refused(USAGE, explanation = null)
            Scenario.TRUNCATED -> LlmResult.Truncated(USAGE)
            Scenario.INVALID_KEY -> LlmResult.InvalidKey
            Scenario.RATE_LIMITED ->
                LlmResult.RateLimited(Duration.ofSeconds(LlmProviderContract.RETRY_AFTER_SECONDS))
            Scenario.SERVER_ERROR ->
                LlmResult.ProviderError(LlmProviderContract.SERVER_ERROR_STATUS, "Internal server error")
            Scenario.UNREACHABLE -> LlmResult.NetworkError("Unable to resolve host")
        }
    }

    private fun <T> decode(
        request: LlmRequest<T>,
        text: String,
    ): LlmResult<T> =
        when (val decoded = request.schema.decode(text)) {
            is OutputSchema.Decoded.Value -> LlmResult.Success(decoded.value, USAGE, MODELS.first().id)
            is OutputSchema.Decoded.Mismatch -> LlmResult.InvalidOutput(text, decoded.problem, USAGE)
        }

    private companion object {
        val MODELS = listOf(ModelInfo("fake-small"), ModelInfo("fake-large", displayName = "Fake Large"))
        val USAGE = TokenUsage(inputTokens = 120, outputTokens = 20)
    }
}

/** Runs the shared contract against the fake, so the suite itself is known to be satisfiable. */
class FakeLlmProviderContractTest : LlmProviderContract() {
    override fun provider(scenario: Scenario): LlmProvider = FakeLlmProvider(scenario)
}
