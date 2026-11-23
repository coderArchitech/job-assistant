package com.coderarchitech.jobassistant.core.network

import javax.inject.Qualifier

/**
 * The places the app talks to. Each group gets its own client, so a group's allowed hosts and timeouts
 * cannot leak into another's, and every client is limited to HTTPS.
 */
enum class NetworkGroup {
    /** The user's AI provider: Anthropic or OpenAI. */
    AI,

    /** Job-board APIs and company career pages. Many hosts, so any HTTPS host except the blocked ones. */
    JOB_SOURCES,

    /** Google sign-in and the Gmail API. */
    GOOGLE,

    /** The static config host that serves signed adapter scripts and the price table. */
    CONFIG,
}

/** Selects the `OkHttpClient` for one [NetworkGroup], for example `@HttpClientFor(NetworkGroup.AI)`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HttpClientFor(
    val group: NetworkGroup,
)

/** What the app tells the network layer about this build. */
data class NetworkConfig(
    /** True only in debug builds: log request and response headers (never bodies), with secrets removed. */
    val httpLogging: Boolean,
)
