package com.coderarchitech.jobassistant.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.coderarchitech.jobassistant.core.data.db.dao.ApplicationDao
import com.coderarchitech.jobassistant.core.data.db.dao.CompanyWatchlistDao
import com.coderarchitech.jobassistant.core.data.db.dao.DailyReportDao
import com.coderarchitech.jobassistant.core.data.db.dao.DecisionDao
import com.coderarchitech.jobassistant.core.data.db.dao.DraftDao
import com.coderarchitech.jobassistant.core.data.db.dao.JobDao
import com.coderarchitech.jobassistant.core.data.db.dao.MatchResultDao
import com.coderarchitech.jobassistant.core.data.db.dao.OutreachMessageDao
import com.coderarchitech.jobassistant.core.data.db.dao.PortalDao
import com.coderarchitech.jobassistant.core.data.db.dao.ProfileDao
import com.coderarchitech.jobassistant.core.data.db.dao.RunDao
import com.coderarchitech.jobassistant.core.data.db.dao.SearchPresetDao
import com.coderarchitech.jobassistant.core.data.db.dao.SourceCursorDao
import com.coderarchitech.jobassistant.core.data.db.dao.UsageRecordDao
import com.coderarchitech.jobassistant.core.data.db.entity.ApplicationEntity
import com.coderarchitech.jobassistant.core.data.db.entity.CompanyWatchlistEntity
import com.coderarchitech.jobassistant.core.data.db.entity.DailyReportEntity
import com.coderarchitech.jobassistant.core.data.db.entity.DecisionEntity
import com.coderarchitech.jobassistant.core.data.db.entity.DraftEntity
import com.coderarchitech.jobassistant.core.data.db.entity.JobEntity
import com.coderarchitech.jobassistant.core.data.db.entity.MatchResultEntity
import com.coderarchitech.jobassistant.core.data.db.entity.OutreachMessageEntity
import com.coderarchitech.jobassistant.core.data.db.entity.PortalEntity
import com.coderarchitech.jobassistant.core.data.db.entity.ProfileEntity
import com.coderarchitech.jobassistant.core.data.db.entity.RunEntity
import com.coderarchitech.jobassistant.core.data.db.entity.SearchPresetEntity
import com.coderarchitech.jobassistant.core.data.db.entity.SourceCursorEntity
import com.coderarchitech.jobassistant.core.data.db.entity.UsageRecordEntity

/**
 * The app's one Room database (`agent.db`). It holds every non-secret record; secrets live only in
 * the vault. The schema is exported to `core/data/schemas` for every version, and a new version
 * needs a migration and a migration test (task T1.04). Destructive migration is never allowed.
 */
@Database(
    entities = [
        ProfileEntity::class,
        SearchPresetEntity::class,
        CompanyWatchlistEntity::class,
        SourceCursorEntity::class,
        PortalEntity::class,
        JobEntity::class,
        MatchResultEntity::class,
        DraftEntity::class,
        DecisionEntity::class,
        ApplicationEntity::class,
        OutreachMessageEntity::class,
        RunEntity::class,
        UsageRecordEntity::class,
        DailyReportEntity::class,
    ],
    version = AgentDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
@Suppress("TooManyFunctions") // Room needs one accessor per DAO.
abstract class AgentDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    abstract fun searchPresetDao(): SearchPresetDao

    abstract fun companyWatchlistDao(): CompanyWatchlistDao

    abstract fun sourceCursorDao(): SourceCursorDao

    abstract fun portalDao(): PortalDao

    abstract fun jobDao(): JobDao

    abstract fun matchResultDao(): MatchResultDao

    abstract fun draftDao(): DraftDao

    abstract fun decisionDao(): DecisionDao

    abstract fun applicationDao(): ApplicationDao

    abstract fun outreachMessageDao(): OutreachMessageDao

    abstract fun runDao(): RunDao

    abstract fun usageRecordDao(): UsageRecordDao

    abstract fun dailyReportDao(): DailyReportDao

    companion object {
        /** The database file name under `databases/`. */
        const val NAME = "agent.db"

        /** The current schema version. */
        const val VERSION = 2
    }
}
