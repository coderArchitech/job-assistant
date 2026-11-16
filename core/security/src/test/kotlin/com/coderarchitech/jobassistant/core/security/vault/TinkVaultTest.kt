package com.coderarchitech.jobassistant.core.security.vault

import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.security.vault.proto.VaultProto
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TinkVaultTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val file: File by lazy { File(tempFolder.root, VAULT_FILE_NAME) }

    /** The key that survives a restart, as the Keystore key does. */
    private val kek = FakeKek()

    private val portalA = VaultSlot.portal(1)
    private val portalB = VaultSlot.portal(2)
    private val aiKey = VaultSlot.aiKey(AiProvider.ANTHROPIC)

    /** One app process: a vault over its own DataStore, closed by [close]. */
    private class Store(
        testScope: TestScope,
        file: File,
        kek: KeyEncryptionKey,
    ) {
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        private val scope = CoroutineScope(dispatcher + Job())
        val dataStore = createVaultDataStore(scope) { file }
        val vault: Vault = TinkVault(dataStore, kek, dispatcher)

        suspend fun stored(): VaultProto = dataStore.data.first()

        /** Changes `vault.pb` behind the vault's back, as an attacker or a bad restore would. */
        suspend fun rewrite(change: VaultProto.Builder.() -> Unit) {
            dataStore.updateData { it.toBuilder().apply(change).build() }
        }

        /** Ends the DataStore's scope so another store may open the same file. */
        suspend fun close() = scope.coroutineContext.job.cancelAndJoin()
    }

    private fun TestScope.open(kek: KeyEncryptionKey = this@TinkVaultTest.kek) = Store(this, file, kek)

    private fun found(value: String) = VaultRead.Found(Secret(value))

    @Test
    fun putThenGetReturnsEachValue() =
        runTest {
            val values =
                mapOf(
                    portalA to "fake-password-1",
                    portalB to "",
                    aiKey to "fake-api-key-not-real",
                    VaultSlot.portal(3) to "fake pässwörd ✓ 🔑",
                    VaultSlot.portal(4) to "fake-" + "long-".repeat(2_000),
                )
            val store = open()
            for ((slot, value) in values) store.vault.put(slot, Secret(value))
            for ((slot, value) in values) assertEquals(slot.name, found(value), store.vault.get(slot))
            store.close()
        }

    @Test
    fun putReplacesTheEarlierValue() =
        runTest {
            val store = open()
            store.vault.put(portalA, Secret("fake-password-1"))
            store.vault.put(portalA, Secret("fake-password-2"))
            assertEquals(found("fake-password-2"), store.vault.get(portalA))
            assertEquals(setOf(portalA), store.vault.slots())
            store.close()
        }

    @Test
    fun deleteRemovesOnlyThatSlot() =
        runTest {
            val store = open()
            store.vault.put(portalA, Secret("fake-password-1"))
            store.vault.put(portalB, Secret("fake-password-2"))

            store.vault.delete(portalA)
            assertEquals(VaultRead.Missing, store.vault.get(portalA))
            assertEquals(found("fake-password-2"), store.vault.get(portalB))

            val before = store.stored()
            store.vault.delete(portalA)
            store.vault.delete(aiKey)
            assertEquals("deleting an empty slot changes nothing", before, store.stored())
            store.close()
        }

    @Test
    fun getOfASlotNeverWrittenIsMissing() =
        runTest {
            val store = open()
            assertEquals("before any keyset", VaultRead.Missing, store.vault.get(portalA))
            store.vault.put(portalB, Secret("fake-password-2"))
            assertEquals("with a keyset", VaultRead.Missing, store.vault.get(portalA))
            store.close()
        }

    @Test
    fun slotsListsStoredSlotsAndSkipsInvalidNames() =
        runTest {
            val store = open()
            assertEquals(emptySet<VaultSlot>(), store.vault.slots())

            store.vault.put(portalA, Secret("fake-password-1"))
            store.vault.put(portalB, Secret("fake-password-2"))
            store.vault.put(aiKey, Secret("fake-api-key-not-real"))
            store.vault.delete(portalB)
            store.rewrite { putEntries("Not A Slot", ByteString.copyFromUtf8("fake-junk")) }

            assertEquals(setOf(portalA, aiKey), store.vault.slots())
            store.close()
        }

    @Test
    fun doneWhen_aValueCopiedToAnotherSlotFailsToDecrypt() =
        runTest {
            val store = open()
            store.vault.put(portalA, Secret("fake-password-1"))
            store.vault.put(portalB, Secret("fake-password-2"))

            val ciphertextOfA = store.stored().entriesMap.getValue(portalA.name)
            store.rewrite { putEntries(portalB.name, ciphertextOfA) }

            assertEquals(VaultRead.Unreadable, store.vault.get(portalB))
            assertEquals(found("fake-password-1"), store.vault.get(portalA))

            // A brand-new slot is no different from an existing one.
            store.rewrite { putEntries(aiKey.name, ciphertextOfA) }
            assertEquals(VaultRead.Unreadable, store.vault.get(aiKey))
            store.close()
        }

    @Test
    fun aTamperedCiphertextIsUnreadable() =
        runTest {
            val store = open()
            store.vault.put(portalA, Secret("fake-password-1"))
            val original = store.stored().entriesMap.getValue(portalA.name)
            val tampered =
                mapOf(
                    "first byte flipped" to original.flipped(0),
                    "middle byte flipped" to original.flipped(original.size() / 2),
                    "last byte flipped" to original.flipped(original.size() - 1),
                    "truncated" to original.substring(0, original.size() - 1),
                    "extended" to original.concat(ByteString.copyFrom(byteArrayOf(0))),
                    "empty" to ByteString.EMPTY,
                )
            for ((name, bytes) in tampered) {
                store.rewrite { putEntries(portalA.name, bytes) }
                assertEquals(name, VaultRead.Unreadable, store.vault.get(portalA))
            }
            store.close()
        }

    @Test
    fun aTamperedKeysetIsUnreadableAndPutFailsWithoutChangingAnything() =
        runTest {
            val first = open()
            first.vault.put(portalA, Secret("fake-password-1"))
            first.rewrite { encryptedKeyset = encryptedKeyset.flipped(encryptedKeyset.size() / 2) }
            first.close()

            // A restart, so the keyset is not cached in memory.
            val second = open()
            val before = second.stored()
            assertEquals(VaultRead.Unreadable, second.vault.get(portalA))

            var thrown: VaultException? = null
            try {
                second.vault.put(portalB, Secret("fake-password-2"))
            } catch (e: VaultException) {
                thrown = e
            }
            assertTrue("put throws VaultException", thrown != null)
            assertEquals("put changes nothing", before, second.stored())
            assertEquals("no new key", 1, kek.createCount)
            assertEquals(setOf(portalA), second.vault.slots())
            second.close()
        }

    @Test
    fun valuesSurviveARestartWithTheSameKey() =
        runTest {
            val first = open()
            first.vault.put(portalA, Secret("fake-password-1"))
            first.vault.put(aiKey, Secret("fake-api-key-not-real"))
            first.close()

            val second = open()
            assertEquals(found("fake-password-1"), second.vault.get(portalA))
            assertEquals(found("fake-api-key-not-real"), second.vault.get(aiKey))
            assertEquals(setOf(portalA, aiKey), second.vault.slots())
            second.vault.put(portalB, Secret("fake-password-2"))
            second.close()

            val third = open()
            assertEquals(found("fake-password-2"), third.vault.get(portalB))
            assertEquals("the keyset was reused", 1, kek.createCount)
            third.close()
        }

    @Test
    fun whenTheKeyIsLostOldValuesAreUnreadableAndPutStartsOver() =
        runTest {
            val ways =
                listOf<Pair<String, () -> KeyEncryptionKey>>(
                    "key deleted" to { kek.also { it.delete() } },
                    "another device" to { FakeKek() },
                )
            for ((way, lose) in ways) {
                val first = open()
                first.vault.wipe()
                first.vault.put(portalA, Secret("fake-password-1"))
                first.vault.put(aiKey, Secret("fake-api-key-not-real"))
                first.close()

                val second = open(lose())
                assertEquals(way, VaultRead.Unreadable, second.vault.get(portalA))
                assertEquals("$way: slots still listed", setOf(portalA, aiKey), second.vault.slots())

                second.vault.put(portalB, Secret("fake-password-2"))
                assertEquals(way, found("fake-password-2"), second.vault.get(portalB))
                assertEquals("$way: old values dropped", VaultRead.Missing, second.vault.get(portalA))
                assertEquals(way, setOf(portalB), second.vault.slots())
                second.close()
            }
        }

    @Test
    fun wipeDeletesEverythingAndTheNextPutUsesANewKeyset() =
        runTest {
            val first = open()
            first.vault.put(portalA, Secret("fake-password-1"))
            first.vault.put(aiKey, Secret("fake-api-key-not-real"))
            val oldKeyset = first.stored().encryptedKeyset

            first.vault.wipe()
            assertEquals(VaultRead.Missing, first.vault.get(portalA))
            assertEquals(VaultRead.Missing, first.vault.get(aiKey))
            assertEquals(emptySet<VaultSlot>(), first.vault.slots())
            assertFalse("key deleted", kek.hasKey)
            assertEquals(VaultProto.getDefaultInstance(), first.stored())

            first.vault.put(portalB, Secret("fake-password-2"))
            assertEquals(found("fake-password-2"), first.vault.get(portalB))
            assertNotEquals(oldKeyset, first.stored().encryptedKeyset)
            assertEquals(2, kek.createCount)
            first.close()

            val second = open()
            assertEquals(found("fake-password-2"), second.vault.get(portalB))
            assertEquals(VaultRead.Missing, second.vault.get(portalA))
            second.close()
        }

    @Test
    fun aCorruptFileReadsAsAnEmptyVaultAndStaysWritable() =
        runTest {
            file.writeBytes(byteArrayOf(0x0A, 0x7F, 0xFF.toByte(), 0x00, 0x13, 0x37))

            val first = open()
            assertEquals(emptySet<VaultSlot>(), first.vault.slots())
            assertEquals(VaultRead.Missing, first.vault.get(portalA))
            first.vault.put(portalA, Secret("fake-password-1"))
            first.close()

            val second = open()
            assertEquals(found("fake-password-1"), second.vault.get(portalA))
            second.close()
        }

    @Test
    fun theFileNeverHoldsThePlaintext() =
        runTest {
            val values = listOf("fake-password-1", "fake pässwörd ✓ 🔑", "fake-api-key-not-real")
            val store = open()
            values.forEachIndexed { i, value -> store.vault.put(VaultSlot.portal(i.toLong()), Secret(value)) }
            store.close()

            val raw = file.readBytes()
            for (value in values) {
                assertFalse("'$value' is in vault.pb", raw.containsBytes(value.toByteArray(Charsets.UTF_8)))
            }
            assertTrue("the slot names are stored", raw.containsBytes(VaultSlot.portal(0).name.toByteArray()))
        }

    @Test
    fun concurrentFirstPutsCreateExactlyOneKeyset() =
        runTest {
            val store = open()
            val slots = (1L..20L).map { VaultSlot.portal(it) }
            slots.map { slot -> async { store.vault.put(slot, Secret("fake-password-${slot.name}")) } }.awaitAll()

            assertEquals("one key", 1, kek.createCount)
            store.close()

            // Every value must decrypt with the one keyset left in the file, not a cached one.
            val reopened = open()
            for (slot in slots) assertEquals(found("fake-password-${slot.name}"), reopened.vault.get(slot))
            assertEquals(slots.toSet(), reopened.vault.slots())
            reopened.close()
        }

    @Test
    fun getNeverCreatesAKeyOrKeyset() =
        runTest {
            val store = open()
            assertEquals(VaultRead.Missing, store.vault.get(portalA))
            store.vault.slots()

            // An entry with no keyset, as after a bad restore.
            store.rewrite { putEntries(portalA.name, ByteString.copyFromUtf8("fake-ciphertext")) }
            assertEquals(VaultRead.Unreadable, store.vault.get(portalA))

            assertFalse(kek.hasKey)
            assertEquals(0, kek.createCount)
            assertTrue(store.stored().encryptedKeyset.isEmpty)
            store.close()
        }

    @Test
    fun getWithAKeysetButNoKeyLeavesTheFileAlone() =
        runTest {
            val first = open()
            first.vault.put(portalA, Secret("fake-password-1"))
            first.close()
            kek.delete()

            val second = open()
            val before = second.stored()
            assertEquals(VaultRead.Unreadable, second.vault.get(portalA))
            assertFalse(kek.hasKey)
            assertEquals(1, kek.createCount)
            assertEquals(before, second.stored())
            second.close()
        }
}

private fun ByteString.flipped(index: Int): ByteString {
    val bytes = toByteArray()
    bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
    return ByteString.copyFrom(bytes)
}

private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
