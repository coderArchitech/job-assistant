package com.coderarchitech.jobassistant.core.data.settings

import androidx.datastore.core.DataStore
import com.coderarchitech.jobassistant.core.data.settings.proto.SettingsProto
import com.coderarchitech.jobassistant.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Reads and changes the app-wide [AppSettings]. Values survive app restarts. */
interface SettingsRepository {
    /** The current settings, then every change. Emits the defaults when nothing was saved yet. */
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
                .distinctUntilChanged()

        override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings =
            dataStore
                .updateData { current -> transform(current.toDomain()).toProto() }
                .toDomain()

        override suspend fun reset() {
            dataStore.updateData { SettingsSerializer.defaultValue }
        }
    }
