package com.coderarchitech.jobassistant.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListedPriceTest {
    private val signed = ListedPrice(AiProvider.ANTHROPIC, "fake-a", 3_000_000, 15_000_000, 300_000, 3_750_000)
    private val other = ListedPrice(AiProvider.OPENAI, "fake-b", 1_000_000, 4_000_000, 0, 0)

    @Test
    fun anEditReplacesTheSignedPriceForItsModelOnly() {
        val edit = signed.copy(inputMicrosPerMillion = 2_000_000)

        assertEquals(listOf(edit, other), listOf(signed, other).withEdits(listOf(edit)))
        assertEquals(listOf(signed, other), listOf(signed, other).withEdits(emptyList()))
    }

    @Test
    fun theSameModelIdUnderAnotherProviderIsAnotherModel() {
        assertFalse(signed.isSameModel(signed.copy(provider = AiProvider.OPENAI)))
        assertTrue(signed.isSameModel(signed.copy(outputMicrosPerMillion = 1)))
    }

    @Test
    fun settingsAcceptOnlyEditsThatCostSomething() {
        assertTrue(AppSettings.isValidPriceEdit(signed))
        assertFalse(AppSettings.isValidPriceEdit(signed.copy(inputMicrosPerMillion = 0)))
        assertFalse(AppSettings.isValidPriceEdit(signed.copy(outputMicrosPerMillion = 0)))
        assertFalse(AppSettings.isValidPriceEdit(signed.copy(model = "sk-fake")))
    }
}
