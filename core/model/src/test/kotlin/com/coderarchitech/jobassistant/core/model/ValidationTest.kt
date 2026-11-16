package com.coderarchitech.jobassistant.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ValidationTest {
    private val now = Instant.parse("2026-11-16T00:00:00Z")

    private fun match(
        score: Int = 80,
        reason: String = "Strong Kotlin match",
        stage: ScoreStage = ScoreStage.LLM,
        model: String? = "claude-haiku-5-5",
    ) = MatchResult(
        jobId = 1,
        profileVersion = 1,
        score = score,
        remoteEligible = RemoteEligibility.YES,
        matchedSkills = listOf("Kotlin"),
        missingSkills = emptyList(),
        redFlags = emptyList(),
        reason = reason,
        decidedBy = stage,
        model = model,
        scoredAt = now,
    )

    @Test
    fun matchResultEnforcesItsSchema() {
        assertEquals(Tier.HIGH, match().tier(TierThresholds()))
        assertThrows(IllegalArgumentException::class.java) { match(score = 101) }
        assertThrows(IllegalArgumentException::class.java) { match(reason = "x".repeat(141)) }
        assertThrows(IllegalArgumentException::class.java) { match(model = null) }
        match(score = 0, reason = "deal-breaker: crypto", stage = ScoreStage.HARD_FILTER, model = null)
    }

    @Test
    fun presetContextIsLimitedTo2000Characters() {
        fun preset(context: String) =
            SearchPreset(1, "Remote Android", listOf("android"), emptyList(), context, JobFilters(), RunSchedule())
        preset("x".repeat(SearchPreset.MAX_CONTEXT_LENGTH))
        assertThrows(IllegalArgumentException::class.java) {
            preset("x".repeat(SearchPreset.MAX_CONTEXT_LENGTH + 1))
        }
    }

    @Test
    fun salaryNeedsAConsistentRangeAndIsoCurrency() {
        Salary(min = 1_200_000, max = 1_800_000, currency = "INR")
        Salary(min = null, max = 150_000, currency = "USD")
        assertThrows(IllegalArgumentException::class.java) { Salary(min = null, max = null, currency = "INR") }
        assertThrows(IllegalArgumentException::class.java) { Salary(min = 2, max = 1, currency = "INR") }
        assertThrows(IllegalArgumentException::class.java) { Salary(min = 1, max = 2, currency = "Rupees") }
    }

    @Test
    fun portalKeepsCredentialsOutAndNeedsAnAdapterWhenBuiltIn() {
        val naukri =
            Portal(7, "Naukri", PortalType.BUILT_IN, "naukri", "https://example.test/login", null, LoginMethod.OTP)
        assertEquals("portal/7", naukri.vaultKey)
        assertEquals(10, naukri.dailyCap)
        assertThrows(IllegalArgumentException::class.java) {
            Portal(8, "Broken", PortalType.BUILT_IN, null, "https://example.test/login", null, LoginMethod.PASSWORD)
        }
        assertThrows(IllegalArgumentException::class.java) {
            naukri.copy(dailyCap = -1)
        }
    }

    @Test
    fun internalStatusesShowAsApproved() {
        val internal = JobStatus.entries.filter { it.isInternal }
        assertEquals(listOf(JobStatus.READY, JobStatus.NEEDS_INPUT, JobStatus.SUBMITTING), internal)
        internal.forEach { assertEquals(JobStatus.APPROVED, it.userFacing) }
        assertEquals(10, JobStatus.entries.count { !it.isInternal })
        assertTrue(JobStatus.OFFER.isFinal)
        assertFalse(JobStatus.APPLIED.isFinal)
    }

    @Test
    fun usageRejectsNegativeNumbers() {
        assertThrows(IllegalArgumentException::class.java) {
            UsageRecord(1, null, AiProvider.ANTHROPIC, "claude-haiku-5-5", AiTask.SCORE, -1, 0, 0, 0, now)
        }
    }
}
