package com.coderarchitech.jobassistant.core.config

import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.ListedPrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ConfigPricesTest {
    @Test
    fun aPriceTableIsRead() {
        val prices =
            ConfigPrices.parse(
                doc(
                    """[
                    {"provider":"anthropic","model":"claude-a","input":3000000,"output":15000000,
                     "cacheRead":300000,"cacheWrite":3750000},
                    {"provider":"openai","model":"gpt-b","input":1,"output":2,"cacheRead":0,"cacheWrite":0}
                    ]""",
                ),
            )

        assertEquals(
            listOf(
                ListedPrice(AiProvider.ANTHROPIC, "claude-a", 3_000_000, 15_000_000, 300_000, 3_750_000),
                ListedPrice(AiProvider.OPENAI, "gpt-b", 1, 2, 0, 0),
            ),
            prices,
        )
    }

    @Test
    fun aProviderThisAppDoesNotKnowIsSkipped() {
        val prices =
            ConfigPrices.parse(
                doc("""[{"provider":"future-ai","model":"m","input":1,"output":1,"cacheRead":1,"cacheWrite":1}]"""),
            )

        assertEquals(emptyList<ListedPrice>(), prices)
    }

    @Test
    fun oneBadEntrySpoilsTheTable() {
        val bad =
            listOf(
                """[{"provider":"anthropic","model":"m","input":-1,"output":1,"cacheRead":1,"cacheWrite":1}]""",
                """[{"provider":"anthropic","model":"m","input":"1","output":1,"cacheRead":1,"cacheWrite":1}]""",
                """[{"provider":"anthropic","input":1,"output":1,"cacheRead":1,"cacheWrite":1}]""",
                """[{"provider":"anthropic","model":"m","input":1,"output":1,"cacheRead":1}]""",
                """[{"provider":"anthropic","model":"m","input":0,"output":1,"cacheRead":1,"cacheWrite":1}]""",
                """[{"provider":"anthropic","model":"m","input":1,"output":0,"cacheRead":0,"cacheWrite":0}]""",
                """[42]""",
                """{}""",
            )
        bad.forEach { assertNull(it, ConfigPrices.parse(doc(it))) }
    }

    @Test
    fun theBundledTableIsAValidRelease() {
        val bundled = File("src/main/assets/config/v1/prices.json").readBytes()
        val document = checkNotNull(ConfigDocument.parse(bundled))

        assertEquals(true, document.isFor(ConfigItem.Prices))
        assertNotNull(ConfigPrices.parse(document))
    }

    private fun doc(models: String) =
        ConfigDocument.parse(
            """{"kind":"prices","name":"prices","version":1,"minAppVersion":1,"models":$models}""".toByteArray(),
        )!!
}
