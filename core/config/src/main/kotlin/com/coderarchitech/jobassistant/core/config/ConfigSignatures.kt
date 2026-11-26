package com.coderarchitech.jobassistant.core.config

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Checks Ed25519 signatures against the public keys compiled into the app, current and next (D07). A
 * signature from either key is accepted, so the owner can rotate keys without stranding older apps. With no
 * usable key, nothing verifies and only bundled config is used.
 *
 * @param publicKeys base64 of each raw 32-byte public key; a malformed one is ignored.
 */
class ConfigSignatures(
    publicKeys: List<String>,
) {
    private val verifiers: List<Ed25519Verify> = publicKeys.mapNotNull(::verifierOf)

    /** True when at least one key is usable. */
    val hasKeys: Boolean get() = verifiers.isNotEmpty()

    /** True when [signature] over [data] was made by one of the app's keys. */
    fun verify(
        data: ByteArray,
        signature: ByteArray,
    ): Boolean =
        signature.size == SIGNATURE_BYTES &&
            verifiers.any { verifier ->
                try {
                    verifier.verify(signature, data)
                    true
                } catch (_: GeneralSecurityException) {
                    false
                }
            }

    /** Sizes of Ed25519 keys and signatures. */
    companion object {
        const val SIGNATURE_BYTES = 64
        private const val KEY_BYTES = 32

        private fun verifierOf(key: String): Ed25519Verify? =
            try {
                Base64
                    .getDecoder()
                    .decode(key.trim())
                    .takeIf { it.size == KEY_BYTES }
                    ?.let(::Ed25519Verify)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
