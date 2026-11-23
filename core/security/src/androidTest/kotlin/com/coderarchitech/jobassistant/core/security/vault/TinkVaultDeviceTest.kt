package com.coderarchitech.jobassistant.core.security.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.security.log.SecretRegistry
import com.coderarchitech.jobassistant.core.security.vault.proto.VaultProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.ProviderException

/** The vault with the real Android Keystore key, under a test-only alias deleted after each test. */
@RunWith(AndroidJUnit4::class)
class TinkVaultDeviceTest {
    private val alias = "jobassistant_vault_kek_devicetest"
    private val kek = AndroidKeystoreKeyEncryptionKey(alias)
    private lateinit var dir: File
    private val file: File get() = File(dir, VAULT_FILE_NAME)

    private val portalA = VaultSlot.portal(1)
    private val portalB = VaultSlot.portal(2)
    private val aiKey = VaultSlot.aiKey(AiProvider.OPENAI)

    /** One app process: a vault over its own DataStore, closed by [close]. */
    private class Store(
        file: File,
        kek: KeyEncryptionKey,
    ) {
        private val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = createVaultDataStore(scope) { file }
        val vault: Vault = TinkVault(dataStore, kek, Dispatchers.IO, SecretRegistry())

        suspend fun stored(): VaultProto = dataStore.data.first()

        /** Ends the DataStore's scope so another store may open the same file. */
        suspend fun close() = scope.coroutineContext.job.cancelAndJoin()
    }

    private fun open() = Store(file, kek)

    private fun found(value: String) = VaultRead.Found(Secret(value))

    private fun keystoreHasAlias(): Boolean =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

    @Before
    fun setUp() {
        kek.delete()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "vault-device-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        kek.delete()
        dir.deleteRecursively()
    }

    @Test
    fun doneWhen_roundTripWithTheKeystoreKey() =
        runTest {
            val values =
                mapOf(
                    portalA to "fake-password-1",
                    portalB to "fake pässwörd ✓ 🔑",
                    aiKey to "fake-api-key-not-real",
                    VaultSlot.portal(3) to "fake-" + "long-".repeat(2_000),
                    VaultSlot.portal(4) to "",
                )
            val first = open()
            for ((slot, value) in values) assertEquals(slot.name, VaultPut.Stored, first.vault.put(slot, Secret(value)))
            for ((slot, value) in values) assertEquals(slot.name, found(value), first.vault.get(slot))
            assertTrue(keystoreHasAlias())
            first.close()

            // A restart: the keyset must be unwrapped again with the Keystore key.
            val second = open()
            for ((slot, value) in values) {
                assertEquals(
                    "after restart ${slot.name}",
                    found(value),
                    second.vault.get(slot),
                )
            }
            assertEquals(values.keys, second.vault.slots())
            second.close()
        }

    @Test
    fun doneWhen_aValueCopiedToAnotherSlotFailsToDecrypt() =
        runTest {
            val store = open()
            store.vault.put(portalA, Secret("fake-password-1"))
            store.vault.put(portalB, Secret("fake-password-2"))

            val ciphertextOfA = store.stored().entriesMap.getValue(portalA.name)
            store.dataStore.updateData { it.toBuilder().putEntries(portalB.name, ciphertextOfA).build() }

            assertEquals(VaultRead.Unreadable, store.vault.get(portalB))
            assertEquals(found("fake-password-1"), store.vault.get(portalA))
            store.close()
        }

    @Test
    fun wipeDeletesTheKeystoreKeyAndTheNextPutWorks() =
        runTest {
            val store = open()
            store.vault.put(portalA, Secret("fake-password-1"))
            assertTrue(keystoreHasAlias())

            store.vault.wipe()
            assertFalse("alias deleted", keystoreHasAlias())
            assertEquals(VaultRead.Missing, store.vault.get(portalA))
            assertEquals(emptySet<VaultSlot>(), store.vault.slots())
            assertFalse("get does not create a key", keystoreHasAlias())

            assertEquals(VaultPut.Stored, store.vault.put(portalB, Secret("fake-password-2")))
            assertTrue(keystoreHasAlias())
            assertEquals(found("fake-password-2"), store.vault.get(portalB))
            store.close()

            val reopened = open()
            assertEquals(found("fake-password-2"), reopened.vault.get(portalB))
            reopened.close()
        }

    @Test
    fun whenTheKeystoreKeyIsDeletedOldValuesAreUnreadableAndPutRecovers() =
        runTest {
            val first = open()
            first.vault.put(portalA, Secret("fake-password-1"))
            first.vault.put(aiKey, Secret("fake-api-key-not-real"))
            first.close()

            kek.delete()
            assertFalse(keystoreHasAlias())

            val second = open()
            assertEquals(VaultRead.Unreadable, second.vault.get(portalA))
            assertFalse("get does not create a key", keystoreHasAlias())

            assertEquals(
                VaultPut.StoredAfterReset(setOf(portalA, aiKey)),
                second.vault.put(portalB, Secret("fake-password-2")),
            )
            assertTrue(keystoreHasAlias())
            assertEquals(found("fake-password-2"), second.vault.get(portalB))
            assertEquals(VaultRead.Missing, second.vault.get(portalA))
            assertEquals(setOf(portalB), second.vault.slots())
            second.close()

            val third = open()
            assertEquals(found("fake-password-2"), third.vault.get(portalB))
            third.close()
        }

    @Test
    fun aPermanentlyInvalidatedKeyIsNeverATransientFailure() {
        val invalidated = KeyPermanentlyInvalidatedException("fake")
        assertFalse(invalidated.isTransientKeystoreFailure())
        assertFalse(ProviderException("fake", invalidated).isTransientKeystoreFailure())
        assertTrue(ProviderException("fake").isTransientKeystoreFailure())
    }
}
