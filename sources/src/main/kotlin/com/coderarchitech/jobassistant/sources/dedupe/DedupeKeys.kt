package com.coderarchitech.jobassistant.sources.dedupe

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.util.Locale

/** The keys the deduplicator compares (design, Deduplication). */
object DedupeKeys {
    /**
     * Query parameters that only say where a click came from. Matched case-insensitively. `gh_jid` is not one:
     * on a company's own careers page it names the job.
     */
    private val TRACKING =
        setOf(
            "gclid",
            "fbclid",
            "msclkid",
            "dclid",
            "yclid",
            "mc_cid",
            "mc_eid",
            "_hsenc",
            "_hsmi",
            "ref",
            "referrer",
            "ref_src",
            "source",
            "src",
            "gh_src",
            "lever-source",
            "lever-origin",
            "lever-via",
            "trk",
            "trackingid",
            "campaign",
            "utm",
        )
    private val SENIORITY = mapOf("sr" to "senior", "snr" to "senior", "jr" to "junior", "jnr" to "junior")

    /** A work-mode tag on a title, which the same job may carry on one board and not another. */
    private val MODE_TAG =
        Regex(
            "\\s*(?:[(\\[]\\s*(?:remote|hybrid|on-?site|wfh)[^)\\]]*[)\\]]|" +
                "[-–—|,]\\s*(?:remote|hybrid|on-?site)\\b.*$)",
            RegexOption.IGNORE_CASE,
        )
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /**
     * The URL without what varies between links to the same page: scheme and host in lower case, `www.`
     * dropped, the fragment, tracking parameters (`utm_*`, `gclid`, `gh_src`, `lever-source` and others) and a
     * trailing slash removed, and the remaining parameters sorted. Text that is not an http(s) URL is
     * returned trimmed, so it can still be compared.
     */
    fun canonicalUrl(url: String): String {
        val parsed = url.trim().toHttpUrlOrNull() ?: return url.trim()
        val builder =
            parsed
                .newBuilder()
                .scheme("https")
                .host(parsed.host.lowercase(Locale.ROOT).removePrefix("www."))
                .fragment(null)
                .query(null)
        parsed.queryParameterNames
            .filterNot { isTracking(it) }
            .sorted()
            .forEach { name -> parsed.queryParameterValues(name).forEach { builder.addQueryParameter(name, it) } }
        return builder.build().withoutTrailingSlash()
    }

    /**
     * SHA-256 (hex) of the company key and the title in a comparable form: lower case, punctuation removed,
     * `Sr.` read as `senior` and `Jr.` as `junior`, and a work-mode tag such as `(Remote)` or `- Hybrid`
     * dropped. [companyKey] is `NormalizedPosting.companyKey`.
     */
    fun dedupeHash(
        companyKey: String,
        title: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$companyKey\n${titleKey(title)}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** The title as the hash sees it. */
    fun titleKey(title: String): String =
        title
            .replace(MODE_TAG, "")
            .lowercase(Locale.ROOT)
            .split(NOT_WORD)
            .filter { it.isNotEmpty() }
            .joinToString(" ") { SENIORITY[it] ?: it }

    private fun isTracking(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith("utm_") || lower in TRACKING
    }

    private fun HttpUrl.withoutTrailingSlash(): String {
        val text = toString()
        val pathEnd = text.indexOf('?').takeIf { it >= 0 } ?: text.length
        val path = text.substring(0, pathEnd)
        val trimmed = if (path.endsWith("/") && encodedPath != "/") path.dropLast(1) else path.removeSuffix("/")
        return trimmed + text.substring(pathEnd)
    }
}
