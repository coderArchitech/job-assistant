package com.coderarchitech.jobassistant.core.security.vault

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * A secret value (password, API key) on its way into or out of the [Vault].
 *
 * [toString] never shows the value, so a `Secret` that ends up in a log or a string template
 * prints as `Secret(****)`. Call [reveal] only where the plaintext is really needed, and never log
 * or prompt with the result. Two secrets are equal when their values are equal; the comparison
 * takes the same time wherever the values differ.
 */
class Secret(
    value: CharSequence,
) {
    private val chars = CharArray(value.length) { value[it] }

    /** Number of characters in the secret. */
    val length: Int get() = chars.size

    /** The plaintext value. Never log it or put it in an AI prompt. */
    fun reveal(): String = String(chars)

    /** UTF-8 bytes of the value. The caller must zero the returned array after use. */
    internal fun encodeUtf8(): ByteArray {
        val buffer =
            Charsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }

    override fun toString(): String = "Secret(****)"

    override fun equals(other: Any?): Boolean {
        if (other !is Secret) return false
        var diff = chars.size xor other.chars.size
        val size = maxOf(chars.size, other.chars.size)
        for (i in 0 until size) {
            val a = if (i < chars.size) chars[i].code else 0
            val b = if (i < other.chars.size) other.chars[i].code else 0
            diff = diff or (a xor b)
        }
        return diff == 0
    }

    /** Constant, so a hash never reveals anything about the value. */
    override fun hashCode(): Int = 0

    internal companion object {
        /** Decodes UTF-8 [bytes] into a secret without creating an intermediate String. */
        fun fromUtf8(bytes: ByteArray): Secret {
            val decoded =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
            return try {
                Secret(decoded)
            } finally {
                if (decoded.hasArray()) decoded.array().fill('\u0000')
            }
        }
    }
}
