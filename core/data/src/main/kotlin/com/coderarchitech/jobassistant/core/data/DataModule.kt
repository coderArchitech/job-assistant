package com.coderarchitech.jobassistant.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStoreFile
import com.coderarchitech.jobassistant.core.data.repository.ApplicationRepository
import com.coderarchitech.jobassistant.core.data.repository.JobRepository
import com.coderarchitech.jobassistant.core.data.repository.RoomApplicationRepository
import com.coderarchitech.jobassistant.core.data.repository.RoomJobRepository
import com.coderarchitech.jobassistant.core.data.repository.RoomKnownPostings
import com.coderarchitech.jobassistant.core.data.repository.RoomSourceCursorStore
import com.coderarchitech.jobassistant.core.data.repository.RoomTransactionRunner
import com.coderarchitech.jobassistant.core.data.repository.RoomUsageLedger
import com.coderarchitech.jobassistant.core.data.repository.SettingsBudgetSource
import com.coderarchitech.jobassistant.core.data.repository.TransactionRunner
import com.coderarchitech.jobassistant.core.data.settings.AUTO_SEND_CONSENT_FILE_NAME
import com.coderarchitech.jobassistant.core.data.settings.AutoSendConsentMarker
import com.coderarchitech.jobassistant.core.data.settings.DataStoreSettingsRepository
import com.coderarchitech.jobassistant.core.data.settings.SETTINGS_FILE_NAME
import com.coderarchitech.jobassistant.core.data.settings.SettingsRepository
import com.coderarchitech.jobassistant.core.data.settings.createSettingsDataStore
import com.coderarchitech.jobassistant.core.data.settings.proto.SettingsProto
import com.coderarchitech.jobassistant.core.model.BudgetSource
import com.coderarchitech.jobassistant.core.model.IoDispatcher
import com.coderarchitech.jobassistant.core.model.KnownPostings
import com.coderarchitech.jobassistant.core.model.SourceCursorStore
import com.coderarchitech.jobassistant.core.model.UsageLedger
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

    @Binds
    abstract fun bindUsageLedger(impl: RoomUsageLedger): UsageLedger

    @Binds
    abstract fun bindSourceCursorStore(impl: RoomSourceCursorStore): SourceCursorStore

    @Binds
    abstract fun bindKnownPostings(impl: RoomKnownPostings): KnownPostings

    @Binds
    abstract fun bindBudgetSource(impl: SettingsBudgetSource): BudgetSource

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
