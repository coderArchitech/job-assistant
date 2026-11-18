package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.core.model.AiTask
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One AI call. The prompt is laid out for caching: [system] and [context] stay the same for every
 * call in a run (the stable prefix), and only [input] changes. No sampling parameters exist on
 * purpose: current Claude models reject `temperature`, and both providers use their defaults.
 *
 * Nothing secret may appear in any part: no passwords, OTPs, cookies or API keys (CLAUDE.md rule 1).
 * The prompt guard (T1.15) checks every request before it is sent.
 */
data class LlmRequest<T>(
    val task: AiTask,
    /** Stable rubric and instructions; cached where the provider supports it. */
    val system: String,
    /** Profile, preset context and examples; the rest of the stable prefix. */
    val context: List<Part>,
    /** The job or page snapshot; varies per call. */
    val input: List<Part>,
    val schema: OutputSchema<T>,
    val maxOutputTokens: Int,
) {
    init {
        require(system.isNotBlank()) { "A request needs a system prompt" }
        require(input.isNotEmpty()) { "A request needs input" }
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
    }
}

/** A piece of prompt content. */
sealed interface Part {
    /** Plain text. */
    data class Text(
        val text: String,
    ) : Part {
        init {
            require(text.isNotEmpty()) { "A text part cannot be empty" }
        }

        // Prompt text can hold profile data; keep it out of logs that print the request.
        override fun toString(): String = "Text(${text.length} chars)"
    }
}

/**
 * The shape of a reply: a hand-written JSON Schema sent to the provider, and the serializer that
 * decodes the reply into [T]. Never derive the schema from a class with an SDK helper; that needs
 * reflection Android lacks (decision D01).
 *
 * The schema must be an object with `additionalProperties: false`, which both providers' strict
 * structured-output modes require.
 */
class OutputSchema<T>(
    /** Name sent to the provider; letters, digits, `_` and `-`, at most 64 characters. */
    val name: String,
    val jsonSchema: JsonObject,
    val serializer: KSerializer<T>,
) {
    init {
        require(NAME.matches(name)) { "Schema name must match ${NAME.pattern}" }
        require(jsonSchema["type"] == JsonPrimitive("object")) { "The schema root must be an object" }
        require(jsonSchema["additionalProperties"] == JsonPrimitive(false)) {
            "The schema root must set additionalProperties to false"
        }
    }

    /** Decodes a reply's JSON text into [T], or says why it does not fit. */
    fun decode(text: String): Decoded<T> =
        try {
            Decoded.Value(STRICT_JSON.decodeFromString(serializer, text))
        } catch (e: SerializationException) {
            Decoded.Mismatch(e.message ?: "The reply does not match the schema")
        } catch (e: IllegalArgumentException) {
            // A type's own validation (require) failed while decoding.
            Decoded.Mismatch(e.message ?: "The reply has an invalid value")
        }

    override fun toString(): String = "OutputSchema($name)"

    /** Result of [decode]. */
    sealed interface Decoded<out T> {
        /** The reply decoded into a value. */
        data class Value<T>(
            val value: T,
        ) : Decoded<T>

        /** The reply was not valid JSON, or did not match the type. */
        data class Mismatch(
            val problem: String,
        ) : Decoded<Nothing>
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9_-]{1,64}")

        // Strict on purpose: an unexpected field means the reply did not follow the schema.
        val STRICT_JSON = Json { ignoreUnknownKeys = false }
    }
}
