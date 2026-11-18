package com.coderarchitech.jobassistant.core.data.settings

import androidx.datastore.core.DataStore
import com.coderarchitech.jobassistant.core.data.settings.proto.SettingsProto
import com.coderarchitech.jobassistant.core.model.AppSettings
import com.coderarchitech.jobassistant.core.model.FeatureToggles
import com.coderarchitech.jobassistant.core.model.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Reads and changes the app-wide [AppSettings]. Values survive app restarts. */
interface SettingsRepository {
    /**
     * The current settings, then every change. Emits the defaults when nothing was saved yet. A
     * read that fails with an I/O error is retried a few times with backoff; if it keeps failing
     * the flow fails with that error rather than inventing values, so code that enforces caps or
     * the budget can fail closed (pause) instead of running on defaults the user may have lowered.
     * [FeatureToggles.autoSendEmails] is kept on this device only and never comes back from a backup.
     */
    val settings: Flow<AppSettings>

    /**
     * Atomically replaces the settings with [transform] applied to the current value and returns
     * the saved result. Nothing is written if [transform] throws.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings

    /** Restores every setting to its default. */
    suspend fun reset()
}

/**
 * [SettingsRepository] backed by the Proto DataStore file `settings.pb`, except for
 * [FeatureToggles.autoSendEmails], which lives only in [consentMarker] on this device (decision D03):
 * `settings.pb` is in backups, so it always stores auto-send as off and its stored value is ignored
 * on read. A restored, transferred or older `settings.pb` therefore never turns auto-send on.
 *
 * One instance per process: it holds the marker state, and changes go through it alone.
 */
@Singleton
internal class DataStoreSettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<SettingsProto>,
        private val consentMarker: AutoSendConsentMarker,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : SettingsRepository {
        /** Serializes [update] and [reset], so `settings.pb` and the marker change together. */
        private val writeMutex = Mutex()

        /** Whether the consent marker exists; null until first read from disk. */
        private val consent = MutableStateFlow<Boolean?>(null)

        override val settings: Flow<AppSettings> =
            combine(
                dataStore.data
                    .map { it.toDomain() }
                    .retryWhen { cause, attempt ->
                        val retry = cause is IOException && attempt < READ_RETRIES
                        if (retry) delay(READ_RETRY_BASE_DELAY_MS shl attempt.toInt())
                        retry
                    },
                flow {
                    loadConsent()
                    emitAll(consent.filterNotNull())
                },
            ) { stored, autoSend -> stored.withAutoSend(autoSend) }
                .distinctUntilChanged()

        /**
         * On enabling auto-send, `settings.pb` is written first and the marker created last; on
         * disabling, the marker is deleted first. A failure part way therefore leaves auto-send off.
         */
        override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
            writeMutex.withLock {
                val current =
                    dataStore.data
                        .first()
                        .toDomain()
                        .withAutoSend(loadConsent())
                val next = transform(current)
                val autoSend = next.toggles.autoSendEmails
                if (!autoSend) setConsent(false)
                val saved = dataStore.updateData { next.withAutoSend(false).toProto() }
                if (autoSend) setConsent(true)
                saved.toDomain().withAutoSend(autoSend)
            }

        override suspend fun reset() {
            writeMutex.withLock {
                setConsent(false)
                dataStore.updateData { SettingsSerializer.defaultValue }
            }
        }

        /** The marker state, read from disk once per process. */
        private suspend fun loadConsent(): Boolean =
            consent.value ?: withContext(ioDispatcher) { consentMarker.exists() }.also { exists ->
                consent.compareAndSet(null, exists)
            }

        private suspend fun setConsent(granted: Boolean) {
            withContext(ioDispatcher) {
                if (granted) consentMarker.create() else consentMarker.delete()
            }
            consent.value = granted
        }
    }

private fun AppSettings.withAutoSend(autoSend: Boolean): AppSettings =
    if (toggles.autoSendEmails == autoSend) this else copy(toggles = toggles.copy(autoSendEmails = autoSend))

/** Read attempts after the first, for transient I/O errors on `settings.pb`. */
private const val READ_RETRIES = 3L

/** First retry delay; it doubles on each attempt (100, 200, 400 ms). */
private const val READ_RETRY_BASE_DELAY_MS = 100L
