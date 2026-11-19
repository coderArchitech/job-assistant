package com.coderarchitech.jobassistant.core.data.db

import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.model.ApplicationStatus
import com.coderarchitech.jobassistant.core.model.ApplyMode
import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.DraftKind
import com.coderarchitech.jobassistant.core.model.JobStatus
import com.coderarchitech.jobassistant.core.model.LoginMethod
import com.coderarchitech.jobassistant.core.model.NetworkRequirement
import com.coderarchitech.jobassistant.core.model.OutreachChannel
import com.coderarchitech.jobassistant.core.model.OutreachStatus
import com.coderarchitech.jobassistant.core.model.PortalType
import com.coderarchitech.jobassistant.core.model.RemoteEligibility
import com.coderarchitech.jobassistant.core.model.RunInterval
import com.coderarchitech.jobassistant.core.model.RunStage
import com.coderarchitech.jobassistant.core.model.RunStatus
import com.coderarchitech.jobassistant.core.model.SalaryPeriod
import com.coderarchitech.jobassistant.core.model.ScoreStage
import com.coderarchitech.jobassistant.core.model.Seniority
import com.coderarchitech.jobassistant.core.model.SessionStatus
import com.coderarchitech.jobassistant.core.model.Tier
import com.coderarchitech.jobassistant.core.model.Verdict
import com.coderarchitech.jobassistant.core.model.WorkMode
import com.coderarchitech.jobassistant.core.model.WorkModePreference
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `agent.db` stores these enums by constant name, in columns and in JSON, so the names are a storage
 * format. Renaming a constant would make every existing row fail to decode. If this test fails, keep the
 * old name (or add a migration that rewrites the stored values) instead of editing the list.
 */
class StoredDbEnumNamesTest {
    private fun assertKeeps(
        enumName: String,
        actual: List<String>,
        stored: List<String>,
    ) {
        // New constants may be added; stored ones must keep their names.
        val missing = stored.filterNot { it in actual }
        assertTrue("$enumName lost or renamed stored constants: $missing", missing.isEmpty())
    }

    @Test
    fun jobAndScoringEnumsKeepTheirStoredNames() {
        assertKeeps("WorkMode", WorkMode.entries.map { it.name }, listOf("REMOTE", "HYBRID", "ONSITE", "UNKNOWN"))
        assertKeeps("SalaryPeriod", SalaryPeriod.entries.map { it.name }, listOf("YEAR", "MONTH", "HOUR"))
        assertKeeps(
            "JobStatus",
            JobStatus.entries.map { it.name },
            listOf(
                "FOUND",
                "SCORED",
                "APPROVED",
                "READY",
                "NEEDS_INPUT",
                "SUBMITTING",
                "APPLIED",
                "REPLIED",
                "INTERVIEW",
                "OFFER",
                "REJECTED",
                "SKIPPED",
                "FAILED",
            ),
        )
        assertKeeps("RemoteEligibility", RemoteEligibility.entries.map { it.name }, listOf("YES", "NO", "UNCLEAR"))
        assertKeeps("ScoreStage", ScoreStage.entries.map { it.name }, listOf("HARD_FILTER", "EMBEDDING", "LLM"))
        assertKeeps(
            "DraftKind",
            DraftKind.entries.map { it.name },
            listOf("COVER_LETTER", "ANSWERS", "EMAIL", "LINKEDIN"),
        )
    }

    @Test
    fun applicationAndOutreachEnumsKeepTheirStoredNames() {
        assertKeeps("Verdict", Verdict.entries.map { it.name }, listOf("APPROVE", "REJECT"))
        assertKeeps(
            "ApplicationStatus",
            ApplicationStatus.entries.map { it.name },
            listOf("SUBMITTING", "SUBMITTED", "CHECK_MANUALLY", "APPLY_MANUALLY", "FAILED"),
        )
        assertKeeps("ApplyMode", ApplyMode.entries.map { it.name }, listOf("REVIEWED", "AUTO"))
        assertKeeps("OutreachChannel", OutreachChannel.entries.map { it.name }, listOf("EMAIL", "LINKEDIN"))
        assertKeeps(
            "OutreachStatus",
            OutreachStatus.entries.map { it.name },
            listOf("DRAFT", "APPROVED", "SENT", "REPLIED", "FAILED"),
        )
    }

    @Test
    fun portalRunAndAiEnumsKeepTheirStoredNames() {
        assertKeeps("PortalType", PortalType.entries.map { it.name }, listOf("BUILT_IN", "CUSTOM"))
        assertKeeps("LoginMethod", LoginMethod.entries.map { it.name }, listOf("PASSWORD", "OTP"))
        assertKeeps(
            "SessionStatus",
            SessionStatus.entries.map { it.name },
            listOf("UNKNOWN", "LOGGED_IN", "EXPIRED", "OTP_NEEDED", "FAILED"),
        )
        assertKeeps(
            "RunStatus",
            RunStatus.entries.map { it.name },
            listOf("RUNNING", "SUCCEEDED", "PARTIALLY_FAILED", "FAILED", "CANCELLED"),
        )
        assertKeeps("AiProvider", AiProvider.entries.map { it.name }, listOf("ANTHROPIC", "OPENAI"))
        assertKeeps(
            "AiTask",
            AiTask.entries.map { it.name },
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
        assertKeeps("AtsType", AtsType.entries.map { it.name }, listOf("GREENHOUSE", "LEVER", "ASHBY"))
    }

    @Test
    fun enumsInsideJsonColumnsKeepTheirStoredNames() {
        assertKeeps(
            "RunStage",
            RunStage.entries.map { it.name },
            listOf("DISCOVER", "SCORE", "TAILOR", "APPLY", "OUTREACH", "REPORT"),
        )
        assertKeeps("Tier", Tier.entries.map { it.name }, listOf("HIGH", "PARTIAL", "LOW"))
        assertKeeps(
            "Seniority",
            Seniority.entries.map { it.name },
            listOf("INTERN", "JUNIOR", "MID", "SENIOR", "STAFF", "PRINCIPAL", "MANAGER", "DIRECTOR"),
        )
        assertKeeps(
            "WorkModePreference",
            WorkModePreference.entries.map { it.name },
            listOf("REMOTE_ONLY", "REMOTE_OR_HYBRID", "ANY"),
        )
        assertKeeps(
            "RunInterval",
            RunInterval.entries.map { it.name },
            listOf("MANUAL", "EVERY_3_HOURS", "EVERY_6_HOURS", "EVERY_12_HOURS"),
        )
        assertKeeps("NetworkRequirement", NetworkRequirement.entries.map { it.name }, listOf("ANY", "UNMETERED"))
    }
}
