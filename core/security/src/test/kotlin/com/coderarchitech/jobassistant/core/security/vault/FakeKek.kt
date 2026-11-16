package com.coderarchitech.jobassistant.core.security.vault

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.aead.PredefinedAeadParameters

/**
 * Plain Tink AEAD standing in for the Android Keystore key. Reuse one instance across a simulated
 * restart to keep the key; use a new instance to simulate a key that is gone.
 */
internal class FakeKek : KeyEncryptionKey {
    @Volatile private var aead: Aead? = null

    /** How many times [create] ran, so a test can tell that only one key was made. */
    @Volatile var createCount = 0
        private set

    /** When set, [existing] throws it, as a Keystore that cannot be reached would. */
    @Volatile var existingFailure: Exception? = null

    /** When set, decrypting with the key throws it, as a Keystore cipher operation could. */
    @Volatile var decryptFailure: Exception? = null

    val hasKey: Boolean get() = aead != null

    override fun existing(): Aead? {
        existingFailure?.let { throw it }
        return aead?.let { FailingAead(it) }
    }

    @Synchronized
    override fun create(): Aead =
        KeysetHandle
            .generateNew(PredefinedAeadParameters.AES256_GCM, AeadConfig2026.get())
            .getPrimitive(AeadConfig2026.get(), Aead::class.java)
            .also {
                aead = it
                createCount++
            }.let { FailingAead(it) }

    override fun delete() {
        aead = null
    }

    /** Delegates to [real] unless [decryptFailure] is set. */
    private inner class FailingAead(
        private val real: Aead,
    ) : Aead {
        override fun encrypt(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray = real.encrypt(plaintext, associatedData)

        override fun decrypt(
            ciphertext: ByteArray,
            associatedData: ByteArray,
        ): ByteArray {
            decryptFailure?.let { throw it }
            return real.decrypt(ciphertext, associatedData)
        }
    }
}
