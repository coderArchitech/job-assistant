package com.coderarchitech.jobassistant.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.CompanyWatchlistEntry
import com.coderarchitech.jobassistant.core.model.DealBreakers
import com.coderarchitech.jobassistant.core.model.Education
import com.coderarchitech.jobassistant.core.model.JobFilters
import com.coderarchitech.jobassistant.core.model.Preferences
import com.coderarchitech.jobassistant.core.model.Profile
import com.coderarchitech.jobassistant.core.model.Role
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.Skill
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.model.StandardAnswer
import java.time.Instant

/** The single profile row; [id] is always [SINGLE_ID], which keeps the table to one row. */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = SINGLE_ID,
    val resumeText: String,
    val skills: List<Skill>,
    val experience: List<Role>,
    val education: List<Education>,
    val preferences: Preferences,
    val dealBreakers: DealBreakers,
    val answersBank: List<StandardAnswer>,
    val writingSamples: List<String>,
    val version: Int,
    val updatedAt: Instant,
) {
    companion object {
        /** The only valid primary key. */
        const val SINGLE_ID = 1
    }
}

/** Stored form of [SearchPreset]; each preset owns one periodic work request. */
@Entity(tableName = "search_preset")
data class SearchPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val keywords: List<String>,
    val portalIds: List<Long>,
    val context: String,
    val filters: JobFilters,
    val schedule: RunSchedule,
    val enabled: Boolean,
)

/** Stored form of [CompanyWatchlistEntry]; one row per (ATS, board). */
@Entity(
    tableName = "company_watchlist",
    indices = [Index(value = ["atsType", "boardSlug"], unique = true)],
)
data class CompanyWatchlistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val atsType: AtsType,
    val boardSlug: String,
    val enabled: Boolean,
)

/** Stored form of [SourceCursor]; makes discovery incremental per source. */
@Entity(tableName = "source_cursor")
data class SourceCursorEntity(
    @PrimaryKey val sourceKey: String,
    val lastSuccessAt: Instant?,
    val lastSeenExternalId: String?,
)

/** Maps the profile to its single stored row. */
fun Profile.toEntity() =
    ProfileEntity(
        resumeText = resumeText,
        skills = skills,
        experience = experience,
        education = education,
        preferences = preferences,
        dealBreakers = dealBreakers,
        answersBank = answersBank,
        writingSamples = writingSamples,
        version = version,
        updatedAt = updatedAt,
    )

/** Maps the stored profile row back to the domain type. */
fun ProfileEntity.toModel() =
    Profile(
        resumeText = resumeText,
        skills = skills,
        experience = experience,
        education = education,
        preferences = preferences,
        dealBreakers = dealBreakers,
        answersBank = answersBank,
        writingSamples = writingSamples,
        version = version,
        updatedAt = updatedAt,
    )

/** Maps a preset to its stored row. */
fun SearchPreset.toEntity() = SearchPresetEntity(id, name, keywords, portalIds, context, filters, schedule, enabled)

/** Maps a stored preset row back to the domain type. */
fun SearchPresetEntity.toModel() = SearchPreset(id, name, keywords, portalIds, context, filters, schedule, enabled)

/** Maps a watchlist entry to its stored row. */
fun CompanyWatchlistEntry.toEntity() = CompanyWatchlistEntity(id, name, atsType, boardSlug, enabled)

/** Maps a stored watchlist row back to the domain type. */
fun CompanyWatchlistEntity.toModel() = CompanyWatchlistEntry(id, name, atsType, boardSlug, enabled)

/** Maps a source cursor to its stored row. */
fun SourceCursor.toEntity() = SourceCursorEntity(sourceKey, lastSuccessAt, lastSeenExternalId)

/** Maps a stored cursor row back to the domain type. */
fun SourceCursorEntity.toModel() = SourceCursor(sourceKey, lastSuccessAt, lastSeenExternalId)
