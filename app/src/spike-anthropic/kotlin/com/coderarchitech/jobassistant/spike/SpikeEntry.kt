package com.coderarchitech.jobassistant.spike

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.fasterxml.jackson.databind.ObjectMapper

/** T1.01 spike: Anthropic Java SDK. Tries class-derived and hand-written output schemas. */
object SpikeEntry {
    const val VARIANT = "anthropic"
    const val BASE_PATH = ""

    fun run(baseUrl: String): String {
        val client = AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(baseUrl).build()
        val models = client.models().list().data().map { it.id() }

        // Path 1: schema written by hand, reply parsed with Jackson (no java.lang.reflect.AnnotatedType).
        val schema =
            JsonOutputFormat.Schema
                .builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty(
                    "properties",
                    JsonValue.from(mapOf("score" to mapOf("type" to "integer"), "reason" to mapOf("type" to "string"))),
                ).build()
        val manualParams =
            MessageCreateParams
                .builder()
                .model("claude-haiku-5-5")
                .maxTokens(256L)
                .outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(schema).build()).build())
                .addUserMessage("Score this job")
                .build()
        val text =
            client
                .messages()
                .create(manualParams)
                .content()
                .firstNotNullOf { it.text().orElse(null) }
                .text()
        val manual = ObjectMapper().readTree(text)

        // Path 2: schema derived from a class (uses victools + java.lang.reflect.AnnotatedType).
        val classBased =
            try {
                val params =
                    MessageCreateParams
                        .builder()
                        .model("claude-haiku-5-5")
                        .maxTokens(256L)
                        .outputConfig(SpikeScore::class.java)
                        .addUserMessage("Score this job")
                        .build()
                val typed =
                    client
                        .messages()
                        .create(params)
                        .content()
                        .firstNotNullOf { it.text().orElse(null) }
                        .text()
                "ok(${typed.score})"
            } catch (e: Throwable) {
                "failed(${e.javaClass.simpleName}: ${e.message?.take(80)})"
            }
        return "models=${models.size} score=${manual["score"].asInt()} reason=${manual["reason"].asText()} classBased=$classBased"
    }
}
