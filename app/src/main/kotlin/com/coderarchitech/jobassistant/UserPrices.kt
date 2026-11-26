package com.coderarchitech.jobassistant

import com.coderarchitech.jobassistant.core.model.ListedPrice
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own model prices (task T3.09), kept in memory so pricing a call never reads storage. [ConfigStartup]
 * keeps it in step with the settings. Until the first read it is null, and pricing falls back to over-counting
 * every model, since a user's price may be above the signed one.
 */
@Singleton
class UserPrices
    @Inject
    constructor() {
        private val edits = AtomicReference<List<ListedPrice>?>(null)

        /** The edits now in effect, or null before the settings have been read. */
        fun current(): List<ListedPrice>? = edits.get()

        /** Replaces the edits in effect. */
        fun set(prices: List<ListedPrice>) = edits.set(prices)
    }
