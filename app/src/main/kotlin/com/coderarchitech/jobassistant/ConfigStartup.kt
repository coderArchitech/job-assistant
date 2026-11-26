package com.coderarchitech.jobassistant

import com.coderarchitech.jobassistant.core.config.ConfigPrices
import com.coderarchitech.jobassistant.core.model.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Loads the price table into memory off the main thread at startup, then asks the config host for a newer one
 * (task T3.09). Without a config host the refresh does nothing and the bundled table stays in use.
 */
class ConfigStartup
    @Inject
    constructor(
        private val prices: ConfigPrices,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /** Starts the work in [scope]; it never blocks the caller. */
        fun start(scope: CoroutineScope) {
            scope.launch(io) {
                prices.load()
                prices.refresh()
            }
        }
    }
