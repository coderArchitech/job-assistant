package com.coderarchitech.jobassistant.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Which hosts a client may contact. */
internal class HostPolicy(
    private val exactHosts: Set<String> = emptySet(),
    private val domainSuffixes: Set<String> = emptySet(),
    private val anyHost: Boolean = false,
    private val blockedSuffixes: Set<String> = emptySet(),
    /** Refuse IP literals and names that only resolve inside a network (`localhost`, `.local`, `.internal`). */
    private val blockLocalNetwork: Boolean = false,
) {
    fun allows(host: String): Boolean {
        // `example.com.` is the same host as `example.com`, so a trailing dot must not dodge a rule.
        val h = host.lowercase().trimEnd('.')
        val refused = blockedSuffixes.any { it.matches(h) } || (blockLocalNetwork && isLocalNetworkName(h))
        return !refused && (anyHost || h in exactHosts || domainSuffixes.any { it.matches(h) })
    }

    private fun isLocalNetworkName(host: String): Boolean =
        host.contains(':') ||
            // an IPv6 literal
            IPV4_LITERAL.matches(host) ||
            LOCAL_SUFFIXES.any { host == it || host.endsWith(".$it") }

    private fun String.matches(host: String) = host == this || host.endsWith(".$this")

    companion object {
        private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val LOCAL_SUFFIXES = setOf("localhost", "local", "internal", "lan", "home.arpa")

        /**
         * The hosts each group may reach. [configHost] is the config host, as a URL or a bare host name,
         * empty until it exists (T3.10). With [blockLocalNetwork], job sources cannot reach IP literals or
         * local names, so a hostile URL cannot make the phone probe its own network.
         */
        fun forGroup(
            group: NetworkGroup,
            configHost: String,
            blockLocalNetwork: Boolean = true,
        ): HostPolicy =
            when (group) {
                NetworkGroup.AI -> HostPolicy(exactHosts = setOf("api.anthropic.com", "api.openai.com"))
                NetworkGroup.GOOGLE ->
                    HostPolicy(exactHosts = setOf("accounts.google.com"), domainSuffixes = setOf("googleapis.com"))
                NetworkGroup.CONFIG ->
                    HostPolicy(exactHosts = setOfNotNull(hostOf(configHost)))
                // The app never calls LinkedIn (CLAUDE.md rule 7), whatever a source or adapter asks for.
                NetworkGroup.JOB_SOURCES ->
                    HostPolicy(
                        anyHost = true,
                        blockedSuffixes = setOf("linkedin.com"),
                        blockLocalNetwork = blockLocalNetwork,
                    )
            }

        private fun hostOf(configHost: String): String? =
            configHost.trim().takeIf { it.isNotEmpty() }?.let { value ->
                (value.toHttpUrlOrNull() ?: "https://$value".toHttpUrlOrNull())?.host
            }
    }
}

/** A request the network layer refused to send. An [IOException], so callers treat it as a network failure. */
class BlockedRequestException(
    message: String,
) : IOException(message)

/**
 * Refuses a request to a host outside the group's [HostPolicy], and (unless [allowCleartext]) any plain
 * HTTP URL. Installed twice: as an application interceptor it stops the first hop before any connection
 * opens, and as a network interceptor it checks every redirect hop.
 */
internal class HostPolicyInterceptor(
    private val policy: HostPolicy,
    private val allowCleartext: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        check(chain.request().url)
        return chain.proceed(chain.request())
    }

    private fun check(url: HttpUrl) {
        if (!allowCleartext && !url.isHttps) {
            throw BlockedRequestException("Cleartext HTTP is not allowed: ${url.scheme}://${url.host}")
        }
        if (!policy.allows(url.host)) throw BlockedRequestException("Host is not allowed for this client: ${url.host}")
    }
}
