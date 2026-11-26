package com.coderarchitech.jobassistant.core.config

import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.ListedPrice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * The signed price table (task T3.09): the release in use, kept in memory. The first read loads it from disk or
 * the APK; [refresh] fetches a newer one. Models it does not list are priced by the AI layer's fallback,
 * which over-counts.
 *
 * The body is `{"models": [{"provider": "anthropic", "model": "...", "input": 3000000, "output": ...,
 * "cacheRead": ..., "cacheWrite": ...}]}`, prices in micro-US-dollars per million tokens. Input and output
 * must be above zero, so no listed model is free to the budget guard. An entry for a provider this app does
 * not know is skipped; any other bad entry makes the whole release malformed.
 */
class ConfigPrices(
    private val client: ConfigClient,
) {
    private class Loaded(
        val version: Int,
        val prices: List<ListedPrice>,
    )

    private val loaded = AtomicReference<Loaded?>(null)

    /** Every listed price. The first call reads a small file (call [load] early, off the main thread). */
    fun listed(): List<ListedPrice> = (loaded.get() ?: keep(client.currentNow(ConfigItem.Prices, ::isValid))).prices

    /** Loads the table in use into memory. */
    suspend fun load() {
        keep(client.current(ConfigItem.Prices, ::isValid))
    }

    /** Fetches a newer price table and uses it if it is good. */
    suspend fun refresh(): RefreshResult = client.refresh(ConfigItem.Prices, ::isValid).also { load() }

    /** Keeps [active] unless a newer table is already in memory, so concurrent loads never go backwards. */
    private fun keep(active: ActiveConfig?): Loaded {
        val candidate = Loaded(active?.document?.version ?: 0, active?.document?.let(::parse).orEmpty())
        return loaded.updateAndGet { held -> if (held == null || candidate.version > held.version) candidate else held }
            ?: candidate
    }

    private fun isValid(document: ConfigDocument) = parse(document) != null

    /** Reading price tables. */
    companion object {
        /** The prices in [document], or null when one entry is invalid. */
        fun parse(document: ConfigDocument): List<ListedPrice>? {
            val models = document.body["models"] as? JsonArray ?: return null
            val entries = models.map { (it as? JsonObject)?.let(::entry) ?: Entry.Invalid }
            return if (entries.any { it == Entry.Invalid }) {
                null
            } else {
                entries.filterIsInstance<Entry.Price>().map { it.price }
            }
        }

        private sealed interface Entry {
            data object Invalid : Entry

            data object UnknownProvider : Entry

            class Price(
                val price: ListedPrice,
            ) : Entry
        }

        private fun entry(json: JsonObject): Entry {
            val provider = json.text("provider")?.uppercase(Locale.ROOT)
            val model = json.text("model")
            val numbers = listOf("input", "output", "cacheRead", "cacheWrite").map { json.micros(it) }
            return when {
                provider == null || model == null || numbers.any { it == null || it < 0 } -> Entry.Invalid
                numbers[0] == 0L || numbers[1] == 0L -> Entry.Invalid
                AiProvider.entries.none { it.name == provider } -> Entry.UnknownProvider
                else ->
                    Entry.Price(
                        ListedPrice(
                            provider = AiProvider.valueOf(provider),
                            model = model,
                            inputMicrosPerMillion = numbers[0] ?: 0,
                            outputMicrosPerMillion = numbers[1] ?: 0,
                            cacheReadMicrosPerMillion = numbers[2] ?: 0,
                            cacheWriteMicrosPerMillion = numbers[3] ?: 0,
                        ),
                    )
            }
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

        private fun JsonObject.micros(key: String): Long? =
            (this[key] as? JsonPrimitive)
                ?.takeUnless {
                    it.isString
                }?.longOrNull
    }
}
