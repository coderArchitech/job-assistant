package com.coderarchitech.jobassistant.core.data.settings

import androidx.datastore.core.DataStore
import com.coderarchitech.jobassistant.core.data.settings.proto.SettingsProto
import com.coderarchitech.jobassistant.core.model.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException
import javax.inject.Inject

/** Reads and changes the app-wide [AppSettings]. Values survive app restarts. */
interface SettingsRepository {
    /**
     * The current settings, then every change. Emits the defaults when nothing was saved yet. A
     * read that fails with an I/O error is retried a few times with backoff; if it keeps failing
     * the flow fails with that error rather than inventing values, so code that enforces caps or
     * the budget can fail closed (pause) instead of running on defaults the user may have lowered.
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

/** [SettingsRepository] backed by the Proto DataStore file `settings.pb`. */
internal class DataStoreSettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<SettingsProto>,
    ) : SettingsRepository {
        override val settings: Flow<AppSettings> =
            dataStore.data
                .map { it.toDomain() }
                .retryWhen { cause, attempt ->
                    val retry = cause is IOException && attempt < READ_RETRIES
                    if (retry) delay(READ_RETRY_BASE_DELAY_MS shl attempt.toInt())
                    retry
                }.distinctUntilChanged()

        override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
            dataStore
                .updateData { current -> transform(current.toDomain()).toProto() }
                .toDomain()

        override suspend fun reset() {
            dataStore.updateData { SettingsSerializer.defaultValue }
        }
    }

/** Read attempts after the first, for transient I/O errors on `settings.pb`. */
private const val READ_RETRIES = 3L

/** First retry delay; it doubles on each attempt (100, 200, 400 ms). */
private const val READ_RETRY_BASE_DELAY_MS = 100L
