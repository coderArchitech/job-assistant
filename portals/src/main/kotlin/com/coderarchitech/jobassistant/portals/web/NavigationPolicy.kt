package com.coderarchitech.jobassistant.portals.web

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
 * URLs are read the way a browser hands them over, not with `java.net.URI`, which rejects characters such
 * as `|` or `{` that Chromium leaves in a query and underscores in a host: a URL that strict parser refused
 * would otherwise escape the LinkedIn check or block a real portal page.
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
        val text = url.trim()
        val scheme =
            SCHEME
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.lowercase(Locale.ROOT)
        val authority = AUTHORITY.find(text)?.groupValues?.get(1)
        val host = authority?.let(::hostOf)
        return when {
            text == ABOUT_BLANK -> NavigationDecision.ALLOW
            scheme != "https" && scheme != "http" -> NavigationDecision.BLOCK
            host.isNullOrEmpty() || isLinkedIn(host) -> NavigationDecision.BLOCK
            // Cleartext is refused: an http page of an allowed host is not loaded either. A user name or
            // password in the URL (`https://naukri.com@evil.example`) is a classic disguise, so it is never allowed.
            scheme == "https" && '@' !in authority && isAllowed(host) -> NavigationDecision.ALLOW
            else -> NavigationDecision.OPEN_OUTSIDE
        }
    }

    /** True for any request (page or sub-resource) the runtime must refuse to fetch at all. */
    fun isBlockedHost(url: String): Boolean =
        AUTHORITY
            .find(url.trim())
            ?.groupValues
            ?.get(1)
            ?.let(::hostOf)
            ?.let(::isLinkedIn) == true

    private fun isAllowed(host: String): Boolean = allowed.any { host == it || host.endsWith(".$it") }

    companion object {
        const val ABOUT_BLANK = "about:blank"

        private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
        private val AUTHORITY = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#\\\\]*)")

        /** Hosts LinkedIn serves pages and tracking from. */
        private val LINKEDIN =
            listOf(
                "linkedin.com", // ci-allow: linkedin-host (blocklist)
                "lnkd.in",
                "licdn.com",
                "linkedin.cn",
            )

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

        /** The host of an authority (`user@host:443` gives `host`), lower case, without a trailing dot. */
        private fun hostOf(authority: String): String =
            authority
                .substringAfterLast('@')
                .let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }
                .lowercase(Locale.ROOT)
                .trimEnd('.')
    }
}
