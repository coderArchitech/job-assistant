package com.coderarchitech.jobassistant.core.security.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Smoke test against the real Android Keystore key, under a test-only alias. */
@RunWith(AndroidJUnit4::class)
class TinkVaultDeviceSmokeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val kek = AndroidKeystoreKeyEncryptionKey(alias = "jobassistant_vault_kek_test")

    @After
    fun deleteKey() {
        kek.delete()
    }

    @Test
    fun roundTripWithKeystoreKey() =
        runTest {
            val dataStore = createVaultDataStore(backgroundScope) { tmp.root.resolve("vault.pb") }
            val vault = TinkVault(dataStore, kek, Dispatchers.IO)

            assertEquals(VaultPut.Stored, vault.put(VaultSlot.portal(1), Secret("fake-password-1")))

            assertEquals(VaultRead.Found(Secret("fake-password-1")), vault.get(VaultSlot.portal(1)))
        }
}
