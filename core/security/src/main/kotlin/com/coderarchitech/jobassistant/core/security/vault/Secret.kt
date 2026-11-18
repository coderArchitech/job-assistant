package com.coderarchitech.jobassistant.core.security.vault

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction

/**
 * Marks [Secret.reveal]: it creates a String that cannot be zeroed; prefer [Secret.useChars].
 *
 * Opt in with `@OptIn(RevealsSecret::class)` only where an API takes nothing but a String.
 */
@RequiresOptIn(
    message = "reveal() creates a String that cannot be zeroed; prefer useChars",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class RevealsSecret

/**
 * A secret value (password, API key) on its way into or out of the [Vault].
 *
 * [toString] never shows the value, so a `Secret` that ends up in a log or a string template
 * prints as `Secret(****)`. Read the value with [useChars], which hands out a copy that is zeroed
 * afterwards; [reveal] is a last resort. Never log or prompt with the value. [close] zeroes the
 * value; after that every read and [equals] throw [IllegalStateException]. Two secrets are equal
 * when their values are equal; the comparison takes the same time wherever the values differ. The
 * value must be valid UTF-16 text (no lone surrogate), so it can always be stored; invalid text is
 * rejected here with [IllegalArgumentException] rather than failing later inside the vault.
 */
class Secret(
    value: CharSequence,
) : AutoCloseable {
    private val chars = CharArray(value.length) { value[it] }

    @Volatile private var closed = false

    init {
        if (!isWellFormed(chars)) {
            chars.fill('\u0000')
            throw IllegalArgumentException("A secret must be valid text (it has a lone surrogate)")
        }
    }

    /** Number of characters in the secret. */
    val length: Int get() = open().size

    /**
     * The plaintext value as a String, which cannot be zeroed and lives until the garbage
     * collector frees it. Prefer [useChars]. Never log it or put it in an AI prompt.
     */
    @RevealsSecret
    fun reveal(): String = String(open())

    /**
     * Runs [block] with a copy of the value and zeroes the copy afterwards, so the caller never
     * needs a String. [block] must not keep the array.
     */
    inline fun <T> useChars(block: (CharArray) -> T): T {
        val copy = copyChars()
        try {
            return block(copy)
        } finally {
            copy.fill('\u0000')
        }
    }

    /** A fresh copy of the value for [useChars]; the caller zeroes it. */
    @PublishedApi
    internal fun copyChars(): CharArray = open().copyOf()

    /**
     * UTF-8 bytes of the value. The caller must zero the returned array after use. Encodes into
     * one buffer of the worst-case size, so no growth copy is left behind unzeroed.
     */
    internal fun encodeUtf8(): ByteArray {
        val source = open()
        // A UTF-16 unit takes at most 3 UTF-8 bytes; a surrogate pair (2 units) takes 4.
        val out = ByteBuffer.allocate(source.size * MAX_UTF8_BYTES_PER_CHAR)
        try {
            val encoder =
                Charsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            requireUnderflow(encoder.encode(CharBuffer.wrap(source), out, true))
            requireUnderflow(encoder.flush(out))
            return out.array().copyOf(out.position())
        } finally {
            out.array().fill(0)
        }
    }

    /** Zeroes the value. Every later read throws [IllegalStateException]; closing again is fine. */
    override fun close() {
        closed = true
        chars.fill('\u0000')
    }

    override fun toString(): String = "Secret(****)"

    override fun equals(other: Any?): Boolean {
        if (other !is Secret) return false
        val mine = open()
        val theirs = other.open()
        var diff = mine.size xor theirs.size
        val size = maxOf(mine.size, theirs.size)
        for (i in 0 until size) {
            val a = if (i < mine.size) mine[i].code else 0
            val b = if (i < theirs.size) theirs[i].code else 0
            diff = diff or (a xor b)
        }
        return diff == 0
    }

    /** Constant, so a hash never reveals anything about the value. */
    override fun hashCode(): Int = 0

    /** The value, or [IllegalStateException] once [close] ran. The message never holds the value. */
    private fun open(): CharArray {
        check(!closed) { "The secret is closed" }
        return chars
    }

    internal companion object {
        /** Most UTF-8 bytes one UTF-16 unit can need. */
        private const val MAX_UTF8_BYTES_PER_CHAR = 3

        /** True when every surrogate in [chars] is part of a high-low pair. */
        private fun isWellFormed(chars: CharArray): Boolean {
            var i = 0
            while (i < chars.size) {
                val c = chars[i]
                when {
                    c.isHighSurrogate() && i + 1 < chars.size && chars[i + 1].isLowSurrogate() -> i += 2
                    c.isSurrogate() -> return false
                    else -> i++
                }
            }
            return true
        }

        /**
         * Throws for a coding error ([java.nio.charset.CharacterCodingException]) or an overflow
         * of a buffer sized for the worst case, which would be a bug.
         */
        private fun requireUnderflow(result: CoderResult) {
            if (result.isError) result.throwException()
            check(result.isUnderflow) { "UTF-8 buffer too small" }
        }

        /**
         * Decodes UTF-8 [bytes] into a secret without creating an intermediate String, into one
         * buffer of the worst-case size (a UTF-8 byte gives at most one UTF-16 unit), zeroed after.
         *
         * @throws java.nio.charset.CharacterCodingException when [bytes] is not valid UTF-8.
         */
        fun fromUtf8(bytes: ByteArray): Secret {
            val out = CharBuffer.allocate(bytes.size)
            try {
                val decoder =
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                requireUnderflow(decoder.decode(ByteBuffer.wrap(bytes), out, true))
                requireUnderflow(decoder.flush(out))
                out.flip()
                return Secret(out)
            } finally {
                out.array().fill('\u0000')
            }
        }
    }
}
