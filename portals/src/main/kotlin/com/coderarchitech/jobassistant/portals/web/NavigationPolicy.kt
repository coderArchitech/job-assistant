package com.coderarchitech.jobassistant.portals.web

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** What the runtime does with a navigation. */
enum class NavigationDecision {
    /** A page of the portal, its sign-in or its application form: load it in the runtime. */
    ALLOW,

    /** A web page outside the portal: not loaded here; the user may open it in their browser. */
    OPEN_OUTSIDE,

    /** Never loaded, here or outside: LinkedIn, or a scheme that is not a web page (`file:`, `intent:`). */
    BLOCK,
}

/**
 * Which pages a portal's runtime may load (design, WebView runtime): the portal's own hosts plus known
 * sign-in and ATS hosts, over HTTPS only. LinkedIn is always blocked, whatever a portal lists, because the
 * app never automates it (CLAUDE.md rule 7). Pure, so it is tested on the JVM and shared by every check
 * point in [AgentWebView].
 *
 * @param portalHosts the portal's hosts, such as `naukri.com`; each also allows its subdomains.
 */
class NavigationPolicy(
    portalHosts: Set<String>,
    includeSignInAndAts: Boolean = true,
) {
    private val allowed: Set<String> =
        (portalHosts + if (includeSignInAndAts) SIGN_IN_AND_ATS_HOSTS else emptySet())
            .map {
                it
                    .trim()
                    .lowercase(Locale.ROOT)
                    .removePrefix("*.")
                    .trimEnd('.')
            }.filter { it.isNotEmpty() }
            .onEach { require(!isLinkedIn(it)) { "LinkedIn cannot be allowed in the runtime" } }
            .toSet()

    /** The decision for a navigation to [url]. */
    fun decide(url: String): NavigationDecision {
        val uri = parse(url) ?: return NavigationDecision.BLOCK
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        return when {
            url.trim() == ABOUT_BLANK -> NavigationDecision.ALLOW
            scheme != "https" && scheme != "http" -> NavigationDecision.BLOCK
            host.isNullOrEmpty() || isLinkedIn(host) -> NavigationDecision.BLOCK
            // Mixed content and cleartext are refused: an http page of an allowed host is not loaded either.
            scheme == "https" && isAllowed(host) && uri.userInfo == null -> NavigationDecision.ALLOW
            else -> NavigationDecision.OPEN_OUTSIDE
        }
    }

    /** True for any request (page or sub-resource) the runtime must refuse to fetch at all. */
    fun isBlockedHost(url: String): Boolean {
        val host = parse(url)?.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        return host != null && isLinkedIn(host)
    }

    /** The URI, or null when [url] is not one; such text is never loaded. */
    @Suppress("SwallowedException")
    private fun parse(url: String): URI? =
        try {
            URI(url.trim())
        } catch (e: URISyntaxException) {
            null
        }

    private fun isAllowed(host: String): Boolean = allowed.any { host == it || host.endsWith(".$it") }

    companion object {
        const val ABOUT_BLANK = "about:blank"

        /** Hosts LinkedIn serves pages and tracking from. */
        private val LINKEDIN = listOf("linkedin.com", "lnkd.in", "licdn.com", "linkedin.cn")

        /**
         * Sign-in pages portals send users to, and the ATS forms a portal's "apply" can land on. Workday and
         * other unsupported systems are not here: reaching one means "apply manually" (design, Apply flow).
         */
        val SIGN_IN_AND_ATS_HOSTS =
            setOf(
                "accounts.google.com",
                "login.microsoftonline.com",
                "appleid.apple.com",
                "boards.greenhouse.io",
                "job-boards.greenhouse.io",
                "jobs.lever.co",
                "jobs.eu.lever.co",
                "jobs.ashbyhq.com",
            )

        /** True for a LinkedIn host or any subdomain of one. */
        fun isLinkedIn(host: String): Boolean = LINKEDIN.any { host == it || host.endsWith(".$it") }
    }
}
