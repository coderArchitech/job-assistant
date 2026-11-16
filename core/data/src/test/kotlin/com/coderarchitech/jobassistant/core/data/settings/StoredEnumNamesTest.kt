package com.coderarchitech.jobassistant.core.data.settings

import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.model.NetworkRequirement
import com.coderarchitech.jobassistant.core.model.RunInterval
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `settings.pb` stores these enums by constant name, so the names are a storage format. Renaming a
 * constant would silently reset every user's stored value to the default. If this test fails,
 * keep the old name (or map it in `SettingsMapper`) instead of updating the list.
 */
class StoredEnumNamesTest {
    @Test
    fun storedEnumNamesNeverChange() {
        val cases =
            listOf(
                "RunInterval" to RunInterval.entries.map { it.name } to
                    listOf("MANUAL", "EVERY_3_HOURS", "EVERY_6_HOURS", "EVERY_12_HOURS"),
                "NetworkRequirement" to NetworkRequirement.entries.map { it.name } to listOf("ANY", "UNMETERED"),
                "AiProvider" to AiProvider.entries.map { it.name } to listOf("ANTHROPIC", "OPENAI"),
                "AiTask" to AiTask.entries.map { it.name } to
                    listOf(
                        "PARSE_RESUME",
                        "EXPAND_CONTEXT",
                        "SCORE",
                        "EXTRACT_POSTINGS",
                        "TAILOR",
                        "MAP_QUESTION",
                        "BROWSER_STEP",
                    ),
            )
        for ((named, expected) in cases) {
            val (enumName, actual) = named
            // New constants may be added; existing ones must keep their names.
            assertEquals("$enumName lost or renamed a stored constant", expected, actual.filter { it in expected })
            assertEquals(
                "$enumName lost a stored constant",
                expected.toSet(),
                expected.toSet().intersect(actual.toSet()),
            )
        }
    }
}
