package com.coderarchitech.jobassistant.core.model

import kotlinx.serialization.Serializable

/** A saved search that runs on its own schedule (PRD FR2). */
data class SearchPreset(
    val id: Long,
    val name: String,
    val keywords: List<String>,
    val portalIds: List<Long>,
    /** Free-form context that shapes search and scoring, up to [MAX_CONTEXT_LENGTH] characters. */
    val context: String,
    val filters: JobFilters,
    val schedule: RunSchedule,
    val enabled: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "A preset needs a name" }
        require(context.length <= MAX_CONTEXT_LENGTH) { "Context is limited to $MAX_CONTEXT_LENGTH characters" }
    }

    companion object {
        /** PRD FR2: the context field holds up to 2,000 characters. */
        const val MAX_CONTEXT_LENGTH = 2_000
    }
}

/** Filters a preset applies to the Jobs list. Empty sets mean "any". */
@Serializable
data class JobFilters(
    val tiers: Set<Tier> = setOf(Tier.HIGH, Tier.PARTIAL),
    val sources: Set<String> = emptySet(),
    val workModes: Set<WorkMode> = emptySet(),
    val postedWithinDays: Int? = null,
    val minSalary: Salary? = null,
)

/** How often a preset runs and under which device conditions (PRD FR12). */
@Serializable
data class RunSchedule(
    val interval: RunInterval = RunInterval.EVERY_6_HOURS,
    val network: NetworkRequirement = NetworkRequirement.ANY,
    val chargingOnly: Boolean = false,
)

/** Supported run intervals. [MANUAL] runs only from "Run now". */
@Serializable
enum class RunInterval(
    val hours: Int?,
) {
    MANUAL(null),
    EVERY_3_HOURS(3),
    EVERY_6_HOURS(6),
    EVERY_12_HOURS(12),
}

/** Network a scheduled run may use. */
@Serializable
enum class NetworkRequirement { ANY, UNMETERED }

/** Which applicant tracking system a watchlist company uses. */
enum class AtsType { GREENHOUSE, LEVER, ASHBY }

/** A company whose public job board is checked on every run (PRD FR3). */
data class CompanyWatchlistEntry(
    val id: Long,
    val name: String,
    val atsType: AtsType,
    /** The board's identifier in that ATS, such as "stripe" in boards-api.greenhouse.io/v1/boards/stripe. */
    val boardSlug: String,
    val enabled: Boolean = true,
) {
    init {
        require(boardSlug.isNotBlank()) { "A watchlist entry needs a board slug" }
    }
}
