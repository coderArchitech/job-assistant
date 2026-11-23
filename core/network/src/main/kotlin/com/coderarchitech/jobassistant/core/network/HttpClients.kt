package com.coderarchitech.jobassistant.core.network

import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.time.Duration

/** The JSON settings every network caller shares. */
val NetworkJson: Json =
    Json {
        // Providers add fields over time; an unknown field must not break parsing.
        ignoreUnknownKeys = true
        // A null field and a missing field mean the same thing on the wire.
        explicitNulls = false
    }

/** Headers that carry secrets. They are replaced by `██` in HTTP logs. */
internal val SECRET_HEADERS = listOf("Authorization", "Proxy-Authorization", "x-api-key", "Cookie", "Set-Cookie")

/** Query parameters that carry secrets (for example `?key=` on some Google endpoints). */
internal val SECRET_QUERY_PARAMS =
    listOf(
        "key",
        "api_key",
        "apikey",
        "api-key",
        "access_token",
        "id_token",
        "refresh_token",
        "token",
        "code",
        "client_secret",
        "secret",
        "password",
        "pwd",
        "auth",
        "signature",
        "sig",
    )

/** Logs request and response headers only, never bodies, with [SECRET_HEADERS] and [SECRET_QUERY_PARAMS] hidden. */
internal fun redactingHttpLogger(logger: (String) -> Unit): HttpLoggingInterceptor =
    HttpLoggingInterceptor { logger(it) }.apply {
        level = HttpLoggingInterceptor.Level.HEADERS
        SECRET_HEADERS.forEach(::redactHeader)
        SECRET_QUERY_PARAMS.forEach { redactQueryParams(it) }
    }

/**
 * Builds the per-group clients. They share one connection pool and dispatcher through [base], and
 * differ in allowed hosts, timeouts and the interceptors below.
 */
internal class HttpClientFactory(
    private val base: OkHttpClient,
    private val configHost: String,
    private val config: NetworkConfig,
    private val logger: (String) -> Unit,
    private val retry: RetryInterceptor = RetryInterceptor(),
    /** Tests only: lets a plain-HTTP `MockWebServer` through. Production never sets this. */
    private val allowCleartext: Boolean = false,
) {
    fun create(group: NetworkGroup): OkHttpClient {
        val hostPolicy =
            HostPolicyInterceptor(
                HostPolicy.forGroup(group, configHost, blockLocalNetwork = !allowCleartext),
                allowCleartext,
            )
        val specs =
            if (allowCleartext) {
                listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
            } else {
                listOf(ConnectionSpec.MODERN_TLS)
            }
        val isAi = group == NetworkGroup.AI
        return base
            .newBuilder()
            .connectionSpecs(specs)
            // Application interceptors run once per call, so the retry loop sits inside them; the host check
            // is first so a blocked request never opens a connection.
            .addInterceptor(hostPolicy)
            // Job boards get at most two requests at a time per host; waiting here also covers retries.
            .apply { if (group == NetworkGroup.JOB_SOURCES) addInterceptor(HostLimitInterceptor()) }
            .addInterceptor(retry)
            // Redirect hops never pass through application interceptors, so the host check runs here too.
            .addNetworkInterceptor(hostPolicy)
            .apply { if (config.httpLogging) addNetworkInterceptor(redactingHttpLogger(logger)) }
            .connectTimeout(CONNECT_TIMEOUT)
            .readTimeout(if (isAi) AI_READ_TIMEOUT else READ_TIMEOUT)
            .writeTimeout(READ_TIMEOUT)
            // Covers the whole call, including retries and their waits.
            .callTimeout(if (isAi) AI_CALL_TIMEOUT else CALL_TIMEOUT)
            .build()
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(30)
        val CALL_TIMEOUT: Duration = Duration.ofMinutes(3)

        // Structured-output answers can take a while to start; this is the user's own key, so be patient.
        val AI_READ_TIMEOUT: Duration = Duration.ofMinutes(2)
        val AI_CALL_TIMEOUT: Duration = Duration.ofMinutes(10)
    }
}
