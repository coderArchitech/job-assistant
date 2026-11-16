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

    val hasKey: Boolean get() = aead != null

    override fun existing(): Aead? = aead

    @Synchronized
    override fun create(): Aead =
        KeysetHandle
            .generateNew(PredefinedAeadParameters.AES256_GCM, AeadConfig2026.get())
            .getPrimitive(AeadConfig2026.get(), Aead::class.java)
            .also {
                aead = it
                createCount++
            }

    override fun delete() {
        aead = null
    }
}
