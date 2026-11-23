package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.security.log.SecretRegistry
import com.coderarchitech.jobassistant.core.security.scan.RegistrySecretScanner
import com.coderarchitech.jobassistant.core.security.vault.Secret
import com.coderarchitech.jobassistant.core.security.vault.Vault
import com.coderarchitech.jobassistant.core.security.vault.VaultException
import com.coderarchitech.jobassistant.core.security.vault.VaultPut
import com.coderarchitech.jobassistant.core.security.vault.VaultRead
import com.coderarchitech.jobassistant.core.security.vault.VaultSlot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/** Every secret here is obviously fake (CLAUDE.md rule 3). */
class PromptGuardTest {
    private val registry = SecretRegistry()
    private val vault = InMemoryVault()
    private val provider = FakeLlmProvider(LlmProviderContract.Scenario.SUCCESS)
    private val guarded = GuardedLlmProvider(provider, RegistrySecretScanner(vault, registry))
    private val logged = mutableListOf<String>()

    @Before
    fun captureLogs() {
        Timber.plant(
            object : Timber.Tree() {
                override fun log(
                    priority: Int,
                    tag: String?,
                    message: String,
                    t: Throwable?,
                ) {
                    logged += message
                }
            },
        )
    }

    @After
    fun releaseLogs() = Timber.uprootAll()

    @Serializable
    data class Verdict(
        val ok: Boolean,
    )

    private val schema =
        OutputSchema(
            name = "verdict",
            jsonSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("ok") { put("type", "boolean") } }
                    putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("ok")) }
                    put("additionalProperties", false)
                },
            serializer = Verdict.serializer(),
        )

    private fun request(
        system: String = "You score jobs.",
        context: List<String> = listOf("Profile: Kotlin engineer."),
        input: List<String> = listOf("Job: Android developer in Pune."),
    ) = LlmRequest(
        task = AiTask.SCORE,
        system = system,
        context = context.map(Part::Text),
        input = input.map(Part::Text),
        schema = schema,
        maxOutputTokens = 100,
    )

    private suspend fun store(
        slot: VaultSlot,
        value: String,
    ) = Secret(value).use { vault.put(slot, it) }

    /** Task T1.15 Done when: a request containing a stored password is blocked. */
    @Test
    fun aRequestContainingAStoredPasswordIsBlocked() =
        runTest {
            store(VaultSlot.portal(12), "fake-portal-password-91")

            val result =
                guarded.generate(
                    request(input = listOf("Job text. Log in with fake-portal-password-91 please.")),
                )

            assertEquals(LlmResult.Blocked(BlockReason.SECRET_IN_PROMPT), result)
            assertTrue("the provider was never called", provider.requests.isEmpty())
        }

    @Test
    fun aSecretIsFoundInTheSystemPromptTheContextAndTheInput() =
        runTest {
            store(VaultSlot.portal(1), "fake-portal-password-91")

            for (
            leaky in
            listOf(
                request(system = "Use fake-portal-password-91 here"),
                request(context = listOf("fine", "and fake-portal-password-91")),
                request(input = listOf("ok", "fake-portal-password-91")),
            )
            ) {
                assertEquals(LlmResult.Blocked(BlockReason.SECRET_IN_PROMPT), guarded.generate(leaky))
            }
            assertTrue(provider.requests.isEmpty())
        }

    @Test
    fun theUsersAiKeyCannotBeSentInAPromptEither() =
        runTest {
            store(VaultSlot.aiKey(AiProvider.ANTHROPIC), "sk-ant-api03-FAKEFAKEFAKEFAKE")

            val result = guarded.generate(request(input = listOf("key sk-ant-api03-FAKEFAKEFAKEFAKE")))

            assertEquals(LlmResult.Blocked(BlockReason.SECRET_IN_PROMPT), result)
        }

    @Test
    fun theLastOtpIsBlocked() =
        runTest {
            registry.register("otp", "48213957".toCharArray())

            val result = guarded.generate(request(input = listOf("The page says: your code is 48213957")))

            assertEquals(LlmResult.Blocked(BlockReason.SECRET_IN_PROMPT), result)
            assertTrue(provider.requests.isEmpty())
        }

    @Test
    fun aCleanRequestIsSentOnce() =
        runTest {
            store(VaultSlot.portal(12), "fake-portal-password-91")

            val result = guarded.generate(request())

            assertFalse(result is LlmResult.Blocked)
            assertEquals(1, provider.requests.size)
        }

    @Test
    fun secretsStoredBeforeTheProcessStartedAreFoundAfterARestart() =
        runTest {
            // A fresh registry, as after a restart: the vault holds the value but nothing has read it yet.
            vault.preload(VaultSlot.portal(7), "fake-old-password-55")

            val result = guarded.generate(request(input = listOf("fake-old-password-55")))

            assertEquals(LlmResult.Blocked(BlockReason.SECRET_IN_PROMPT), result)
        }

    @Test
    fun anUnreadableVaultBlocksTheCallAndTheNextCallTriesAgain() =
        runTest {
            vault.preload(VaultSlot.portal(7), "fake-old-password-55")
            vault.failing = true

            assertEquals(LlmResult.Blocked(BlockReason.SECRET_CHECK_UNAVAILABLE), guarded.generate(request()))
            assertTrue("nothing was sent while the vault was unreadable", provider.requests.isEmpty())

            vault.failing = false
            assertEquals(
                LlmResult.Blocked(BlockReason.SECRET_IN_PROMPT),
                guarded.generate(request(input = listOf("fake-old-password-55"))),
            )
            assertFalse(guarded.generate(request()) is LlmResult.Blocked)
        }

    @Test
    fun aSlotThatCanNeverBeDecryptedDoesNotBlockEverything() =
        runTest {
            vault.unreadable += VaultSlot.portal(3)

            assertFalse(guarded.generate(request()) is LlmResult.Blocked)
        }

    @Test
    fun theSecurityEventNamesTheTaskAndNeverTheSecret() =
        runTest {
            store(VaultSlot.portal(12), "fake-portal-password-91")

            guarded.generate(request(input = listOf("fake-portal-password-91")))

            val event = logged.single()
            assertTrue(event, event.contains("SCORE"))
            assertTrue(event, event.contains("portal/12"))
            assertFalse(event, event.contains("fake-portal-password-91"))
        }

    @Test
    fun keyChecksAndModelListsPassThroughUntouched() =
        runTest {
            vault.failing = true

            assertEquals(KeyCheck.Valid, guarded.validateKey())
            assertTrue(guarded.listModels() is ModelList.Available)
            assertEquals(AiProvider.ANTHROPIC, guarded.id)
        }

    /** A vault in memory. Like the real one, it registers nothing itself; the scanner does the seeding. */
    private class InMemoryVault : Vault {
        private val values = mutableMapOf<VaultSlot, String>()
        val unreadable = mutableSetOf<VaultSlot>()
        var failing = false

        fun preload(
            slot: VaultSlot,
            value: String,
        ) {
            values[slot] = value
        }

        override suspend fun put(
            slot: VaultSlot,
            secret: Secret,
        ): VaultPut {
            values[slot] = secret.useChars { String(it) }
            return VaultPut.Stored
        }

        override suspend fun get(slot: VaultSlot): VaultRead {
            if (failing) throw VaultException("Keystore unavailable")
            if (slot in unreadable) return VaultRead.Unreadable
            return values[slot]?.let { VaultRead.Found(Secret(it)) } ?: VaultRead.Missing
        }

        override suspend fun delete(slot: VaultSlot) {
            values.remove(slot)
        }

        override suspend fun slots(): Set<VaultSlot> {
            if (failing) throw VaultException("Keystore unavailable")
            return values.keys + unreadable
        }

        override suspend fun wipe() = values.clear()
    }
}
