package com.coderarchitech.jobassistant.core.model

import java.time.LocalTime

/**
 * App-wide settings that are not records: thresholds, default schedule, report time, caps, budget,
 * AI model choices and feature toggles. Stored in Proto DataStore by `:core:data`.
 */
data class AppSettings(
    val thresholds: TierThresholds = TierThresholds(),
    /** Schedule given to new search presets; each preset keeps its own copy afterwards. */
    val defaultSchedule: RunSchedule = RunSchedule(),
    /** Local time (device zone) the daily report is built; PRD FR11 default is 9:00 PM. */
    val reportTime: LocalTime = DEFAULT_REPORT_TIME,
    val caps: Caps = Caps(),
    val budget: Budget = Budget(),
    val ai: AiSettings = AiSettings(),
    val toggles: FeatureToggles = FeatureToggles(),
) {
    companion object {
        /** PRD FR11: the daily report is ready at 9:00 PM by default. */
        val DEFAULT_REPORT_TIME: LocalTime = LocalTime.of(21, 0)
    }
}

/** Daily limits on applies and emails (PRD FR6, FR9). Zero turns that action off for the day. */
data class Caps(
    /** Applies across all portals per day. */
    val overallDailyApplies: Int = DEFAULT_OVERALL_DAILY_APPLIES,
    /** Cap given to a newly added portal; each portal keeps its own [Portal.dailyCap] afterwards. */
    val defaultPortalDailyApplies: Int = Portal.DEFAULT_DAILY_CAP,
    /** Recruiter emails sent per day. */
    val dailyEmails: Int = DEFAULT_DAILY_EMAILS,
) {
    init {
        require(overallDailyApplies >= 0) { "The overall daily cap cannot be negative" }
        require(defaultPortalDailyApplies >= 0) { "The default portal cap cannot be negative" }
        require(dailyEmails >= 0) { "The daily email cap cannot be negative" }
    }

    companion object {
        /** PRD FR6: at most 25 applies a day across all portals. */
        const val DEFAULT_OVERALL_DAILY_APPLIES = 25

        /** PRD FR9: at most 10 recruiter emails a day. */
        const val DEFAULT_DAILY_EMAILS = 10
    }
}

/** AI spending limits in micro-US-dollars (1_000_000 = $1), checked by the budget guard. */
data class Budget(
    /** Hard cap on estimated AI spend per calendar month. */
    val monthlyCapMicros: Long = DEFAULT_MONTHLY_CAP_MICROS,
    /** Daily spend allowed for the generic browser agent; zero keeps the agent off. */
    val browserAgentDailyMicros: Long = DEFAULT_BROWSER_AGENT_DAILY_MICROS,
) {
    init {
        require(monthlyCapMicros >= 0) { "The monthly budget cannot be negative" }
        require(browserAgentDailyMicros >= 0) { "The browser agent budget cannot be negative" }
    }

    companion object {
        /** $10 a month. */
        const val DEFAULT_MONTHLY_CAP_MICROS = 10_000_000L

        /** The generic browser agent is off until the user gives it a budget. */
        const val DEFAULT_BROWSER_AGENT_DAILY_MICROS = 0L
    }
}

/** The user's AI provider and per-task model overrides (PRD FR10). The API key lives in the vault. */
data class AiSettings(
    /** Chosen during onboarding; null until then. */
    val provider: AiProvider? = null,
    /** Model id per task; a task without an entry uses the remote-config default for the provider. */
    val modelOverrides: Map<AiTask, String> = emptyMap(),
) {
    init {
        require(modelOverrides.values.none { it.isBlank() }) { "A model override needs a model id" }
    }
}

/** Optional features that are off by default. */
data class FeatureToggles(
    /** Send recruiter emails without per-email approval (PRD FR9: approval is the default). */
    val autoSendEmails: Boolean = false,
    /** Email the daily report to the user as a digest. */
    val emailDigest: Boolean = false,
)
