package com.coderarchitech.jobassistant

import com.coderarchitech.jobassistant.core.config.ConfigPrices
import com.coderarchitech.jobassistant.core.data.settings.SettingsRepository
import com.coderarchitech.jobassistant.core.model.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/**
 * Loads the price table into memory off the main thread at startup, then asks the config host for a newer one
 * (task T3.09). Without a config host the refresh does nothing and the bundled table stays in use. It also keeps
 * [UserPrices] in step with the user's edited prices for as long as the process lives.
 */
class ConfigStartup
    @Inject
    constructor(
        private val prices: ConfigPrices,
        private val settings: SettingsRepository,
        private val userPrices: UserPrices,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) {
        /** Starts the work in [scope]; it never blocks the caller. */
        fun start(scope: CoroutineScope) {
            scope.launch(io) {
                prices.load()
                prices.refresh()
            }
            scope.launch(io) {
                settings.settings
                    .map { it.priceEdits }
                    .distinctUntilChanged()
                    // Settings that cannot be read are tried again later rather than ending the process; until
                    // then the last edits read (or none, which over-counts) stay in effect.
                    .retryWhen { cause, _ ->
                        (cause is IOException).also { retry ->
                            if (retry) {
                                Timber.w(cause, "Edited prices unreadable; retrying")
                                delay(SETTINGS_RETRY_MS)
                            }
                        }
                    }.collect(userPrices::set)
            }
        }
    }

private const val SETTINGS_RETRY_MS = 30_000L
