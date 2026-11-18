package com.coderarchitech.jobassistant.core.security.vault

import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.LoginMethod
import com.coderarchitech.jobassistant.core.model.Portal
import com.coderarchitech.jobassistant.core.model.PortalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSlotTest {
    private val validNames =
        listOf(
            "a",
            "0",
            "portal/12",
            "ai/anthropic",
            "snake_case/with-dash/0",
            "/",
            "a".repeat(128),
        )

    private val invalidNames =
        listOf(
            "" to "empty",
            "a".repeat(129) to "longer than 128",
            "Portal/1" to "upper case",
            "portal 1" to "space",
            "portal.1" to "dot",
            "portal:1" to "colon",
            "portal|1" to "associated-data separator",
            "portal/1\n" to "trailing newline",
            "portäl/1" to "non-ASCII letter",
            "ai\\openai" to "backslash",
        )

    @Test
    fun validNamesAreAccepted() {
        for (name in validNames) {
            assertEquals(name, VaultSlot(name).name)
            assertEquals(name, VaultSlot.fromNameOrNull(name)?.name)
        }
    }

    @Test
    fun invalidNamesAreRejected() {
        for ((name, why) in invalidNames) {
            var thrown: IllegalArgumentException? = null
            try {
                VaultSlot(name)
            } catch (e: IllegalArgumentException) {
                thrown = e
            }
            assertTrue("$why throws", thrown != null)
            assertEquals("$why message does not echo the name", "Invalid vault slot name", thrown?.message)
            assertNull("$why fromNameOrNull", VaultSlot.fromNameOrNull(name))
        }
    }

    @Test
    fun portalSlotMatchesThePortalVaultKey() {
        for (id in listOf(0L, 1L, 12L, 9_876_543_210L, Long.MAX_VALUE)) {
            val portal =
                Portal(
                    id = id,
                    name = "Fake Portal",
                    type = PortalType.CUSTOM,
                    adapterId = null,
                    loginUrl = "https://portal.example.invalid/login",
                    searchUrl = null,
                    loginMethod = LoginMethod.PASSWORD,
                )
            assertEquals("id $id", portal.vaultKey, VaultSlot.portal(id).name)
        }
    }

    @Test
    fun aiKeySlotPerProvider() {
        val expected =
            mapOf(
                AiProvider.ANTHROPIC to "ai/anthropic",
                AiProvider.OPENAI to "ai/openai",
            )
        assertEquals("every provider has a case", AiProvider.entries.toSet(), expected.keys)
        for ((provider, name) in expected) {
            assertEquals(provider.name, name, VaultSlot.aiKey(provider).name)
        }
    }

    @Test
    fun portalAndAiSlotsNeverCollide() {
        val slots = AiProvider.entries.map { VaultSlot.aiKey(it) } + (0L..20L).map { VaultSlot.portal(it) }
        assertEquals(slots.size, slots.toSet().size)
    }

    @Test
    fun equalityIsByName() {
        assertEquals(VaultSlot("portal/1"), VaultSlot.portal(1))
        assertFalse(VaultSlot.portal(1) == VaultSlot.portal(2))
        assertEquals("VaultSlot(portal/1)", VaultSlot.portal(1).toString())
    }
}
