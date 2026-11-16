package com.coderarchitech.jobassistant.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalTime

class SettingsTest {
    @Test
    fun defaultsMatchThePrd() {
        val settings = AppSettings()
        val cases =
            listOf(
                "high threshold" to (settings.thresholds.high to 75),
                "partial threshold" to (settings.thresholds.partial to 50),
                "default schedule" to (settings.defaultSchedule to RunSchedule()),
                "default interval" to (settings.defaultSchedule.interval to RunInterval.EVERY_6_HOURS),
                "default network" to (settings.defaultSchedule.network to NetworkRequirement.ANY),
                "default charging only" to (settings.defaultSchedule.chargingOnly to false),
                "report time" to (settings.reportTime to LocalTime.of(21, 0)),
                "overall daily applies" to (settings.caps.overallDailyApplies to 25),
                "default portal daily applies" to (settings.caps.defaultPortalDailyApplies to 10),
                "daily emails" to (settings.caps.dailyEmails to 10),
                "monthly budget" to (settings.budget.monthlyCapMicros to 10_000_000L),
                "browser agent daily budget" to (settings.budget.browserAgentDailyMicros to 0L),
                "provider" to (settings.ai.provider to null),
                "model overrides" to (settings.ai.modelOverrides to emptyMap<AiTask, String>()),
                "auto send emails" to (settings.toggles.autoSendEmails to false),
                "email digest" to (settings.toggles.emailDigest to false),
            )
        for ((name, values) in cases) assertEquals(name, values.second, values.first)
    }

    @Test
    fun defaultPortalCapMatchesTheNewPortalCap() {
        assertEquals(Portal.DEFAULT_DAILY_CAP, Caps().defaultPortalDailyApplies)
    }

    @Test
    fun invalidValuesAreRejected() {
        val cases: List<Pair<String, () -> Any>> =
            listOf(
                "negative overall cap" to { Caps(overallDailyApplies = -1) },
                "negative portal cap" to { Caps(defaultPortalDailyApplies = -1) },
                "negative email cap" to { Caps(dailyEmails = -1) },
                "negative monthly budget" to { Budget(monthlyCapMicros = -1) },
                "negative browser agent budget" to { Budget(browserAgentDailyMicros = -1) },
                "empty model override" to { AiSettings(modelOverrides = mapOf(AiTask.SCORE to "")) },
                "blank model override" to { AiSettings(modelOverrides = mapOf(AiTask.TAILOR to "  ")) },
                "one blank override among valid ones" to {
                    AiSettings(modelOverrides = mapOf(AiTask.SCORE to "model-a", AiTask.TAILOR to "\t"))
                },
                "partial equal to high" to { AppSettings(thresholds = TierThresholds(high = 60, partial = 60)) },
            )
        for ((name, build) in cases) {
            assertThrows(name, IllegalArgumentException::class.java) { build() }
        }
    }

    @Test
    fun zeroCapsAndBudgetsAreAllowed() {
        val caps = Caps(overallDailyApplies = 0, defaultPortalDailyApplies = 0, dailyEmails = 0)
        val budget = Budget(monthlyCapMicros = 0, browserAgentDailyMicros = 0)
        assertEquals(0, caps.overallDailyApplies + caps.defaultPortalDailyApplies + caps.dailyEmails)
        assertEquals(0L, budget.monthlyCapMicros + budget.browserAgentDailyMicros)
        val ai = AiSettings(modelOverrides = mapOf(AiTask.SCORE to "model-a"))
        assertEquals("model-a", ai.modelOverrides[AiTask.SCORE])
    }
}
