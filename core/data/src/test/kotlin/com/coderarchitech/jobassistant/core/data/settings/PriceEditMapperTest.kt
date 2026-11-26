package com.coderarchitech.jobassistant.core.data.settings

import com.coderarchitech.jobassistant.core.data.settings.proto.PriceEditProto
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AppSettings
import com.coderarchitech.jobassistant.core.model.ListedPrice
import org.junit.Assert.assertEquals
import org.junit.Test

class PriceEditMapperTest {
    private val edit = ListedPrice(AiProvider.ANTHROPIC, "fake-model-large", 3_000_000, 15_000_000, 300_000, 3_750_000)

    @Test
    fun editsSurviveARoundTrip() {
        val settings = AppSettings(priceEdits = listOf(edit, edit.copy(provider = AiProvider.OPENAI)))

        assertEquals(settings, settings.toProto().toDomain())
    }

    @Test
    fun invalidStoredEditsAreDropped() {
        val good = edit.toPriceEditProto()
        val stored =
            listOf(
                good,
                good.toBuilder().setProvider("FUTURE_AI").build(),
                good.toBuilder().setModel("sk-fake-not-a-model").build(),
                good.toBuilder().setModel("two words").build(),
                good.toBuilder().setInputMicrosPerMillion(0).build(),
                good.toBuilder().setOutputMicrosPerMillion(-1).build(),
                good.toBuilder().setCacheReadMicrosPerMillion(-1).build(),
                good.toBuilder().clearCacheWriteMicrosPerMillion().build(),
                // A second edit for the same model: the first one wins.
                good.toBuilder().setInputMicrosPerMillion(1).build(),
            )

        assertEquals(listOf(edit), stored.toPriceEdits())
    }

    @Test
    fun noMoreThanTheLimitIsKept() {
        val stored =
            (1..AppSettings.MAX_PRICE_EDITS + 5).map {
                edit.copy(model = "fake-model-$it").toPriceEditProto()
            }

        assertEquals(AppSettings.MAX_PRICE_EDITS, stored.toPriceEdits().size)
    }

    @Test
    fun aSettingsFileWithoutEditsHasNone() {
        assertEquals(emptyList<PriceEditProto>(), AppSettings().toProto().priceEditsList)
        assertEquals(emptyList<ListedPrice>(), AppSettings().toProto().toDomain().priceEdits)
    }
}
