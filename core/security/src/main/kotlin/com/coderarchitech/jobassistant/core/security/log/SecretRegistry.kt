package com.coderarchitech.jobassistant.core.security.log

import com.coderarchitech.jobassistant.core.security.vault.Secret
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The secrets currently known to this process, so [Redactor] can strip them from log lines by
 * exact match (decision in T1.09: patterns plus a live registry).
 *
 * The vault adds every value it stores or returns under `vault:<slot>` and removes it on delete or
 * wipe; the OTP flow adds the last code under its own key. Values are held as [CharArray] copies
 * that are zeroed when removed, and never turned into a String. Thread-safe.
 *
 * Accepted limits: values shorter than [MIN_LENGTH] are not redacted, and a value that is replaced
 * or removed stops being redacted at once.
 */
@Singleton
class SecretRegistry
    @Inject
    constructor() {
        private val lock = Any()
        private val values = HashMap<String, CharArray>()

        /** Registered values long enough to redact, longest first; rebuilt on every change. */
        @Volatile private var snapshot: List<CharArray> = emptyList()

        /** Remembers [secret] under [key], replacing (and zeroing) any earlier value for that key. */
        fun register(
            key: String,
            secret: Secret,
        ) {
            val copy = secret.useChars { it.copyOf() }
            put(key, copy)
        }

        /** Remembers a copy of [chars] under [key]; the caller still owns and zeroes [chars]. */
        fun register(
            key: String,
            chars: CharArray,
        ) = put(key, chars.copyOf())

        /** Forgets and zeroes the value under [key]; does nothing when there is none. */
        fun unregister(key: String) = removeWhere { it == key }

        /** Forgets and zeroes every value whose key starts with [prefix]. */
        fun unregisterAll(prefix: String) = removeWhere { it.startsWith(prefix) }

        /**
         * Replaces every occurrence of a registered value in [text] with [replacement], longest
         * value first.
         */
        fun redact(
            text: String,
            replacement: String,
        ): String {
            val secrets = snapshot
            if (secrets.isEmpty()) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val hit = secrets.firstOrNull { text.matchesAt(i, it) }
                if (hit != null) {
                    out.append(replacement)
                    i += hit.size
                } else {
                    out.append(text[i])
                    i++
                }
            }
            return out.toString()
        }

        private fun put(
            key: String,
            copy: CharArray,
        ) {
            val old =
                synchronized(lock) {
                    values.put(key, copy).also { rebuildSnapshot() }
                }
            old?.fill('\u0000')
        }

        private fun removeWhere(matches: (String) -> Boolean) {
            val removed =
                synchronized(lock) {
                    val keys = values.keys.filter(matches)
                    keys.mapNotNull { values.remove(it) }.also { rebuildSnapshot() }
                }
            removed.forEach { it.fill('\u0000') }
        }

        /** Called with [lock] held. */
        private fun rebuildSnapshot() {
            snapshot = values.values.filter { it.size >= MIN_LENGTH }.sortedByDescending { it.size }
        }

        /** Shared values. */
        companion object {
            /** Shortest value that is redacted; shorter ones would match ordinary text. */
            const val MIN_LENGTH = 4
        }
    }

private fun String.matchesAt(
    start: Int,
    value: CharArray,
): Boolean = start + value.size <= length && value.indices.all { this[start + it] == value[it] }
