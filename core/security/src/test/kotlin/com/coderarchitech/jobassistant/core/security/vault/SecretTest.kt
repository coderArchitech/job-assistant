package com.coderarchitech.jobassistant.core.security.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.CharacterCodingException

@OptIn(RevealsSecret::class)
class SecretTest {
    private val values =
        listOf(
            "fake-password-1",
            "",
            "x",
            "fake pässwörd ✓ 🔑",
            "fake-" + "long-".repeat(500),
        )

    @Test
    fun toStringNeverShowsTheValue() {
        for (value in values) {
            val secret = Secret(value)
            assertEquals("Secret(****)", secret.toString())
            assertEquals("template", "key=Secret(****)", "key=$secret")
            val found = VaultRead.Found(secret).toString()
            if (value.isNotEmpty()) {
                assertFalse("Found.toString leaks '$value'", found.contains(value))
            }
            assertTrue(found.contains("Secret(****)"))
        }
    }

    @Test
    fun revealAndLengthReturnTheValue() {
        for (value in values) {
            val secret = Secret(value)
            assertEquals(value, secret.reveal())
            assertEquals(value.length, secret.length)
        }
    }

    @Test
    fun equalsComparesValues() {
        val cases =
            listOf(
                Triple("fake-password-1", "fake-password-1", true),
                Triple("", "", true),
                Triple("fake-password-1", "fake-password-2", false),
                Triple("fake-password-1", "Fake-password-1", false),
                Triple("fake", "fake-password-1", false),
                Triple("fake", "fake\u0000", false),
                Triple("", "\u0000", false),
                Triple("", "x", false),
            )
        for ((a, b, equal) in cases) {
            assertEquals("'$a' vs '$b'", equal, Secret(a) == Secret(b))
            assertEquals("'$b' vs '$a'", equal, Secret(b) == Secret(a))
            if (equal) assertEquals(Secret(a).hashCode(), Secret(b).hashCode())
        }
        val secret = Secret("fake-password-1")
        assertNotEquals(secret, "fake-password-1")
        assertNotEquals(secret, null)
    }

    @Test
    fun hashCodeIsTheSameForEveryValue() {
        assertEquals(1, values.map { Secret(it).hashCode() }.toSet().size)
    }

    @Test
    fun aSecretCopiesItsInput() {
        val builder = StringBuilder("fake-password-1")
        val secret = Secret(builder)
        builder.setCharAt(0, 'X')
        assertEquals("fake-password-1", secret.reveal())
    }

    @Test
    fun utf8RoundTrip() {
        for (value in values) {
            val bytes = Secret(value).encodeUtf8()
            assertArrayEquals(value.toByteArray(Charsets.UTF_8), bytes)
            assertEquals(Secret(value), Secret.fromUtf8(bytes))
        }
    }

    @Test
    fun malformedTextIsReportedNotReplaced() {
        // Invalid text is refused up front, so the vault never sees a value it cannot store.
        for (bad in listOf("fake-\uD800-lone-high", "fake-\uDC00-lone-low", "fake-end-\uD800", "\uDC00\uD800")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Secret(bad) }
        }
        // A proper surrogate pair (an emoji) is fine and survives UTF-8.
        val emoji = Secret("fake-\uD83D\uDE00-pass")
        assertEquals(emoji, Secret.fromUtf8(emoji.encodeUtf8()))
        assertThrowsCoding { Secret.fromUtf8(byteArrayOf(0x66, 0xC3.toByte(), 0x28)) }
    }

    @Test
    fun utf8EncodingMatchesTheJdkForEveryWidth() {
        val cases =
            mapOf(
                "ascii" to "fake-password-1",
                "two-byte" to "fake-pass-\u00e9\u00e9",
                "three-byte (Devanagari)" to "\u0928\u0915\u0932\u0940-\u092a\u093e\u0938",
                "four-byte (emoji)" to "\uD83D\uDD11\uD83D\uDE00fake",
                "only emoji" to "\uD83D\uDD11",
                "worst case, all three-byte" to "\u0915".repeat(1_000),
                "mixed" to "fake p\u00e4ss \u0915 \uD83D\uDD11 \u2713",
                "empty" to "",
            )
        for ((name, value) in cases) {
            val bytes = Secret(value).encodeUtf8()
            assertArrayEquals(name, value.toByteArray(Charsets.UTF_8), bytes)
            assertEquals(name, Secret(value), Secret.fromUtf8(bytes))
        }
    }

    @Test
    fun closeZeroesTheValueAndBlocksEveryRead() {
        val secret = Secret("fake-password-1")
        val other = Secret("fake-password-1")
        secret.close()

        val inside = Secret::class.java.getDeclaredField("chars").apply { isAccessible = true }
        assertTrue("zeroed", (inside.get(secret) as CharArray).all { it == '\u0000' })

        val uses =
            mapOf<String, () -> Unit>(
                "reveal" to { secret.reveal() },
                "length" to { secret.length },
                "useChars" to { secret.useChars { } },
                "encodeUtf8" to { secret.encodeUtf8() },
                "equals" to { secret.equals(other) },
                "equals, closed on the other side" to { other.equals(secret) },
            )
        for ((name, use) in uses) {
            val thrown = assertThrows(name, IllegalStateException::class.java) { use() }
            assertFalse("$name: message leaks the value", thrown.message.orEmpty().contains("fake-password-1"))
        }
        assertEquals("toString still works", "Secret(****)", secret.toString())
        assertEquals("hashCode still works", other.hashCode(), secret.hashCode())
    }

    @Test
    fun closingTwiceIsFine() {
        val secret = Secret("fake-password-1")
        secret.close()
        secret.close()
        assertThrows(IllegalStateException::class.java) { secret.length }
        Secret("fake-password-2").use { assertEquals(15, it.length) }
    }

    @Test
    fun useCharsSeesTheValueAndItsCopyIsZeroedAfter() {
        val secret = Secret("fake-password-1")
        var seen: CharArray? = null
        val result =
            secret.useChars { chars ->
                assertEquals("fake-password-1", String(chars))
                seen = chars
                chars.size
            }
        assertEquals(15, result)
        val copy = seen ?: throw AssertionError("block did not run")
        assertTrue("copy zeroed", copy.all { it == '\u0000' })
        assertEquals("the secret itself is untouched", Secret("fake-password-1"), secret)

        // Changing the copy does not change the secret, and the copy is zeroed even on a throw.
        var thrownCopy: CharArray? = null
        assertThrows(IllegalStateException::class.java) {
            secret.useChars { chars ->
                chars[0] = 'X'
                thrownCopy = chars
                error("fake failure")
            }
        }
        val afterThrow = thrownCopy ?: throw AssertionError("block did not run")
        assertTrue("copy zeroed after a throw", afterThrow.all { it == '\u0000' })
        assertEquals(Secret("fake-password-1"), secret)
    }

    private fun assertThrowsCoding(block: () -> Unit) {
        var thrown: CharacterCodingException? = null
        try {
            block()
        } catch (e: CharacterCodingException) {
            thrown = e
        }
        assertTrue("expected CharacterCodingException", thrown != null)
    }
}
