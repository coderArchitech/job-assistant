package com.coderarchitech.jobassistant.core.security.scan

import com.coderarchitech.jobassistant.core.security.log.SecretRegistry
import com.coderarchitech.jobassistant.core.security.vault.Vault
import com.coderarchitech.jobassistant.core.security.vault.VaultRead
import com.coderarchitech.jobassistant.core.security.vault.VaultSlot
import com.coderarchitech.jobassistant.core.security.vault.registryKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** What [SecretScanner.scan] found. */
sealed interface ScanResult {
    /** None of the secrets the app holds appears in the text. */
    data object Clean : ScanResult

    /** A secret appears in the text. [source] names whose it is (for example `vault:portal/12`), never the value. */
    data class SecretFound(
        val source: String,
    ) : ScanResult

    /** The vault could not be read, so the text could not be checked. Treat it like a match. */
    data object Unverifiable : ScanResult
}

/** Checks text, such as an outgoing AI request, for secrets the app holds. */
interface SecretScanner {
    /** Scans every text in [texts]. */
    suspend fun scan(texts: List<String>): ScanResult
}

/**
 * Scans for the vault's values and for the last OTP.
 *
 * Both live in [SecretRegistry]: the vault registers each value it stores, returns or deletes, and the
 * OTP flow registers the last code. After a restart the registry is empty until each vault slot has
 * been read, so the first scan reads every slot once. Later changes arrive through the vault, so no
 * further reads are needed. If the vault cannot be read the scan is [ScanResult.Unverifiable] and the
 * next scan tries again, so the guard fails closed.
 */
@Singleton
class RegistrySecretScanner
    @Inject
    constructor(
        private val vault: Vault,
        private val registry: SecretRegistry,
    ) : SecretScanner {
        private val seedLock = Mutex()

        @Volatile private var seeded = false

        override suspend fun scan(texts: List<String>): ScanResult {
            if (!seedFromVault()) return ScanResult.Unverifiable
            val source = texts.firstNotNullOfOrNull { registry.findKeyIn(it) }
            return if (source == null) ScanResult.Clean else ScanResult.SecretFound(source)
        }

        /** True once every vault value is in the registry. */
        private suspend fun seedFromVault(): Boolean {
            if (seeded) return true
            return seedLock.withLock {
                seeded || readAllSlots().also { seeded = it }
            }
        }

        @Suppress("TooGenericExceptionCaught") // Any failure to read the vault means the scan cannot be trusted.
        private suspend fun readAllSlots(): Boolean =
            try {
                vault.slots().forEach { registerSlot(it) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The exception names the failure, never a secret.
                Timber.w(e, "Could not read the vault to check for secrets")
                false
            }

        private suspend fun registerSlot(slot: VaultSlot) {
            // Unreadable: the value can never be decrypted, so it cannot be in a prompt either.
            val found = vault.get(slot) as? VaultRead.Found ?: return
            found.secret.use { registry.register(slot.registryKey(), it) }
        }
    }
