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
        // require() in Verdict: the schema has no numeric limits, the type does.
        assertTrue(VERDICT_SCHEMA.decode("""{"score":150,"reason":"ok"}""") is OutputSchema.Decoded.Mismatch)
        assertTrue(
            VERDICT_SCHEMA.decode("""{"score":50,"reason":"${"x".repeat(141)}"}""") is OutputSchema.Decoded.Mismatch,
        )
        // check() in a type's init throws IllegalStateException; it must not escape decode.
        val checked = OutputSchema("checked", inputSchema(), CheckedJson.serializer())
        assertTrue(checked.decode("""{"input":-1}""") is OutputSchema.Decoded.Mismatch)
    }

    @Test
    fun nestedObjectsMustBeStrictToo() {
        val openNested =
            """{"type":"object","additionalProperties":false,"required":["a"],
               "properties":{"a":{"type":"object","properties":{"b":{"type":"string"}},"required":["b"]}}}"""
        val optionalProperty =
            """{"type":"object","additionalProperties":false,"required":[],
               "properties":{"a":{"type":"string"}}}"""
        val openArrayItem =
            """{"type":"object","additionalProperties":false,"required":["a"],
               "properties":{"a":{"type":"array","items":{"type":"object","properties":{}}}}}"""
        for (schema in listOf(openNested, optionalProperty, openArrayItem)) {
            assertThrows("Accepted $schema", IllegalArgumentException::class.java) {
                OutputSchema("nested", json(schema), Verdict.serializer())
            }
        }
        val strictNested =
            """{"type":"object","additionalProperties":false,"required":["a"],
               "properties":{"a":{"type":"array","items":{"type":"object","additionalProperties":false,
               "required":["b"],"properties":{"b":{"type":"string"}}}}}}"""
        OutputSchema("nested", json(strictNested), Verdict.serializer())
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
    fun resultsDoNotPrintModelOutput() {
        val invalid = LlmResult.InvalidOutput("""{"reason":"Example Person"}""", "missing score", TokenUsage.NONE)
        assertFalse(invalid.toString().contains("Example Person"))
        val success = LlmResult.Success(Verdict(80, "Example Person fits"), TokenUsage.NONE, "fake-small")
        assertFalse(success.toString().contains("Example Person"))
    }

    @Test
    fun tokenUsageRejectsNegativeCounts() {
        assertThrows(IllegalArgumentException::class.java) { TokenUsage(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { TokenUsage(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { TokenUsage(0, 0, cacheReadInputTokens = -1) }
        assertThrows(IllegalArgumentException::class.java) { TokenUsage(0, 0, cacheWriteInputTokens = -1) }
    }

    @Test
    fun cachedInputCanExceedUncachedInput() {
        // Anthropic: a run's later calls read a large cached prefix and send little new input.
        val usage = TokenUsage(inputTokens = 50, outputTokens = 300, cacheReadInputTokens = 2_000)
        assertEquals(2_050L, usage.totalInputTokens)
        assertEquals(
            3_100L,
            TokenUsage(100, 0, cacheReadInputTokens = 0, cacheWriteInputTokens = 3_000).totalInputTokens,
        )
    }

    @Test
    fun modelInfoRejectsImpossibleValues() {
        assertThrows(IllegalArgumentException::class.java) { ModelInfo(" ") }
        assertThrows(IllegalArgumentException::class.java) { ModelInfo("m", contextWindowTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { ModelInfo("m", maxOutputTokens = -1) }
    }

    @kotlinx.serialization.Serializable
    private data class CheckedJson(
        val input: Int,
    ) {
        init {
            check(input >= 0) { "input must not be negative" }
        }
    }

    private fun objectSchema(): JsonObject = json("""{"type":"object","additionalProperties":false}""")

    private fun inputSchema(): JsonObject =
        json(
            """{"type":"object","additionalProperties":false,"required":["input"],
               "properties":{"input":{"type":"integer"}}}""",
        )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
}
