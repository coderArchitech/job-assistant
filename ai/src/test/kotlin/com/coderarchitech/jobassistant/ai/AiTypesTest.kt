package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.ai.LlmProviderContract.Companion.VERDICT_SCHEMA
import com.coderarchitech.jobassistant.ai.LlmProviderContract.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTypesTest {
    @Test
    fun decodeReturnsTheValue() {
        assertEquals(
            OutputSchema.Decoded.Value(Verdict(82, "Kotlin and Compose match")),
            VERDICT_SCHEMA.decode(LlmProviderContract.SUCCESS_REPLY),
        )
    }

    @Test
    fun decodeReportsEveryKindOfMismatch() {
        val replies =
            listOf(
                "not json",
                "",
                LlmProviderContract.MALFORMED_REPLY,
                """{"score":82}""",
                """{"score":82,"reason":"ok","extra":true}""",
                """[1,2]""",
            )
        for (reply in replies) {
            assertTrue("Should not decode: $reply", VERDICT_SCHEMA.decode(reply) is OutputSchema.Decoded.Mismatch)
        }
    }

    @Test
    fun decodeTurnsATypesOwnValidationIntoAMismatch() {
        val schema = OutputSchema("usage", objectSchema(), TokenUsageJson.serializer())
        assertTrue(schema.decode("""{"input":-1}""") is OutputSchema.Decoded.Mismatch)
    }

    @Test
    fun schemaNamesFollowTheProvidersRules() {
        for (bad in listOf("", "has space", "dot.name", "x".repeat(65))) {
            assertThrows("Accepted name '$bad'", IllegalArgumentException::class.java) {
                OutputSchema(bad, objectSchema(), Verdict.serializer())
            }
        }
        OutputSchema("score_v2-${"x".repeat(55)}", objectSchema(), Verdict.serializer())
    }

    @Test
    fun schemaRootMustBeAStrictObject() {
        val notObject = json("""{"type":"array","additionalProperties":false}""")
        val open = json("""{"type":"object"}""")
        val explicitlyOpen = json("""{"type":"object","additionalProperties":true}""")
        for (schema in listOf(notObject, open, explicitlyOpen)) {
            assertThrows(IllegalArgumentException::class.java) {
                OutputSchema("verdict", schema, Verdict.serializer())
            }
        }
    }

    @Test
    fun requestsRejectImpossibleValues() {
        val ok = LlmProviderContract.request()
        assertThrows(IllegalArgumentException::class.java) { ok.copy(system = " ") }
        assertThrows(IllegalArgumentException::class.java) { ok.copy(input = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ok.copy(maxOutputTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { Part.Text("") }
    }

    @Test
    fun textPartsDoNotPrintTheirContent() {
        val part = Part.Text("Profile: Example Person, CTC 12 LPA")
        assertFalse(part.toString().contains("Example Person"))
        assertFalse(LlmProviderContract.request().toString().contains("Example Person"))
    }

    @Test
    fun invalidOutputDoesNotPrintTheRawText() {
        val result = LlmResult.InvalidOutput("""{"reason":"Example Person"}""", "missing score", TokenUsage.NONE)
        assertFalse(result.toString().contains("Example Person"))
    }

    @Test
    fun tokenUsageRejectsImpossibleCounts() {
        assertThrows(IllegalArgumentException::class.java) { TokenUsage(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { TokenUsage(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { TokenUsage(10, 0, cachedInputTokens = 11) }
        assertEquals(TokenUsage(10, 5, 10).cachedInputTokens, 10L)
    }

    @Test
    fun modelInfoRejectsImpossibleValues() {
        assertThrows(IllegalArgumentException::class.java) { ModelInfo(" ") }
        assertThrows(IllegalArgumentException::class.java) { ModelInfo("m", contextWindowTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { ModelInfo("m", maxOutputTokens = -1) }
    }

    @kotlinx.serialization.Serializable
    private data class TokenUsageJson(
        val input: Int,
    ) {
        init {
            require(input >= 0) { "input must not be negative" }
        }
    }

    private fun objectSchema(): JsonObject = json("""{"type":"object","additionalProperties":false}""")

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
}
