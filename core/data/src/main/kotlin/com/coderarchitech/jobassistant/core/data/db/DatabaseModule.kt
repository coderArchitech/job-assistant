package com.coderarchitech.jobassistant.core.data.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the Room database and its DAOs. */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // Hilt needs one @Provides per DAO.
internal object DatabaseModule {
    /** One database instance per process. No destructive-migration fallback: a missing migration must fail loudly. */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AgentDatabase =
        Room
            .databaseBuilder(context, AgentDatabase::class.java, AgentDatabase.NAME)
            .also { builder -> AgentMigrations.ALL.forEach { builder.addMigrations(it) } }
            .build()

    @Provides
    fun provideProfileDao(db: AgentDatabase) = db.profileDao()

    @Provides
    fun provideSearchPresetDao(db: AgentDatabase) = db.searchPresetDao()

    @Provides
    fun provideCompanyWatchlistDao(db: AgentDatabase) = db.companyWatchlistDao()

    @Provides
    fun provideSourceCursorDao(db: AgentDatabase) = db.sourceCursorDao()

    @Provides
    fun providePortalDao(db: AgentDatabase) = db.portalDao()

    @Provides
    fun provideJobDao(db: AgentDatabase) = db.jobDao()

    @Provides
    fun provideMatchResultDao(db: AgentDatabase) = db.matchResultDao()

    @Provides
    fun provideDraftDao(db: AgentDatabase) = db.draftDao()

    @Provides
    fun provideDecisionDao(db: AgentDatabase) = db.decisionDao()

    @Provides
    fun provideApplicationDao(db: AgentDatabase) = db.applicationDao()

    @Provides
    fun provideOutreachMessageDao(db: AgentDatabase) = db.outreachMessageDao()

    @Provides
    fun provideRunDao(db: AgentDatabase) = db.runDao()

    @Provides
    fun provideUsageRecordDao(db: AgentDatabase) = db.usageRecordDao()

    @Provides
    fun provideDailyReportDao(db: AgentDatabase) = db.dailyReportDao()

    @Provides
    fun provideCapUsageDao(db: AgentDatabase) = db.capUsageDao()
}
