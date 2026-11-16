package com.coderarchitech.jobassistant.spike

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** T1.01 spike: thin client on OkHttp and kotlinx.serialization, Anthropic wire format. */
object SpikeEntry {
    const val VARIANT = "thin"
    const val BASE_PATH = ""

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    @Serializable
    private data class Model(
        val id: String,
    )

    @Serializable
    private data class ModelList(
        val data: List<Model>,
    )

    @Serializable
    private data class TextBlock(
        val type: String,
        val text: String? = null,
    )

    @Serializable
    private data class MessageResponse(
        val content: List<TextBlock>,
        val stop_reason: String? = null,
    )

    @Serializable
    private data class Score(
        val score: Int,
        val reason: String,
    )

    fun run(baseUrl: String): String {
        val http = OkHttpClient()
        val base = baseUrl.trimEnd('/')
        fun Request.Builder.auth() = header("x-api-key", "test-key").header("anthropic-version", "2023-06-01")

        val modelsBody =
            http
                .newCall(Request.Builder().url("$base/v1/models").auth().build())
                .execute()
                .use { it.body.string() }
        val models = json.decodeFromString<ModelList>(modelsBody).data

        val request: JsonObject =
            buildJsonObject {
                put("model", "claude-haiku-5-5")
                put("max_tokens", 256)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "Score this job")
                        },
                    )
                }
                putJsonObject("output_config") {
                    putJsonObject("format") {
                        put("type", "json_schema")
                        putJsonObject("schema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("score") { put("type", "integer") }
                                putJsonObject("reason") { put("type", "string") }
                            }
                        }
                    }
                }
            }
        val messageBody =
            http
                .newCall(
                    Request
                        .Builder()
                        .url("$base/v1/messages")
                        .auth()
                        .post(request.toString().toRequestBody(jsonType))
                        .build(),
                ).execute()
                .use { it.body.string() }
        val text = json.decodeFromString<MessageResponse>(messageBody).content.first { it.type == "text" }.text.orEmpty()
        val score = json.decodeFromString<Score>(text)
        return "models=${models.size} score=${score.score} reason=${score.reason}"
    }
}
