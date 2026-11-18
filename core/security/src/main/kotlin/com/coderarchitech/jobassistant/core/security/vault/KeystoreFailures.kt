package com.coderarchitech.jobassistant.core.security.vault

import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.KeyStoreException
import java.security.ProviderException

/**
 * True when this failure (or one of its causes) says the Keystore could not be reached or failed
 * internally, which may pass on a retry. A permanently invalidated key is never transient: that
 * key is lost. On API 33+ an `android.security.KeyStoreException` in the chain decides alone, by
 * its `isTransientFailure` or `isSystemError`, whatever wraps it; a non-transient one means the
 * data cannot be decrypted. Otherwise a [ProviderException] or [KeyStoreException] is transient.
 * Cryptographic failures such as a bad tag are not transient: they prove the data cannot be
 * decrypted.
 */
internal fun Throwable.isTransientKeystoreFailure(): Boolean = isTransientKeystoreFailure(::platformKeystoreVerdict)

/**
 * [isTransientKeystoreFailure] with the platform verdict passed in: [platformVerdict] returns
 * whether a Keystore exception it recognises is transient, or null for any other throwable. JVM
 * tests pass a fake, because `android.security.KeyStoreException` cannot be built there.
 */
internal fun Throwable.isTransientKeystoreFailure(platformVerdict: (Throwable) -> Boolean?): Boolean {
    val chain = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    if (chain.any { it is KeyPermanentlyInvalidatedException }) return false
    return chain.firstNotNullOfOrNull(platformVerdict)
        ?: chain.any { it is ProviderException || it is KeyStoreException }
}

/** On API 33+, whether an `android.security.KeyStoreException` is transient; null otherwise. */
private fun platformKeystoreVerdict(failure: Throwable): Boolean? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && failure is android.security.KeyStoreException) {
        failure.isTransientFailure || failure.isSystemError
    } else {
        null
    }

/** Bounds the cause walk, so a cyclic cause chain cannot loop forever. */
private const val MAX_CAUSE_DEPTH = 8
