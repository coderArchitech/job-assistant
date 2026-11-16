package com.coderarchitech.jobassistant.core.security.vault

/**
 * Encrypted on-device storage for secrets: portal credentials and AI API keys.
 *
 * Values are encrypted with Tink AES-256-GCM, bound to their [VaultSlot], and stored in
 * `vault.pb`. The Tink keyset is stored there too, wrapped by an Android Keystore key that never
 * leaves the device. Every function may throw [java.io.IOException] when `vault.pb` cannot be
 * read or written; that is a transient failure, not data loss.
 */
interface Vault {
    /**
     * Stores [secret] under [slot], replacing any earlier value. Creates the keyset on first use.
     *
     * If the Keystore key is gone (for example `vault.pb` came back from a backup onto another
     * device), the old values can never be read again: they are dropped and a new keyset and key
     * are created before [secret] is stored.
     *
     * @throws VaultException when the Keystore key exists but the stored keyset cannot be
     *   unwrapped, or the Keystore fails. Nothing is changed then; [wipe] resets the vault.
     */
    suspend fun put(
        slot: VaultSlot,
        secret: Secret,
    )

    /** The value stored under [slot]. Never creates a key or keyset. */
    suspend fun get(slot: VaultSlot): VaultRead

    /** Removes the value stored under [slot]; does nothing when there is none. */
    suspend fun delete(slot: VaultSlot)

    /** The slots that hold a value, whether or not it can still be decrypted. */
    suspend fun slots(): Set<VaultSlot>

    /**
     * Deletes every value, the wrapped keyset and the Keystore key. The next [put] starts a fresh
     * keyset under a fresh key.
     */
    suspend fun wipe()
}

/** Result of [Vault.get]. */
sealed interface VaultRead {
    /** The slot holds [secret]. */
    data class Found(
        val secret: Secret,
    ) : VaultRead

    /** Nothing is stored under the slot. */
    data object Missing : VaultRead

    /**
     * A value is stored but cannot be decrypted: it was tampered with, copied from another slot,
     * or the key that protects it is gone. The user has to enter the secret again.
     */
    data object Unreadable : VaultRead
}

/** The vault could not be written because its key material is unusable. See [Vault.put]. */
class VaultException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
