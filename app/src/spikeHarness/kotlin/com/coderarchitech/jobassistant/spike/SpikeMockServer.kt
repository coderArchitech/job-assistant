package com.coderarchitech.jobassistant.spike

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/** Local stand-in for the Anthropic and OpenAI APIs; answers the two calls each spike variant makes. */
object SpikeMockServer {
    private const val SCORE_JSON = """{\"score\":82,\"reason\":\"good fit\"}"""

    private val anthropicModels =
        """{"data":[{"id":"claude-haiku-5-5","type":"model","display_name":"Haiku","created_at":"2026-01-01T00:00:00Z"}],""" +
            """"has_more":false,"first_id":"claude-haiku-5-5","last_id":"claude-haiku-5-5"}"""
    private val anthropicMessage =
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",""" +
            """"content":[{"type":"text","text":"$SCORE_JSON"}],"stop_reason":"end_turn","stop_sequence":null,""" +
            """"usage":{"input_tokens":10,"output_tokens":5}}"""
    private val openAiModels = """{"object":"list","data":[{"id":"gpt-test","object":"model","created":1,"owned_by":"x"}]}"""
    private val openAiCompletion =
        """{"id":"c1","object":"chat.completion","created":1,"model":"gpt-test","choices":[{"index":0,""" +
            """"finish_reason":"stop","message":{"role":"assistant","content":"$SCORE_JSON","refusal":null}}],""" +
            """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

    fun start(): MockWebServer {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    val body =
                        when (path) {
                            "/v1/models" -> if (SpikeEntry.VARIANT == "openai") openAiModels else anthropicModels
                            "/v1/messages" -> anthropicMessage
                            "/v1/chat/completions" -> openAiCompletion
                            else -> return MockResponse.Builder().code(404).body("no route for $path").build()
                        }
                    return MockResponse
                        .Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(body)
                        .build()
                }
            }
        server.start()
        return server
    }

    fun baseUrl(server: MockWebServer): String = server.url(SpikeEntry.BASE_PATH.ifEmpty { "/" }).toString().trimEnd('/')
}
