package com.coderarchitech.jobassistant.core.security.log

import android.util.Log
import com.coderarchitech.jobassistant.core.security.vault.Secret
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/** Every value here is obviously fake (CLAUDE.md rule 3). */
class RedactionTest {
    private val registry = SecretRegistry()
    private val redactor = Redactor(registry)
    private val lines = mutableListOf<Triple<Int, String, String>>()
    private val output get() = lines.joinToString("\n") { it.third }

    @Before
    fun plant() {
        Timber.plant(
            RedactingTree(redactor, Log.DEBUG) { priority, tag, message ->
                lines +=
                    Triple(priority, tag, message)
            },
        )
    }

    @After
    fun uproot() = Timber.uprootAll()

    @Test
    fun doneWhen_aKnownSecretLoggedThroughTimberIsRedacted() {
        Secret("fake-portal-password-123").use { registry.register("portal/1", it) }

        Timber.i("Logging in with fake-portal-password-123 for portal 1")

        assertEquals(listOf("Logging in with [REDACTED] for portal 1"), lines.map { it.third })
        assertFalse(output.contains("fake-portal-password-123"))
    }

    @Test
    fun aSecretInsideAnExceptionsStackTraceIsRedacted() {
        Secret("fake-portal-password-123").use { registry.register("portal/1", it) }

        Timber.e(IllegalStateException("login failed for fake-portal-password-123"), "Login failed")

        assertTrue(output.contains("Login failed"))
        assertTrue(output.contains("IllegalStateException"))
        assertFalse(output.contains("fake-portal-password-123"))
    }

    @Test
    fun patternsCatchSecretsTheRegistryDoesNotHold() {
        val cases =
            mapOf(
                "x-api-key: sk-ant-api03-FAKEFAKEFAKEFAKE" to "x-api-key: [REDACTED]",
                "Authorization: Bearer FAKE.TOKEN.VALUE" to "Authorization: [REDACTED]",
                "authorization=Basic ZmFrZTpmYWtl" to "authorization=[REDACTED]",
                "Cookie: session=fake123; other=fake456" to "Cookie: [REDACTED]",
                "Set-Cookie: sid=fake; Path=/; HttpOnly" to "Set-Cookie: [REDACTED]",
                "key sk-proj-FAKEFAKEFAKEFAKEFAKE in a sentence" to "key [REDACTED] in a sentence",
                "token Bearer FAKE-token_value" to "token Bearer [REDACTED]",
                "got ya29.FAKE-google-token from Google" to "got [REDACTED] from Google",
                """{"user":"someone","password":"fake\"pw"}""" to """{"user":"someone","password":"[REDACTED]"}""",
                """{"otp": "123456"}""" to """{"otp": "[REDACTED]"}""",
                "GET /x?api_key=FAKE123&page=2" to "GET /x?api_key=[REDACTED]&page=2",
                "POST body password=fake&user=someone" to "POST body password=[REDACTED]&user=someone",
            )
        for ((input, expected) in cases) {
            assertEquals(input, expected, redactor.redact(input))
        }
    }

    @Test
    fun anyFieldNameContainingASecretWordIsRedacted() {
        val cases =
            mapOf(
                """{"accessToken":"fake-abc"}""" to """{"accessToken":"[REDACTED]"}""",
                """{"client_secret": "fake-abc"}""" to """{"client_secret": "[REDACTED]"}""",
                """{"otp": 482139, "n": 1}""" to """{"otp": "[REDACTED]", "n": 1}""",
                "password=fake&user=someone" to "password=[REDACTED]&user=someone",
                "?session_token=fake1&x=1" to "?session_token=[REDACTED]&x=1",
            )
        for ((input, expected) in cases) {
            assertEquals(input, expected, redactor.redact(input))
        }
    }

    @Test
    fun plainKeyAndCodeFieldsAreOrdinaryValues() {
        val text = """{"key":"cache-1","code":404} ?key=cache-2&code=200"""
        assertEquals(text, redactor.redact(text))
    }

    @Test
    fun valuesPrintedByToStringAreRedacted() {
        val cases =
            mapOf(
                "LoginRequest(user=someone, password=fake1)" to "LoginRequest(user=someone, password=[REDACTED])",
                "{token=fake2, page=1}" to "{token=[REDACTED], page=1}",
                "[otp=482139]" to "[otp=[REDACTED]]",
            )
        for ((input, expected) in cases) {
            assertEquals(input, expected, redactor.redact(input))
        }
    }

    @Test
    fun usageCountsAndWordsContainingOtpAreNotSecrets() {
        val text = """{"maxTokens":4096,"tokens_used":12,"footprint":"x","hotpath":1} maxTokens=4096"""
        assertEquals(text, redactor.redact(text))
        assertEquals("""{"userOtp":"[REDACTED]"}""", redactor.redact("""{"userOtp":"482139"}"""))
    }

    @Test
    fun longTextWithoutSecretsIsRedactedInLinearTime() {
        val blob = "a".repeat(200_000) + "token"

        val start = System.nanoTime()
        redactor.redact(blob)

        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", System.nanoTime() - start < 5_000_000_000)
    }

    @Test
    fun longMessagesSplitAtLineBreaks() {
        val line = "x".repeat(99) + "\n"
        val parts = RedactingTree.chunk(line.repeat(40))

        assertTrue(parts.size > 1)
        assertTrue("splits after a newline", parts.dropLast(1).all { it.endsWith("\n") })
        assertEquals(line.repeat(40), parts.joinToString(""))
    }

    @Test
    fun longMessagesAreNeverSplitInsideASurrogatePair() {
        val emoji = "😀".repeat(1_000)
        val emojiParts = RedactingTree.chunk(emoji)
        assertEquals(emoji, emojiParts.joinToString(""))
        assertTrue(emojiParts.all { it.isNotEmpty() && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test
    fun theTagIsRedactedToo() {
        Secret("fake-tag-secret-1").use { registry.register("t", it) }
        Timber.tag("fake-tag-secret-1").w("hello")
        assertEquals("[REDACTED]", lines.single().second)
    }

    @Test
    fun unregisterAllForgetsOnlyKeysWithThePrefix() {
        Secret("fake-vault-secret").use { registry.register("vault:A", it) }
        Secret("fake-otp-code-123").use { registry.register("otp", it) }

        registry.unregisterAll("vault:")

        assertEquals("fake-vault-secret [REDACTED]", redactor.redact("fake-vault-secret fake-otp-code-123"))
    }

    @Test
    fun ordinaryTextIsLeftAlone() {
        val text = "Scored 42 jobs in 3 batches; 7 High, 12 Partial. Keyboard shortcut: Ctrl+K. Monkey."
        Secret("fake-portal-password-123").use { registry.register("portal/1", it) }
        assertEquals(text, redactor.redact(text))
    }

    @Test
    fun registryFollowsTheVaultLifecycle() {
        Secret("fake-first-pass").use { registry.register("portal/1", it) }
        Secret("fake-second-pass").use { registry.register("portal/2", it) }
        assertEquals("[REDACTED] [REDACTED]", redactor.redact("fake-first-pass fake-second-pass"))

        // A new value for the slot replaces the old one.
        Secret("fake-new-pass").use { registry.register("portal/1", it) }
        assertEquals("fake-first-pass [REDACTED]", redactor.redact("fake-first-pass fake-new-pass"))

        registry.unregister("portal/1")
        assertEquals("fake-new-pass [REDACTED]", redactor.redact("fake-new-pass fake-second-pass"))

        registry.unregisterAll("portal/")
        assertEquals("fake-second-pass", redactor.redact("fake-second-pass"))
    }

    @Test
    fun theRegistryKeepsItsOwnCopy() {
        val otp = "48213957".toCharArray()
        registry.register("otp", otp)
        otp.fill('\u0000')
        // The caller zeroed its array; the registry still redacts the code.
        assertEquals("code [REDACTED]", redactor.redact("code 48213957"))
    }

    @Test
    fun theLongerOfTwoOverlappingSecretsWins() {
        Secret("fake-pass").use { registry.register("a", it) }
        Secret("fake-pass-extended").use { registry.register("b", it) }
        assertEquals("[REDACTED]!", redactor.redact("fake-pass-extended!"))
    }

    @Test
    fun valuesShorterThanTheMinimumAreNotRedacted() {
        Secret("abc").use { registry.register("short", it) }
        assertEquals("abc", redactor.redact("abc"))
    }

    @Test
    fun linesBelowTheMinimumPriorityAreDropped() {
        Timber.uprootAll()
        Timber.plant(
            RedactingTree(redactor, Log.INFO) { priority, tag, message ->
                lines +=
                    Triple(priority, tag, message)
            },
        )

        Timber.d("debug detail")
        Timber.v("verbose detail")
        Timber.w("warning")

        assertEquals(listOf(Log.WARN to "warning"), lines.map { it.first to it.third })
    }

    @Test
    fun aMissingTagGetsTheAppTag() {
        Timber.w("no tag here")
        assertEquals("JobAssistant", lines.single().second)
    }
}
