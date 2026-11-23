package com.coderarchitech.jobassistant.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import java.io.IOException

/** The text trimmed, or null when nothing is left. */
internal fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Sources differ in how they spell where the work happens (`OnSite`, `on-site`, `Remote`). This maps the
 * three real values to one spelling (`remote`, `hybrid`, `on-site`) and keeps anything else lower case;
 * `unspecified` and blank mean the source gave no information.
 */
internal fun String?.normalizedWorkplace(): String? {
    val text = clean()?.lowercase() ?: return null
    return when (text.replace("-", "").replace("_", "").replace(" ", "")) {
        "unspecified" -> null
        "onsite" -> "on-site"
        else -> text
    }
}

private const val BYTES_PER_MB = 1024L * 1024

/** Largest reply a board may send, in megabytes. A bigger one is a misbehaving host, not a job board. */
private const val MAX_BODY_MB = 64L

/** Reads the whole reply off the caller's thread and refuses one over [maxBytes]. */
internal suspend fun Response.readText(maxBytes: Long = MAX_BODY_MB * BYTES_PER_MB): String =
    withContext(Dispatchers.IO) {
        val source = body.source()
        if (source.request(maxBytes + 1)) throw IOException("The reply is larger than $maxBytes bytes")
        source.readUtf8()
    }
