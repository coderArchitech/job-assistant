package com.coderarchitech.jobassistant.spike

import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.ChatCompletionCreateParams

/** T1.01 spike: OpenAI Java SDK. Tries class-derived and hand-written output schemas. */
object SpikeEntry {
    const val VARIANT = "openai"
    const val BASE_PATH = "/v1"

    fun run(baseUrl: String): String {
        val client = OpenAIOkHttpClient.builder().apiKey("test-key").baseUrl(baseUrl).build()
        val models = client.models().list().data().map { it.id() }

        // Path 1: schema written by hand, reply parsed with Jackson.
        val schema =
            ResponseFormatJsonSchema.JsonSchema.Schema
                .builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty(
                    "properties",
                    JsonValue.from(mapOf("score" to mapOf("type" to "integer"), "reason" to mapOf("type" to "string"))),
                ).build()
        val format =
            ResponseFormatJsonSchema
                .builder()
                .jsonSchema(
                    ResponseFormatJsonSchema.JsonSchema
                        .builder()
                        .name("score")
                        .schema(schema)
                        .build(),
                ).build()
        val manualParams =
            ChatCompletionCreateParams
                .builder()
                .model("gpt-test")
                .addUserMessage("Score this job")
                .responseFormat(format)
                .build()
        val text =
            client
                .chat()
                .completions()
                .create(manualParams)
                .choices()
                .first()
                .message()
                .content()
                .get()
        val manual = ObjectMapper().readTree(text)

        // Path 2: schema derived from a class.
        val classBased =
            try {
                val params =
                    ChatCompletionCreateParams
                        .builder()
                        .model("gpt-test")
                        .addUserMessage("Score this job")
                        .responseFormat(SpikeScore::class.java)
                        .build()
                val typed =
                    client
                        .chat()
                        .completions()
                        .create(params)
                        .choices()
                        .first()
                        .message()
                        .content()
                        .get()
                "ok(${typed.score})"
            } catch (e: Throwable) {
                "failed(${e.javaClass.simpleName}: ${e.message?.take(80)})"
            }
        return "models=${models.size} score=${manual["score"].asInt()} reason=${manual["reason"].asText()} classBased=$classBased"
    }
}
