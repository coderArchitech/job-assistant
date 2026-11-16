package com.coderarchitech.jobassistant.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** Value types stored as JSON columns survive a round trip with the JSON settings :core:data will use. */
class JsonValueTypesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun profileValueTypesRoundTrip() {
        val roles = listOf(Role("Acme", "Android Engineer", YearMonth.of(2021, 4), null, "Payments app"))
        val encoded = json.encodeToString(roles)
        assertTrue(encoded, encoded.contains("\"2021-04\""))
        assertEquals(roles, json.decodeFromString<List<Role>>(encoded))

        val preferences =
            Preferences(
                targetRoles = listOf("Senior Android Engineer"),
                seniority = Seniority.SENIOR,
                expectedCtc = Salary(3_000_000, 4_000_000, "INR"),
                workMode = WorkModePreference.REMOTE_OR_HYBRID,
                timeZones = listOf("Asia/Kolkata"),
            )
        assertEquals(preferences, json.decodeFromString<Preferences>(json.encodeToString(preferences)))
    }

    @Test
    fun searchAndRunValueTypesRoundTrip() {
        val filters = JobFilters(tiers = setOf(Tier.HIGH), workModes = setOf(WorkMode.REMOTE), postedWithinDays = 7)
        assertEquals(filters, json.decodeFromString<JobFilters>(json.encodeToString(filters)))
        val schedule = RunSchedule(RunInterval.EVERY_3_HOURS, NetworkRequirement.UNMETERED, chargingOnly = true)
        assertEquals(schedule, json.decodeFromString<RunSchedule>(json.encodeToString(schedule)))
        val errors = listOf(RunError(RunStage.SCORE, "HTTP 429", subject = "anthropic"))
        assertEquals(errors, json.decodeFromString<List<RunError>>(json.encodeToString(errors)))
    }

    @Test
    fun olderJsonWithoutNewFieldsStillDecodes() {
        val decoded = json.decodeFromString<Preferences>("""{"targetRoles":["Android"]}""")
        assertEquals(WorkModePreference.ANY, decoded.workMode)
    }
}
