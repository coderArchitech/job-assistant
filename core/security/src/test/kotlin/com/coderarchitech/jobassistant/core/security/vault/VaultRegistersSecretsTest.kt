package com.coderarchitech.jobassistant.core.security.vault

import com.coderarchitech.jobassistant.core.security.log.Redactor
import com.coderarchitech.jobassistant.core.security.log.SecretRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The vault keeps [SecretRegistry] in step with what it holds, so the log redactor knows every
 * stored secret (T1.09). Uses DataStore files, so like the other vault tests it runs on Linux CI.
 */
class VaultRegistersSecretsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val registry = SecretRegistry()
    private val redactor = Redactor(registry)
    private val dataStore = createVaultDataStore(scope.backgroundScope) { tmp.root.resolve("vault.pb") }

    @Test
    fun storedReadDeletedAndWipedValuesFollowTheVault() =
        scope.runTest {
            val vault = TinkVault(dataStore, FakeKek(), dispatcher, registry)
            vault.put(VaultSlot.portal(1), Secret("fake-portal-pass-1"))
            vault.put(VaultSlot.portal(2), Secret("fake-portal-pass-2"))
            assertEquals("[REDACTED] [REDACTED]", redactor.redact("fake-portal-pass-1 fake-portal-pass-2"))

            vault.delete(VaultSlot.portal(1))
            assertEquals("fake-portal-pass-1 [REDACTED]", redactor.redact("fake-portal-pass-1 fake-portal-pass-2"))

            vault.wipe()
            assertEquals("fake-portal-pass-2", redactor.redact("fake-portal-pass-2"))
        }

    @Test
    fun aValueReadAfterARestartIsRegistered() =
        scope.runTest {
            val kek = FakeKek()
            TinkVault(
                dataStore,
                kek,
                dispatcher,
                SecretRegistry(),
            ).put(VaultSlot.portal(1), Secret("fake-portal-pass-1"))

            // A new process: empty registry until the value is read.
            val vault = TinkVault(dataStore, kek, dispatcher, registry)
            assertEquals("fake-portal-pass-1", redactor.redact("fake-portal-pass-1"))
            (vault.get(VaultSlot.portal(1)) as VaultRead.Found).secret.close()
            assertEquals("[REDACTED]", redactor.redact("fake-portal-pass-1"))
        }
}
