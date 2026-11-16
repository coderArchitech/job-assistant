package com.coderarchitech.jobassistant.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStoreFile
import com.coderarchitech.jobassistant.core.data.settings.DataStoreSettingsRepository
import com.coderarchitech.jobassistant.core.data.settings.SETTINGS_FILE_NAME
import com.coderarchitech.jobassistant.core.data.settings.SettingsRepository
import com.coderarchitech.jobassistant.core.data.settings.createSettingsDataStore
import com.coderarchitech.jobassistant.core.data.settings.proto.SettingsProto
import com.coderarchitech.jobassistant.core.model.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Binds the `:core:data` repository interfaces to their implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun bindAppInfoRepository(impl: DefaultAppInfoRepository): AppInfoRepository

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    companion object {
        /** One DataStore per file per process, so it is a singleton. */
        @Provides
        @Singleton
        fun provideSettingsDataStore(
            @ApplicationContext context: Context,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<SettingsProto> =
            createSettingsDataStore(
                scope = CoroutineScope(ioDispatcher + SupervisorJob()),
                produceFile = { context.dataStoreFile(SETTINGS_FILE_NAME) },
            )
    }
}
