package com.coderarchitech.jobassistant.core.security.vault

/**
 * Encrypted on-device storage for secrets: portal credentials and AI API keys.
 *
 * Values are encrypted with Tink AES-256-GCM, bound to their [VaultSlot], and stored in
 * `vault.pb`. The Tink keyset is stored there too, wrapped by an Android Keystore key that never
 * leaves the device.
 *
 * Failures, and what a caller does about them:
 * - [VaultRead.Unreadable]: permanent. The value can never be decrypted; ask the user to enter it
 *   again.
 * - [VaultException]: the Keystore could not be reached or failed, which is often transient.
 *   Nothing was lost; retry later. (From [put] it can also mean the key material is unusable; see
 *   there.)
 * - [java.io.IOException]: `vault.pb` could not be read or written. Nothing was lost; retry later.
 * - [VaultException] from [put], [get] or [delete] when `vault.pb` was written by a newer app
 *   version: nothing is read or changed. Update the app, or [wipe] the vault.
 */
interface Vault {
    /**
     * Stores [secret] under [slot], replacing any earlier value. Creates the keyset on first use.
     *
     * If the stored keyset's Keystore key is gone (for example `vault.pb` came back from a backup
     * onto another device), or values are stored with no keyset at all, the old values can never
     * be read again. They are dropped and a new keyset and key are created before [secret] is
     * stored, and the result is [VaultPut.StoredAfterReset] naming the slots that were lost.
     *
     * The old values are dropped before the new key is created, so a crash part-way leaves an
     * empty vault rather than a keyset no key can open. If creating the key then fails, [put]
     * throws [VaultException] and those already-unreadable values stay dropped; [secret] is not
     * stored.
     *
     * @throws VaultException when the Keystore cannot be reached (retry later), when the Keystore
     *   key exists but the stored keyset cannot be unwrapped (nothing is changed; [wipe] resets
     *   the vault), or when creating a new key fails as described above.
     */
    suspend fun put(
        slot: VaultSlot,
        secret: Secret,
    ): VaultPut

    /**
     * The value stored under [slot]. Never creates a key or keyset and never changes `vault.pb`.
     *
     * @throws VaultException when the Keystore cannot be reached right now; retry later. A value
     *   that is provably undecryptable is [VaultRead.Unreadable] instead.
     */
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
    /** The slot holds [secret]; the caller owns it and closes it once done with the value. */
    data class Found(
        val secret: Secret,
    ) : VaultRead

    /** Nothing is stored under the slot. */
    data object Missing : VaultRead

    /**
     * A value is stored but can never be decrypted: it was tampered with, copied from another
     * slot, or the key that protects it is gone or permanently invalidated. This is permanent;
     * the user has to enter the secret again.
     */
    data object Unreadable : VaultRead
}

/** Result of [Vault.put]. */
sealed interface VaultPut {
    /** The secret was stored; no other value was touched. */
    data object Stored : VaultPut

    /**
     * The secret was stored, but only after starting a new vault because the old key was gone.
     * The values in [lost] were dropped (they were unreadable already); the user has to enter
     * them again. [lost] never contains the slot just written and may be empty.
     */
    data class StoredAfterReset(
        val lost: Set<VaultSlot>,
    ) : VaultPut
}

/**
 * The Keystore could not be reached (retry later), `vault.pb` was written by a newer app version,
 * or, from [Vault.put], the vault's key material is unusable. See [Vault].
 */
class VaultException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
