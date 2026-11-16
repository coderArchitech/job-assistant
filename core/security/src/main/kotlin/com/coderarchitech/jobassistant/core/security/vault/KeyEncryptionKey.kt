package com.coderarchitech.jobassistant.core.security.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.google.crypto.tink.Aead
import com.google.crypto.tink.integration.android.AndroidKeystore
import javax.crypto.KeyGenerator

/**
 * The key that wraps the vault's Tink keyset. Tests substitute a plain Tink AEAD; the app uses
 * [AndroidKeystoreKeyEncryptionKey]. Every call may block, so call it off the main thread.
 */
internal interface KeyEncryptionKey {
    /** An AEAD over the existing key, or null when there is no key. Never creates one. */
    fun existing(): Aead?

    /** Creates a new key, replacing any existing one, and returns an AEAD over it. */
    fun create(): Aead

    /** Deletes the key; does nothing when there is none. */
    fun delete()
}

/**
 * An AES-256-GCM key in the Android Keystore under [alias], StrongBox-backed where the device has
 * StrongBox. It needs no user authentication because background runs decrypt credentials with
 * nobody present (see "Vault design" in docs/TECHNICAL_DESIGN.md). The key material never leaves
 * the Keystore, and the Keystore picks a random IV for every encryption.
 */
internal class AndroidKeystoreKeyEncryptionKey(
    private val alias: String = DEFAULT_ALIAS,
) : KeyEncryptionKey {
    override fun existing(): Aead? = if (AndroidKeystore.hasKey(alias)) AndroidKeystore.getAead(alias) else null

    override fun create(): Aead {
        delete()
        try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
        return AndroidKeystore.getAead(alias)
    }

    override fun delete() {
        if (AndroidKeystore.hasKey(alias)) AndroidKeystore.deleteKey(alias)
    }

    private fun generate(strongBox: Boolean) {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .setIsStrongBoxBacked(strongBox)
                .build()
        KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        /** Keystore alias of the vault's key-encryption key. */
        const val DEFAULT_ALIAS = "jobassistant_vault_kek"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
    }
}
