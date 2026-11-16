package com.coderarchitech.jobassistant.core.security.vault

import androidx.datastore.core.DataStore
import com.coderarchitech.jobassistant.core.model.IoDispatcher
import com.coderarchitech.jobassistant.core.security.vault.proto.VaultProto
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.CharacterCodingException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Vault] over `vault.pb`, Tink AES-256-GCM and a [KeyEncryptionKey].
 *
 * The keyset is created on the first [put] and kept in memory once unwrapped. Every operation
 * holds [mutex], so a keyset is never created twice and a [wipe] never interleaves with a write.
 * JVM tests build it directly with a test [DataStore], a fake [KeyEncryptionKey] and a test
 * dispatcher.
 */
@Singleton
internal class TinkVault
    @Inject
    constructor(
        private val dataStore: DataStore<VaultProto>,
        private val kek: KeyEncryptionKey,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : Vault {
        private val mutex = Mutex()

        /** The unwrapped value AEAD; guarded by [mutex]. */
        private var valueAead: Aead? = null

        override suspend fun put(
            slot: VaultSlot,
            secret: Secret,
        ) = locked {
            val aead = valueAead ?: openOrCreateKeyset()
            val plaintext = secret.encodeUtf8()
            val ciphertext =
                try {
                    keystore("Cannot encrypt the value") { aead.encrypt(plaintext, slot.associatedData()) }
                } finally {
                    plaintext.fill(0)
                }
            dataStore.updateData { current ->
                current.toBuilder().putEntries(slot.name, ByteString.copyFrom(ciphertext)).build()
            }
            Unit
        }

        override suspend fun get(slot: VaultSlot): VaultRead =
            locked {
                val vault = dataStore.data.first()
                val ciphertext = vault.entriesMap[slot.name]
                when {
                    ciphertext == null -> VaultRead.Missing
                    else -> {
                        val aead = valueAead ?: openExisting(vault)
                        if (aead == null) VaultRead.Unreadable else decrypt(aead, ciphertext, slot)
                    }
                }
            }

        override suspend fun delete(slot: VaultSlot) =
            locked {
                dataStore.updateData { current ->
                    if (current.containsEntries(slot.name)) {
                        current.toBuilder().removeEntries(slot.name).build()
                    } else {
                        current
                    }
                }
                Unit
            }

        override suspend fun slots(): Set<VaultSlot> =
            locked {
                dataStore.data
                    .first()
                    .entriesMap.keys
                    .mapNotNullTo(mutableSetOf()) { VaultSlot.fromNameOrNull(it) }
            }

        override suspend fun wipe() =
            locked {
                valueAead = null
                dataStore.updateData { VaultSerializer.defaultValue }
                keystore("Cannot delete the vault key") { kek.delete() }
            }

        private suspend fun <T> locked(block: suspend () -> T): T =
            withContext(ioDispatcher) { mutex.withLock { block() } }

        /**
         * The value AEAD for a [put]: unwraps the stored keyset, or creates one when there is none
         * or when its Keystore key is gone (the old values are then unreadable forever).
         */
        private suspend fun openOrCreateKeyset(): Aead {
            val vault = dataStore.data.first()
            val wrapping =
                if (vault.encryptedKeyset.isEmpty) null else keystore("Cannot load the vault key") { kek.existing() }
            if (wrapping == null) return createKeyset()
            // The key exists, so a keyset it cannot open means tampering or a Keystore fault. Fail
            // closed (VaultException) rather than drop secrets that may still be readable later.
            val aead = keystore("Cannot unwrap the vault keyset") { unwrap(vault.encryptedKeyset, wrapping) }
            valueAead = aead
            return aead
        }

        /** The value AEAD for a read, or null when the keyset is missing or cannot be unwrapped. */
        private fun openExisting(vault: VaultProto): Aead? {
            if (vault.encryptedKeyset.isEmpty) return null
            val aead =
                try {
                    kek.existing()?.let { unwrap(vault.encryptedKeyset, it) }
                } catch (_: GeneralSecurityException) {
                    null
                } catch (_: ProviderException) {
                    null
                }
            valueAead = aead
            return aead
        }

        /**
         * Starts over: drops every stored value and the old keyset first, so a crash part-way
         * leaves an empty vault rather than a keyset no key can open, then creates a new key and
         * keyset.
         */
        private suspend fun createKeyset(): Aead {
            dataStore.updateData { VaultSerializer.defaultValue }
            val (aead, wrapped) =
                keystore("Cannot create the vault key") {
                    val wrapping = kek.create()
                    val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, TINK_CONFIG)
                    val wrapped =
                        TinkProtoKeysetFormat.serializeEncryptedKeyset(
                            handle,
                            wrapping,
                            KEYSET_ASSOCIATED_DATA,
                            TINK_CONFIG,
                        )
                    handle.getPrimitive(TINK_CONFIG, Aead::class.java) to wrapped
                }
            dataStore.updateData { current ->
                current
                    .toBuilder()
                    .setSchemaVersion(SCHEMA_VERSION)
                    .setEncryptedKeyset(ByteString.copyFrom(wrapped))
                    .build()
            }
            valueAead = aead
            return aead
        }
    }

private const val SCHEMA_VERSION = 1

/** Tink's AEAD configuration for keyset generation, parsing and primitives; no global registry. */
private val TINK_CONFIG = AeadConfig2026.get()

/** Associated data of the wrapped keyset; distinct from every value's associated data. */
private val KEYSET_ASSOCIATED_DATA = "jobassistant/vault/v1|keyset".toByteArray(Charsets.UTF_8)

private fun unwrap(
    encryptedKeyset: ByteString,
    wrapping: Aead,
): Aead =
    TinkProtoKeysetFormat
        .parseEncryptedKeyset(encryptedKeyset.toByteArray(), wrapping, KEYSET_ASSOCIATED_DATA, TINK_CONFIG)
        .getPrimitive(TINK_CONFIG, Aead::class.java)

private fun decrypt(
    aead: Aead,
    ciphertext: ByteString,
    slot: VaultSlot,
): VaultRead {
    val plaintext =
        try {
            aead.decrypt(ciphertext.toByteArray(), slot.associatedData())
        } catch (_: GeneralSecurityException) {
            return VaultRead.Unreadable
        }
    return try {
        VaultRead.Found(Secret.fromUtf8(plaintext))
    } catch (_: CharacterCodingException) {
        VaultRead.Unreadable
    } finally {
        plaintext.fill(0)
    }
}

/** Runs a Keystore or Tink call, turning its failures into [VaultException]. */
private inline fun <T> keystore(
    message: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: GeneralSecurityException) {
        throw VaultException(message, e)
    } catch (e: ProviderException) {
        throw VaultException(message, e)
    }

/** Associated data that binds a ciphertext to its slot, so it fails to decrypt anywhere else. */
internal fun VaultSlot.associatedData(): ByteArray = ("jobassistant/vault/v1|slot|$name").toByteArray(Charsets.UTF_8)
