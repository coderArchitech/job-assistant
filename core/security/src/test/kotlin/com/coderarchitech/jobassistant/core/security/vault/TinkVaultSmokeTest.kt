package com.coderarchitech.jobassistant.core.security.vault

import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Smoke test: round trip and slot binding with an in-memory key-encryption key. */
class TinkVaultSmokeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val dataStore = createVaultDataStore(scope.backgroundScope) { tmp.root.resolve("vault.pb") }
    private val vault = TinkVault(dataStore, FakeKek(), dispatcher)

    @Test
    fun roundTripAndMovedValueIsUnreadable() =
        scope.runTest {
            val from = VaultSlot.portal(1)
            val to = VaultSlot.portal(2)
            vault.put(from, Secret("fake-password-1"))

            assertEquals(VaultRead.Found(Secret("fake-password-1")), vault.get(from))
            assertEquals(VaultRead.Missing, vault.get(to))

            val copied =
                dataStore.data
                    .first()
                    .entriesMap
                    .getValue(from.name)
            dataStore.updateData {
                it
                    .toBuilder()
                    .putEntries(
                        to.name,
                        ByteString.copyFrom(copied.toByteArray()),
                    ).build()
            }
            assertEquals(VaultRead.Unreadable, vault.get(to))
        }
}
