package com.coderarchitech.jobassistant.core.security.log

import android.util.Log
import timber.log.Timber

/**
 * The only log tree the app plants. Every message passes through [Redactor] before it is written.
 * Timber appends a throwable's stack trace to the message before calling [log], so exception
 * messages are redacted too; the raw throwable is never written.
 *
 * @param minPriority lowest [Log] priority written; release builds pass [Log.INFO] so debug output
 *   never reaches a user's device log.
 * @param sink where redacted lines go; Logcat by default, a list in tests.
 */
class RedactingTree(
    private val redactor: Redactor,
    private val minPriority: Int,
    private val sink: (priority: Int, tag: String, message: String) -> Unit = ::writeToLogcat,
) : Timber.Tree() {
    override fun isLoggable(
        tag: String?,
        priority: Int,
    ): Boolean = priority >= minPriority

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        sink(priority, tag?.let(redactor::redact) ?: DEFAULT_TAG, redactor.redact(message))
    }

    internal companion object {
        const val DEFAULT_TAG = "JobAssistant"

        /**
         * Logcat truncates an entry at about 4,000 bytes. A UTF-16 unit takes at most 3 UTF-8
         * bytes, so 1,300 characters always fit.
         */
        const val MAX_LOGCAT_CHUNK = 1_300

        /** A line break in the last part of a chunk is used as the split point. */
        private const val NEWLINE_WINDOW = MAX_LOGCAT_CHUNK / 4

        fun writeToLogcat(
            priority: Int,
            tag: String,
            message: String,
        ) {
            chunk(message).forEach { Log.println(priority, tag, it) }
        }

        /** Splits at a line break when one falls in the last quarter of a chunk, never inside a surrogate pair. */
        internal fun chunk(message: String): List<String> {
            val parts = mutableListOf<String>()
            var start = 0
            while (message.length - start > MAX_LOGCAT_CHUNK) {
                var end = start + MAX_LOGCAT_CHUNK
                val newline = message.lastIndexOf('\n', end - 1)
                if (newline >= end - NEWLINE_WINDOW) {
                    end = newline + 1
                } else if (message[end - 1].isHighSurrogate()) {
                    end--
                }
                parts += message.substring(start, end)
                start = end
            }
            parts += message.substring(start)
            return parts
        }
    }
}
