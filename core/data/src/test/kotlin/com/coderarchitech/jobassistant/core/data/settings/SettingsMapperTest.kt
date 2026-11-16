package com.coderarchitech.jobassistant.core.data.settings

import com.coderarchitech.jobassistant.core.data.settings.proto.AiSettingsProto
import com.coderarchitech.jobassistant.core.data.settings.proto.BudgetProto
import com.coderarchitech.jobassistant.core.data.settings.proto.CapsProto
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
import com.coderarchitech.jobassistant.core.model.NetworkRequirement
import com.coderarchitech.jobassistant.core.model.RunInterval
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.TierThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class SettingsMapperTest {
    private val nonDefault =
        AppSettings(
            thresholds = TierThresholds(high = 85, partial = 60),
            defaultSchedule =
                RunSchedule(RunInterval.EVERY_12_HOURS, NetworkRequirement.UNMETERED, chargingOnly = true),
            reportTime = LocalTime.of(7, 45),
            caps = Caps(overallDailyApplies = 30, defaultPortalDailyApplies = 5, dailyEmails = 3),
            budget = Budget(monthlyCapMicros = 25_500_000, browserAgentDailyMicros = 750_000),
            ai =
                AiSettings(
                    provider = AiProvider.OPENAI,
                    modelOverrides =
                        mapOf(
                            AiTask.SCORE to "fake-model-small",
                            AiTask.TAILOR to "fake-model-large",
                            AiTask.BROWSER_STEP to "fake-model-vision",
                        ),
                ),
            toggles = FeatureToggles(autoSendEmails = true, emailDigest = true),
        )

    private fun roundTrip(settings: AppSettings) = settings.toProto().toDomain()

    /** Defaults with one group replaced in the stored proto. */
    private fun defaultsWith(edit: SettingsProto.Builder.() -> Unit): SettingsProto =
        AppSettings()
            .toProto()
            .toBuilder()
            .apply(edit)
            .build()

    @Test
    fun defaultsSurviveARoundTrip() {
        assertEquals(AppSettings(), roundTrip(AppSettings()))
    }

    @Test
    fun everyFieldChangedSurvivesARoundTrip() {
        val defaults = AppSettings()
        val changed =
            listOf(
                "thresholds" to (nonDefault.thresholds to defaults.thresholds),
                "schedule" to (nonDefault.defaultSchedule to defaults.defaultSchedule),
                "report time" to (nonDefault.reportTime to defaults.reportTime),
                "caps" to (nonDefault.caps to defaults.caps),
                "budget" to (nonDefault.budget to defaults.budget),
                "ai" to (nonDefault.ai to defaults.ai),
                "toggles" to (nonDefault.toggles to defaults.toggles),
            )
        // Guards the fixture: if a field still has its default, the round trip proves nothing for it.
        for ((name, values) in changed) assertTrue("$name differs from default", values.first != values.second)
        assertEquals(nonDefault, roundTrip(nonDefault))
    }

    @Test
    fun everyScheduleEnumValueSurvivesARoundTrip() {
        for (interval in RunInterval.entries) {
            for (network in NetworkRequirement.entries) {
                val settings = AppSettings(defaultSchedule = RunSchedule(interval, network, chargingOnly = true))
                assertEquals("$interval/$network", settings, roundTrip(settings))
            }
        }
    }

    @Test
    fun everyProviderSurvivesARoundTrip() {
        for (provider in AiProvider.entries + null) {
            val settings = AppSettings(ai = AiSettings(provider = provider))
            assertEquals("$provider", settings, roundTrip(settings))
        }
    }

    @Test
    fun anOverrideForEveryTaskSurvivesARoundTrip() {
        val settings = AppSettings(ai = AiSettings(modelOverrides = AiTask.entries.associateWith { "fake-${it.name}" }))
        assertEquals(settings, roundTrip(settings))
    }

    @Test
    fun reportTimeIsStoredToTheMinute() {
        val cases =
            listOf(
                LocalTime.MIDNIGHT to LocalTime.MIDNIGHT,
                LocalTime.of(23, 59) to LocalTime.of(23, 59),
                LocalTime.of(9, 30, 42) to LocalTime.of(9, 30),
            )
        for ((stored, read) in cases) {
            assertEquals("$stored", read, roundTrip(AppSettings(reportTime = stored)).reportTime)
        }
    }

    @Test
    fun anEmptyMessageMapsToTheDefaults() {
        assertEquals(AppSettings(), SettingsProto.getDefaultInstance().toDomain())
    }

    @Test
    fun legitimateZerosAndFalsesSurviveARoundTrip() {
        val zeros =
            nonDefault.copy(
                thresholds = TierThresholds(high = 1, partial = 0),
                reportTime = LocalTime.MIDNIGHT,
                caps = Caps(overallDailyApplies = 0, defaultPortalDailyApplies = 0, dailyEmails = 0),
                budget = Budget(monthlyCapMicros = 0, browserAgentDailyMicros = 0),
                toggles = FeatureToggles(autoSendEmails = false, emailDigest = false),
            )
        assertEquals(zeros, roundTrip(zeros))
    }

    @Test
    fun unreadableStoredValuesFallBackWithoutThrowing() {
        val defaults = AppSettings()
        val cases: List<Triple<String, SettingsProto, AppSettings>> =
            listOf(
                Triple(
                    "unknown interval",
                    defaultsWith { defaultSchedule = defaultSchedule.toBuilder().setInterval("EVERY_MINUTE").build() },
                    defaults,
                ),
                Triple(
                    "unknown network, other schedule fields kept",
                    defaultsWith {
                        defaultSchedule =
                            ScheduleProto
                                .newBuilder()
                                .setInterval(RunInterval.MANUAL.name)
                                .setNetwork("SATELLITE")
                                .setChargingOnly(true)
                                .build()
                    },
                    defaults.copy(defaultSchedule = RunSchedule(RunInterval.MANUAL, NetworkRequirement.ANY, true)),
                ),
                Triple(
                    "unknown provider",
                    defaultsWith { ai = AiSettingsProto.newBuilder().setProvider("SOME_FUTURE_AI").build() },
                    defaults,
                ),
                Triple(
                    "unknown task key dropped, known task kept",
                    defaultsWith {
                        ai =
                            AiSettingsProto
                                .newBuilder()
                                .putModelOverrides("FUTURE_TASK", "fake-model-a")
                                .putModelOverrides(AiTask.SCORE.name, "fake-model-b")
                                .build()
                    },
                    defaults.copy(ai = AiSettings(modelOverrides = mapOf(AiTask.SCORE to "fake-model-b"))),
                ),
                Triple(
                    "blank override dropped, other override kept",
                    defaultsWith {
                        ai =
                            AiSettingsProto
                                .newBuilder()
                                .setProvider(AiProvider.ANTHROPIC.name)
                                .putModelOverrides(AiTask.TAILOR.name, "  ")
                                .putModelOverrides(AiTask.SCORE.name, "fake-model-b")
                                .build()
                    },
                    defaults.copy(
                        ai = AiSettings(AiProvider.ANTHROPIC, mapOf(AiTask.SCORE to "fake-model-b")),
                    ),
                ),
            )
        for ((name, proto, expected) in cases) assertEquals(name, expected, proto.toDomain())
    }

    @Test
    fun anOutOfRangeReportMinuteFallsBackToNinePm() {
        val cases = listOf(MINUTES_IN_A_DAY, -1, Int.MAX_VALUE, Int.MIN_VALUE)
        for (minute in cases) {
            val read = defaultsWith { reportMinuteOfDay = minute }.toDomain()
            assertEquals("minute $minute", LocalTime.of(21, 0), read.reportTime)
        }
        val lastMinute = defaultsWith { reportMinuteOfDay = MINUTES_IN_A_DAY - 1 }.toDomain()
        assertEquals(LocalTime.of(23, 59), lastMinute.reportTime)
    }

    private companion object {
        const val MINUTES_IN_A_DAY = 1440
    }

    @Test
    fun invalidStoredValuesFallBackWithoutUndoingOtherLimits() {
        val stored = nonDefault.toProto()
        val cases: List<Triple<String, SettingsProto, AppSettings>> =
            listOf(
                Triple(
                    "partial equal to high",
                    stored.toBuilder().setThresholds(ThresholdsProto.newBuilder().setHigh(60).setPartial(60)).build(),
                    nonDefault.copy(thresholds = TierThresholds()),
                ),
                Triple(
                    "partial above high",
                    stored.toBuilder().setThresholds(ThresholdsProto.newBuilder().setHigh(40).setPartial(70)).build(),
                    nonDefault.copy(thresholds = TierThresholds()),
                ),
                Triple(
                    "high above 100",
                    stored.toBuilder().setThresholds(ThresholdsProto.newBuilder().setHigh(101).setPartial(70)).build(),
                    nonDefault.copy(thresholds = TierThresholds()),
                ),
                Triple(
                    // Only the bad field resets; the lowered portal cap (5) and overall cap survive.
                    "negative email cap",
                    stored
                        .toBuilder()
                        .setCaps(stored.caps.toBuilder().setDailyEmails(-1))
                        .build(),
                    nonDefault.copy(caps = nonDefault.caps.copy(dailyEmails = Caps.DEFAULT_DAILY_EMAILS)),
                ),
                Triple(
                    "negative monthly budget keeps the agent budget",
                    stored
                        .toBuilder()
                        .setBudget(BudgetProto.newBuilder().setMonthlyCapMicros(-5).setBrowserAgentDailyMicros(1))
                        .build(),
                    nonDefault.copy(budget = Budget(Budget.DEFAULT_MONTHLY_CAP_MICROS, browserAgentDailyMicros = 1)),
                ),
                Triple(
                    "model override that is not a model id is dropped",
                    stored
                        .toBuilder()
                        .setAi(stored.ai.toBuilder().putModelOverrides("SCORE", "sk-ant-fake-key"))
                        .build(),
                    nonDefault.copy(
                        ai = nonDefault.ai.copy(modelOverrides = nonDefault.ai.modelOverrides - AiTask.SCORE),
                    ),
                ),
            )
        for ((name, proto, expected) in cases) assertEquals(name, expected, proto.toDomain())
    }

    @Test
    fun aPartlyWrittenGroupFillsMissingFieldsWithDefaults() {
        val cases: List<Triple<String, SettingsProto, AppSettings>> =
            listOf(
                Triple(
                    "only high threshold",
                    SettingsProto.newBuilder().setThresholds(ThresholdsProto.newBuilder().setHigh(90)).build(),
                    AppSettings(thresholds = TierThresholds(high = 90, partial = TierThresholds().partial)),
                ),
                Triple(
                    "only email cap, as zero",
                    SettingsProto.newBuilder().setCaps(CapsProto.newBuilder().setDailyEmails(0)).build(),
                    AppSettings(caps = Caps(dailyEmails = 0)),
                ),
                Triple(
                    "only browser agent budget",
                    SettingsProto
                        .newBuilder()
                        .setBudget(BudgetProto.newBuilder().setBrowserAgentDailyMicros(200_000))
                        .build(),
                    AppSettings(budget = Budget(browserAgentDailyMicros = 200_000)),
                ),
            )
        for ((name, proto, expected) in cases) assertEquals(name, expected, proto.toDomain())
    }

    @Test
    fun theWrittenMessageIsCompleteAndVersioned() {
        val proto = AppSettings().toProto()
        assertEquals(SETTINGS_SCHEMA_VERSION, proto.schemaVersion)
        val present =
            listOf(
                "high" to proto.thresholds.hasHigh(),
                "partial" to proto.thresholds.hasPartial(),
                "interval" to proto.defaultSchedule.hasInterval(),
                "network" to proto.defaultSchedule.hasNetwork(),
                "charging only" to proto.defaultSchedule.hasChargingOnly(),
                "report minute" to proto.hasReportMinuteOfDay(),
                "overall cap" to proto.caps.hasOverallDailyApplies(),
                "portal cap" to proto.caps.hasDefaultPortalDailyApplies(),
                "email cap" to proto.caps.hasDailyEmails(),
                "monthly budget" to proto.budget.hasMonthlyCapMicros(),
                "browser agent budget" to proto.budget.hasBrowserAgentDailyMicros(),
                "auto send" to proto.toggles.hasAutoSendEmails(),
                "digest" to proto.toggles.hasEmailDigest(),
            )
        for ((name, has) in present) assertTrue(name, has)
    }
}
