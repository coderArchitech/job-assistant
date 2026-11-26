package com.coderarchitech.jobassistant.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStoreFile
import com.coderarchitech.jobassistant.core.data.repository.ApplicationRepository
import com.coderarchitech.jobassistant.core.data.repository.JobRepository
import com.coderarchitech.jobassistant.core.data.repository.RoomApplicationRepository
import com.coderarchitech.jobassistant.core.data.repository.RoomJobRepository
import com.coderarchitech.jobassistant.core.data.repository.RoomTransactionRunner
import com.coderarchitech.jobassistant.core.data.repository.TransactionRunner
import com.coderarchitech.jobassistant.core.data.settings.AUTO_SEND_CONSENT_FILE_NAME
import com.coderarchitech.jobassistant.core.data.settings.AutoSendConsentMarker
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
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Singleton

/** Binds the `:core:data` repository interfaces to their implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun bindAppInfoRepository(impl: DefaultAppInfoRepository): AppInfoRepository

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindJobRepository(impl: RoomJobRepository): JobRepository

    @Binds
    abstract fun bindApplicationRepository(impl: RoomApplicationRepository): ApplicationRepository

    @Binds
    abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner

    companion object {
        /**
         * The clock for "today" in the daily caps. It reads the device's time zone on every call, so days end
         * at local midnight even after the user changes time zone while the app is running.
         */
        @Provides
        @Singleton
        fun provideClock(): Clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneId.systemDefault()

                override fun withZone(zone: ZoneId): Clock = system(zone)

                override fun instant(): Instant = Instant.now()
            }

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

        /**
         * The auto-send consent lives in `noBackupFilesDir`, which is never backed up or transferred
         * (decision D03). The directory is resolved on first use, off the main thread.
         */
        @Provides
        @Singleton
        fun provideAutoSendConsentMarker(
            @ApplicationContext context: Context,
        ): AutoSendConsentMarker = AutoSendConsentMarker { File(context.noBackupFilesDir, AUTO_SEND_CONSENT_FILE_NAME) }
    }
}
