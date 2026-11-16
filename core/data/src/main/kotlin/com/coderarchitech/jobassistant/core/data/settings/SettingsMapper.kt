package com.coderarchitech.jobassistant.core.data.settings

import com.coderarchitech.jobassistant.core.data.settings.proto.AiSettingsProto
import com.coderarchitech.jobassistant.core.data.settings.proto.BudgetProto
import com.coderarchitech.jobassistant.core.data.settings.proto.CapsProto
import com.coderarchitech.jobassistant.core.data.settings.proto.FeatureTogglesProto
import com.coderarchitech.jobassistant.core.data.settings.proto.ScheduleProto
import com.coderarchitech.jobassistant.core.data.settings.proto.SettingsProto
import com.coderarchitech.jobassistant.core.data.settings.proto.ThresholdsProto
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiSettings
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.model.AppSettings
import com.coderarchitech.jobassistant.core.model.Budget
import com.coderarchitech.jobassistant.core.model.Caps
import com.coderarchitech.jobassistant.core.model.FeatureToggles
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.TierThresholds
import java.time.LocalTime

/** Schema version the mapper writes; see the rules at the top of `settings.proto`. */
internal const val SETTINGS_SCHEMA_VERSION = 1

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

/**
 * Writes every field, so a stored message is always complete. [AppSettings.reportTime] is stored
 * to the minute; seconds are dropped.
 */
internal fun AppSettings.toProto(): SettingsProto =
    SettingsProto
        .newBuilder()
        .setSchemaVersion(SETTINGS_SCHEMA_VERSION)
        .setThresholds(
            ThresholdsProto
                .newBuilder()
                .setHigh(thresholds.high)
                .setPartial(thresholds.partial),
        ).setDefaultSchedule(
            ScheduleProto
                .newBuilder()
                .setInterval(defaultSchedule.interval.name)
                .setNetwork(defaultSchedule.network.name)
                .setChargingOnly(defaultSchedule.chargingOnly),
        ).setReportMinuteOfDay(reportTime.hour * MINUTES_PER_HOUR + reportTime.minute)
        .setCaps(
            CapsProto
                .newBuilder()
                .setOverallDailyApplies(caps.overallDailyApplies)
                .setDefaultPortalDailyApplies(caps.defaultPortalDailyApplies)
                .setDailyEmails(caps.dailyEmails),
        ).setBudget(
            BudgetProto
                .newBuilder()
                .setMonthlyCapMicros(budget.monthlyCapMicros)
                .setBrowserAgentDailyMicros(budget.browserAgentDailyMicros),
        ).setAi(
            AiSettingsProto
                .newBuilder()
                .apply { ai.provider?.let { setProvider(it.name) } }
                .putAllModelOverrides(ai.modelOverrides.mapKeys { (task, _) -> task.name }),
        ).setToggles(
            FeatureTogglesProto
                .newBuilder()
                .setAutoSendEmails(toggles.autoSendEmails)
                .setEmailDigest(toggles.emailDigest),
        ).build()

/**
 * Never throws: an absent field takes the domain default, an unknown enum name takes the default,
 * and a group whose stored values fail validation falls back to that group's defaults.
 */
internal fun SettingsProto.toDomain(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        thresholds = thresholds.toDomain(defaults.thresholds),
        defaultSchedule = defaultSchedule.toDomain(defaults.defaultSchedule),
        reportTime =
            if (hasReportMinuteOfDay() && reportMinuteOfDay in 0 until MINUTES_PER_DAY) {
                LocalTime.of(reportMinuteOfDay / MINUTES_PER_HOUR, reportMinuteOfDay % MINUTES_PER_HOUR)
            } else {
                defaults.reportTime
            },
        caps = caps.toDomain(defaults.caps),
        budget = budget.toDomain(defaults.budget),
        ai = ai.toDomain(),
        toggles = toggles.toDomain(defaults.toggles),
    )
}

private fun ThresholdsProto.toDomain(default: TierThresholds): TierThresholds =
    validOrDefault(default) {
        TierThresholds(
            high = if (hasHigh()) high else default.high,
            partial = if (hasPartial()) partial else default.partial,
        )
    }

private fun ScheduleProto.toDomain(default: RunSchedule): RunSchedule =
    RunSchedule(
        interval = enumByName(interval, default.interval),
        network = enumByName(network, default.network),
        chargingOnly = if (hasChargingOnly()) chargingOnly else default.chargingOnly,
    )

private fun CapsProto.toDomain(default: Caps): Caps =
    validOrDefault(default) {
        Caps(
            overallDailyApplies = if (hasOverallDailyApplies()) overallDailyApplies else default.overallDailyApplies,
            defaultPortalDailyApplies =
                if (hasDefaultPortalDailyApplies()) defaultPortalDailyApplies else default.defaultPortalDailyApplies,
            dailyEmails = if (hasDailyEmails()) dailyEmails else default.dailyEmails,
        )
    }

private fun BudgetProto.toDomain(default: Budget): Budget =
    validOrDefault(default) {
        Budget(
            monthlyCapMicros = if (hasMonthlyCapMicros()) monthlyCapMicros else default.monthlyCapMicros,
            browserAgentDailyMicros =
                if (hasBrowserAgentDailyMicros()) browserAgentDailyMicros else default.browserAgentDailyMicros,
        )
    }

private fun AiSettingsProto.toDomain(): AiSettings =
    AiSettings(
        provider = if (hasProvider()) AiProvider.entries.firstOrNull { it.name == provider } else null,
        modelOverrides =
            modelOverridesMap
                .mapNotNull { (taskName, model) ->
                    val task = AiTask.entries.firstOrNull { it.name == taskName }
                    if (task == null || model.isBlank()) null else task to model
                }.toMap(),
    )

private fun FeatureTogglesProto.toDomain(default: FeatureToggles): FeatureToggles =
    FeatureToggles(
        autoSendEmails = if (hasAutoSendEmails()) autoSendEmails else default.autoSendEmails,
        emailDigest = if (hasEmailDigest()) emailDigest else default.emailDigest,
    )

private inline fun <reified E : Enum<E>> enumByName(
    name: String,
    default: E,
): E = enumValues<E>().firstOrNull { it.name == name } ?: default

private inline fun <T> validOrDefault(
    default: T,
    build: () -> T,
): T =
    try {
        build()
    } catch (_: IllegalArgumentException) {
        default
    }
