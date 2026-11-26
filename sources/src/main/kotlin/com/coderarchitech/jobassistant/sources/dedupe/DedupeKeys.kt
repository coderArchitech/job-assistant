package com.coderarchitech.jobassistant.sources.dedupe

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.util.Locale

/** The keys the deduplicator compares (design, Deduplication). */
object DedupeKeys {
    /**
     * Query parameters that only say where a click came from, matched case-insensitively, plus any `utm_*`.
     * Generic names such as `ref`, `source` or `gh_jid` are kept: on a company's own careers page they can
     * name the job.
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
            "ref_src",
            "gh_src",
            "lever-source",
            "lever-origin",
            "lever-via",
        )
    private val SENIORITY = mapOf("sr" to "senior", "snr" to "senior", "jr" to "junior", "jnr" to "junior")
    private const val MODES = "remote|hybrid|on-?site|wfh"

    /**
     * A work-mode tag on a title, which the same job may carry on one board and not another: a bracket that
     * holds only the mode (`(Remote)`, not `(Remote - EU)`), or the mode alone after a spaced dash, a bar or a
     * comma at the end (`- Hybrid`, not `Fully-Remote` or `, Hybrid Cloud`).
     */
    private val MODE_TAG =
        Regex("\\s*[(\\[]\\s*($MODES)\\s*[)\\]]|(?:\\s+[-–—|]|,)\\s*($MODES)\\s*$", RegexOption.IGNORE_CASE)

    /** Word characters, plus `+` and `#` so `C++`, `C#` and `C` stay apart. */
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}+#]+")
    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * The URL without what varies between links to the same page: `https`, the host in lower case without
     * `www.`, tracking parameters and a trailing slash removed, and the remaining parameters sorted. The
     * fragment is dropped unless it routes the page (`#/jobs/123`, `#!/jobs/123`). Text that is not an
     * http(s) URL is returned trimmed, so it can still be compared.
     */
    fun canonicalUrl(url: String): String {
        val parsed = url.trim().toHttpUrlOrNull() ?: return url.trim()
        val routing = parsed.fragment?.takeIf { it.startsWith("/") || it.startsWith("!") }
        val builder =
            parsed
                .newBuilder()
                .scheme("https")
                .host(parsed.host.lowercase(Locale.ROOT).removePrefix("www."))
                .fragment(routing)
                .query(null)
        val segments = parsed.pathSegments
        if (segments.size > 1 && segments.last().isEmpty()) builder.removePathSegment(segments.lastIndex)
        parsed.queryParameterNames
            .filterNot(::isTracking)
            .sorted()
            .forEach { name -> parsed.queryParameterValues(name).forEach { builder.addQueryParameter(name, it) } }
        return builder.build().toString().let { if (routing == null) it.removeSuffix("/") else it }
    }

    /**
     * SHA-256 (hex) of the company key and the title in a comparable form ([titleKey]). [companyKey] is
     * `NormalizedPosting.companyKey`.
     */
    fun dedupeHash(
        companyKey: String,
        title: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$companyKey\n${titleKey(title)}".toByteArray())
        val out = CharArray(digest.size * 2)
        digest.forEachIndexed { i, byte ->
            val v = byte.toInt() and BYTE_MASK
            out[i * 2] = HEX[v ushr NIBBLE]
            out[i * 2 + 1] = HEX[v and NIBBLE_MASK]
        }
        return String(out)
    }

    /**
     * The title as the hash sees it: lower case, punctuation removed (but `+` and `#` kept), `Sr.` read as
     * `senior` and `Jr.` as `junior`, and a work-mode tag dropped.
     */
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

    private const val BYTE_MASK = 0xff
    private const val NIBBLE = 4
    private const val NIBBLE_MASK = 0x0f
}
