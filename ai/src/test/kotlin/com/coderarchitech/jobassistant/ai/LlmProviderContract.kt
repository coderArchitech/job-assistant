package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.core.model.AiTask
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior every [LlmProvider] must have. The fake provider runs it now; the Anthropic (T1.12) and
 * OpenAI (T1.13) providers extend it with MockWebServer and recorded fixtures, so all three agree
 * on what each provider outcome means.
 *
 * A subclass builds a provider that behaves as the given [Scenario] describes. For [Scenario.SUCCESS]
 * the provider's reply text must be [SUCCESS_REPLY].
 */
abstract class LlmProviderContract {
    /** Server behaviors a provider under test must be able to simulate. */
    enum class Scenario {
        /** Key accepted; models listed; the reply is [SUCCESS_REPLY]. */
        SUCCESS,

        /** HTTP 401 on every call. */
        INVALID_KEY,

        /** HTTP 429 with `retry-after: 30` on every call, so retries run out. */
        RATE_LIMITED,

        /** HTTP 500 on every call, so retries run out. */
        SERVER_ERROR,

        /** No server answers. */
        UNREACHABLE,

        /** The model refuses (Anthropic `stop_reason: refusal`, OpenAI `refusal`). */
        REFUSAL,

        /** The reply stops at the token limit (`max_tokens`, `length`). */
        TRUNCATED,

        /** The reply text is [MALFORMED_REPLY], which is not valid JSON for [Verdict]. */
        MALFORMED_OUTPUT,
    }

    /** Builds the provider under test for one scenario. */
    protected abstract fun provider(scenario: Scenario): LlmProvider

    @Test
    fun validKeyIsValid() =
        runTest {
            assertEquals(KeyCheck.Valid, provider(Scenario.SUCCESS).validateKey())
        }

    @Test
    fun rejectedKeyIsInvalid() =
        runTest {
            assertEquals(KeyCheck.Invalid, provider(Scenario.INVALID_KEY).validateKey())
        }

    @Test
    fun unreachableProviderIsANetworkErrorNotAVerdictOnTheKey() =
        runTest {
            assertTrue(provider(Scenario.UNREACHABLE).validateKey() is KeyCheck.NetworkError)
        }

    @Test
    fun modelListHasUniqueNonBlankIds() =
        runTest {
            val list = provider(Scenario.SUCCESS).listModels()
            assertTrue("Expected models, got $list", list is ModelList.Available)
            val ids = (list as ModelList.Available).models.map { it.id }
            assertTrue("The list is empty", ids.isNotEmpty())
            assertEquals("Model ids repeat: $ids", ids.size, ids.toSet().size)
        }

    @Test
    fun modelListWithARejectedKeyIsInvalidKey() =
        runTest {
            assertEquals(ModelList.InvalidKey, provider(Scenario.INVALID_KEY).listModels())
        }

    @Test
    fun aMatchingReplyDecodesIntoTheType() =
        runTest {
            val result = provider(Scenario.SUCCESS).generate(request())
            assertTrue("Expected success, got $result", result is LlmResult.Success)
            result as LlmResult.Success
            assertEquals(Verdict(score = 82, reason = "Kotlin and Compose match"), result.value)
            assertTrue("The model is not reported", result.model.isNotBlank())
            assertTrue("No input tokens counted", result.usage.totalInputTokens > 0)
            assertTrue("No output tokens counted", result.usage.outputTokens > 0)
        }

    @Test
    fun aRefusalIsRefused() =
        runTest {
            assertTrue(provider(Scenario.REFUSAL).generate(request()) is LlmResult.Refused)
        }

    @Test
    fun aReplyCutOffAtTheTokenLimitIsTruncated() =
        runTest {
            assertTrue(provider(Scenario.TRUNCATED).generate(request()) is LlmResult.Truncated)
        }

    @Test
    fun aReplyThatDoesNotMatchTheSchemaKeepsItsRawText() =
        runTest {
            val result = provider(Scenario.MALFORMED_OUTPUT).generate(request())
            assertTrue("Expected invalid output, got $result", result is LlmResult.InvalidOutput)
            assertEquals(MALFORMED_REPLY, (result as LlmResult.InvalidOutput).rawText)
        }

    @Test
    fun aRejectedKeyDuringGenerationIsInvalidKey() =
        runTest {
            assertEquals(LlmResult.InvalidKey, provider(Scenario.INVALID_KEY).generate(request()))
        }

    @Test
    fun rateLimitingThatOutlastsRetriesKeepsRetryAfter() =
        runTest {
            val result = provider(Scenario.RATE_LIMITED).generate(request())
            assertTrue("Expected rate limited, got $result", result is LlmResult.RateLimited)
            assertEquals(RETRY_AFTER_SECONDS, (result as LlmResult.RateLimited).retryAfter?.seconds)
        }

    @Test
    fun aServerErrorThatOutlastsRetriesIsAProviderError() =
        runTest {
            val result = provider(Scenario.SERVER_ERROR).generate(request())
            assertTrue("Expected provider error, got $result", result is LlmResult.ProviderError)
            assertEquals(SERVER_ERROR_STATUS, (result as LlmResult.ProviderError).httpStatus)
        }

    @Test
    fun anUnreachableProviderIsANetworkErrorDuringGeneration() =
        runTest {
            assertTrue(provider(Scenario.UNREACHABLE).generate(request()) is LlmResult.NetworkError)
        }

    /** The reply type the contract asks for. Value limits live here, not in the schema. */
    @Serializable
    data class Verdict(
        val score: Int,
        val reason: String,
    ) {
        init {
            require(score in 0..100) { "score must be 0-100" }
            require(reason.length <= 140) { "reason must be at most 140 characters" }
        }
    }

    /** Shared values for the contract and its subclasses' fixtures. */
    companion object {
        /** The reply text every provider returns in [Scenario.SUCCESS]. */
        const val SUCCESS_REPLY = """{"score":82,"reason":"Kotlin and Compose match"}"""

        /** The reply text in [Scenario.MALFORMED_OUTPUT]: a score that is not a number. */
        const val MALFORMED_REPLY = """{"score":"high","reason":"Kotlin and Compose match"}"""

        /** `retry-after` sent in [Scenario.RATE_LIMITED]. */
        const val RETRY_AFTER_SECONDS = 30L

        /** HTTP status sent in [Scenario.SERVER_ERROR]. */
        const val SERVER_ERROR_STATUS = 500

        /** Hand-written schema for [Verdict] (decision D01). */
        val VERDICT_SCHEMA =
            OutputSchema(
                name = "verdict",
                jsonSchema =
                    Json.parseToJsonElement(
                        """
                        {
                          "type": "object",
                          "additionalProperties": false,
                          "required": ["score", "reason"],
                          "properties": {
                            "score": { "type": "integer" },
                            "reason": { "type": "string" }
                          }
                        }
                        """.trimIndent(),
                    ) as JsonObject,
                serializer = Verdict.serializer(),
            )

        /** A small scoring request with obviously fake profile and job text. */
        fun request(): LlmRequest<Verdict> =
            LlmRequest(
                task = AiTask.SCORE,
                system = "Score how well the job fits the profile from 0 to 100.",
                context = listOf(Part.Text("Profile: Example Person, Android engineer, Kotlin, Compose.")),
                input = listOf(Part.Text("Job: Android developer at Example Co. Kotlin required.")),
                schema = VERDICT_SCHEMA,
                maxOutputTokens = 200,
            )
    }
}
