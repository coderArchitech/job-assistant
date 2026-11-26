package com.coderarchitech.jobassistant.agent.scoring

import com.coderarchitech.jobassistant.core.model.DealBreakers
import com.coderarchitech.jobassistant.core.model.Job
import com.coderarchitech.jobassistant.core.model.JobStatus
import com.coderarchitech.jobassistant.core.model.Preferences
import com.coderarchitech.jobassistant.core.model.Profile
import com.coderarchitech.jobassistant.core.model.RemoteEligibility
import com.coderarchitech.jobassistant.core.model.Role
import com.coderarchitech.jobassistant.core.model.ScoreStage
import com.coderarchitech.jobassistant.core.model.Seniority
import com.coderarchitech.jobassistant.core.model.WorkMode
import com.coderarchitech.jobassistant.core.model.WorkMode.HYBRID
import com.coderarchitech.jobassistant.core.model.WorkMode.ONSITE
import com.coderarchitech.jobassistant.core.model.WorkMode.REMOTE
import com.coderarchitech.jobassistant.core.model.WorkMode.UNKNOWN
import com.coderarchitech.jobassistant.core.model.WorkModePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

class HardFiltersTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC)
    private val filters = HardFilters(clock)

    /** An Android engineer in Bengaluru with six years' experience, open to remote or hybrid work. */
    private val indiaProfile =
        profile(
            Preferences(
                workMode = WorkModePreference.REMOTE_OR_HYBRID,
                locations = listOf("Bengaluru, India"),
                timeZones = listOf("Asia/Kolkata"),
            ),
            experience = listOf(Role("Acme", "Android Engineer", YearMonth.of(2020, 11), null)),
        )

    private fun profile(
        preferences: Preferences,
        experience: List<Role> = emptyList(),
        dealBreakers: DealBreakers = DealBreakers(),
    ) = Profile(
        "",
        emptyList(),
        experience,
        emptyList(),
        preferences,
        dealBreakers,
        emptyList(),
        emptyList(),
        1,
        clock.instant(),
    )

    private fun job(
        title: String,
        location: String,
        mode: WorkMode,
        region: String?,
        company: String = "Company",
        description: String = "",
    ) = Job(
        id = 1,
        source = "test",
        externalId = title,
        company = company,
        title = title,
        location = location,
        workMode = mode,
        regionRestriction = region,
        salary = null,
        description = description,
        url = "https://example.test",
        canonicalUrl = "https://example.test",
        postedAt = null,
        dedupeHash = "h",
        status = JobStatus.FOUND,
        firstSeenAt = clock.instant(),
    )

    private class Row(
        val job: Job,
        /** The rule expected to fail, by its prefix, or null when the job should pass. */
        val fails: String?,
    )

    private fun row(
        title: String,
        location: String,
        mode: WorkMode,
        region: String?,
        fails: String?,
    ) = Row(job(title, location, mode, region), fails)

    /**
     * The 53 real postings of the normalizer's table (T2.10), as normalized, each labelled by hand with
     * what should happen for [indiaProfile].
     */
    private val labelled =
        listOf(
            row("Product Manager, Developer Ecosystem", "US Only", REMOTE, "US only", REGION),
            row("Manager, Technical Accounting and Equity Reporting", "US Only", REMOTE, "US only", REGION),
            row("Senior SRE, Site Experience", "US Only", REMOTE, "US only", REGION),
            row("1099 Bilingual NP/PA - Virtual Urgent Care", "Worldwide", REMOTE, null, null),
            row("Regional Sales Director, Canada", "Canada Only", REMOTE, "Canada only", SENIORITY),
            row("Strategic Account Executive - Spain", "Europe", REMOTE, "Europe only", REGION),
            row("Sr Analyst, Analytics & Insights", "North America", REMOTE, "North America only", REGION),
            row("Deal Desk Analyst (Eastern Time Zone Preferred)", "US Only", REMOTE, "US only", REGION),
            row("Corporate Counsel, Commercial", "Worldwide", REMOTE, null, null),
            row("Senior Software Engineer - Behavioral Security Products", "UK Only", REMOTE, "UK only", REGION),
            row("Global Tax Intern", "US Only", REMOTE, "US only", SENIORITY),
            row("Physician - Virtual Urgent Care (Part-Time)", "Worldwide", REMOTE, null, null),
            row(
                "WordPress Theme & Plugin Developer",
                "Americas, LATAM, Canada, USA timezones",
                REMOTE,
                "Americas, LATAM, Canada, USA timezones only",
                REGION,
            ),
            row("Content Reviewer - United States", "USA", REMOTE, "USA only", REGION),
            row(
                "Tech Lead Full-Stack Rails Engineer",
                "USA, Canada, USA timezones",
                REMOTE,
                "USA, Canada, USA timezones only",
                REGION,
            ),
            row("Content Creator - YouTube", "USA, Canada", REMOTE, "USA, Canada only", REGION),
            row("Remote Office Assistant", "Worldwide", REMOTE, null, null),
            row(
                "Senior Data Engineer",
                "LATAM, Europe, USA, Canada, APAC",
                REMOTE,
                "LATAM, Europe, USA, Canada, APAC only",
                null,
            ),
            row("Customer Service Representative", "USA", REMOTE, "USA only", REGION),
            row("Inside Sales Contractor", "Worldwide", REMOTE, null, null),
            row("Tier III Service Desk Engineer", "Worldwide", REMOTE, null, null),
            row("US Remote Patient Care Advocate (Customer Service)", "USA", REMOTE, "USA only", REGION),
            row("Freelance Writer", "Worldwide", REMOTE, null, null),
            row("Freelance Copywriter", "Worldwide", REMOTE, null, null),
            row("Full-Stack Developer", "Worldwide", REMOTE, null, null),
            row(
                "Senior Back-end Developerusa",
                "Europe, USA, Canada, Australia, Singapore, Mexico, Saudi Arabia",
                REMOTE,
                "Europe, USA, Canada, Australia, Singapore, Mexico, Saudi Arabia only",
                REGION,
            ),
            row("Content Reviewer - United States", "USA", REMOTE, "USA only", REGION),
            row(
                "Director, Business Solutions",
                "Anywhere in the World; United States of America",
                REMOTE,
                "United States of America only",
                SENIORITY,
            ),
            row(
                "Senior Director, Product Management, Agentic Platform",
                "Anywhere in the World",
                REMOTE,
                null,
                SENIORITY,
            ),
            row("Sets&Props Concept Artist Internship", "Europe Only", REMOTE, "Europe only", SENIORITY),
            row("Remote High-End Automotive AI Retoucher", "Africa Only", REMOTE, "Africa only", REGION),
            row(
                "Business Solutions Partner - Enterprise AI",
                "Anywhere in the World; Costa Rica",
                REMOTE,
                "Costa Rica only",
                REGION,
            ),
            row("Graphic Design Intern (Remote)", "Asia Only", REMOTE, "Asia only", SENIORITY),
            row("Instructional Designer", "USA Only", REMOTE, "USA only", REGION),
            row("Senior Creative & Web Designer", "Anywhere in the World; Florida", REMOTE, "Florida only", REGION),
            row(
                "Graphic Designer",
                "Anywhere in the World; United States of America; Florida",
                REMOTE,
                "United States of America, Florida only",
                REGION,
            ),
            row("Senior Web Application Security Engineer", "Anywhere in the World", REMOTE, null, null),
            row("Senior Software Engineer", "USA Only", REMOTE, "USA only", REGION),
            row("Engineering Manager - EU", "Remote - European Union", REMOTE, "European Union only", REGION),
            row("Mid Market Account Executive - EMEA (French Speaking)", "France", REMOTE, "France only", REGION),
            row("Senior Product Designer", "Remote - US", REMOTE, "US only", REGION),
            row("Designer", "Remote", HYBRID, null, LOCATION),
            row("Abuse Investigator", "Seattle, San Francisco, New York City", UNKNOWN, null, null),
            row("Platform engineer", "Remote", REMOTE, null, null),
            row("Assistant Manager - Internal Audit", "Bengaluru-VTP, India", UNKNOWN, null, null),
            row("Relationship Manager", "India", UNKNOWN, null, null),
            row("Analyst II - SIEM/SecOps", "Hyderabad", ONSITE, null, WORK_MODE),
            row("Associate Director - Legal", "Mumbai / Bangalore", ONSITE, null, WORK_MODE),
            row("Director - Product and Solutions Engineering", "East Coast", REMOTE, "East Coast only", SENIORITY),
            row("AM/ Manager - Risk & Decision Science", "Bangalore, Karnataka", ONSITE, null, WORK_MODE),
            row("Cluster Head (Security & Investigation)", "Surat, Gujarat", ONSITE, null, WORK_MODE),
            row("fraud analytics engineer", "bengaluru", ONSITE, null, WORK_MODE),
            row("area collections manager chennai- flows", "tamil nadu", ONSITE, null, WORK_MODE),
        )

    @Test
    fun everyRemoteUsOnlyPostingIsFilteredForAnIndiaBasedProfile() {
        // Limits that name only the US or a US state; "USA, Canada" or a list with APAC is not US only.
        val usOnlyLimits =
            setOf(
                "US only",
                "USA only",
                "United States of America only",
                "United States of America, Florida only",
                "Florida only",
            )
        val usOnly = labelled.filter { row -> row.job.workMode == REMOTE && row.job.regionRestriction in usOnlyLimits }
        assertTrue("the set has US-only postings", usOnly.size >= 10)
        val passed = usOnly.filter { filters.check(it.job, indiaProfile) == HardFilterResult.Pass }
        assertEquals(passed.joinToString { it.job.title }, 0, passed.size)
    }

    @Test
    fun theLabelledSetIsFilteredAsLabelled() {
        assertEquals(53, labelled.size)
        val wrong =
            labelled.mapNotNull { row ->
                val result = filters.check(row.job, indiaProfile)
                val actual = (result as? HardFilterResult.Fail)?.rule
                val ok = if (row.fails == null) actual == null else actual?.startsWith(row.fails) == true
                if (ok) null else "${row.job.title}: expected ${row.fails ?: "pass"}, got ${actual ?: "pass"}"
            }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun aFailureIsScoreZeroWithTheRuleAsReason() {
        val job = job("Senior Software Engineer", "USA Only", REMOTE, "USA only")
        val failure = filters.check(job, indiaProfile) as HardFilterResult.Fail

        val result = filters.resultFor(job, indiaProfile, failure)

        assertEquals("Region: USA only", result.reason)
        assertEquals(0, result.score)
        assertEquals(ScoreStage.HARD_FILTER, result.decidedBy)
        assertEquals(RemoteEligibility.NO, result.remoteEligible)
        assertEquals(null, result.model)
    }

    @Test
    fun dealBreakersMatchWholeWordsInTitleCompanyOrDescription() {
        val profile =
            profile(
                Preferences(),
                dealBreakers =
                    DealBreakers(
                        industries = listOf("gambling"),
                        companyTypes = listOf("staffing agency"),
                        keywords = listOf("PHP"),
                    ),
            )

        fun rule(job: Job) = (filters.check(job, profile) as? HardFilterResult.Fail)?.rule

        assertEquals("Deal-breaker: PHP", rule(job("PHP Developer", "", REMOTE, null)))
        assertEquals(
            "Deal-breaker industry: gambling",
            rule(job("Engineer", "", REMOTE, null, description = "An online Gambling platform")),
        )
        assertEquals(
            "Deal-breaker company type: staffing agency",
            rule(job("Engineer", "", REMOTE, null, company = "TalentCo, a staffing agency")),
        )
        assertEquals(
            "a word inside another word is not a match",
            null,
            rule(job("Graphics Engineer", "", REMOTE, null, description = "phpMyAdmin")),
        )
    }

    @Test
    fun workModeAndOfficeLocationFollowThePreference() {
        val remoteOnly =
            profile(Preferences(workMode = WorkModePreference.REMOTE_ONLY, locations = listOf("Bengaluru, India")))

        assertTrue(filters.check(job("Engineer", "Bengaluru", HYBRID, null), remoteOnly) is HardFilterResult.Fail)
        assertEquals(
            "an unknown mode never fails",
            HardFilterResult.Pass,
            filters.check(job("Engineer", "Pune", UNKNOWN, null), remoteOnly),
        )
        assertEquals(
            "Bangalore is Bengaluru",
            HardFilterResult.Pass,
            filters.check(job("Engineer", "Bangalore, Karnataka", HYBRID, null), indiaProfile),
        )
        assertEquals(
            "Location: Pune, India",
            (filters.check(job("Engineer", "Pune, India", HYBRID, null), indiaProfile) as HardFilterResult.Fail).rule,
        )
    }

    @Test
    fun seniorityComparesTheTitleWithTheProfile() {
        val junior = profile(Preferences(seniority = Seniority.JUNIOR))

        assertTrue(filters.check(job("Staff Engineer", "", REMOTE, null), junior) is HardFilterResult.Fail)
        assertEquals(HardFilterResult.Pass, filters.check(job("Software Engineer", "", REMOTE, null), junior))
        assertEquals(HardFilterResult.Pass, filters.check(job("Senior Engineer", "", REMOTE, null), indiaProfile))
        assertEquals(
            "no experience and no preference: the rule is skipped",
            HardFilterResult.Pass,
            filters.check(job("Director of Engineering", "", REMOTE, null), profile(Preferences())),
        )
    }

    @Test
    fun authorizationLimitsAndUnknownUsers() {
        val authorized =
            job("Engineer", "Remote", REMOTE, "Must be authorized to work in United States; No visa sponsorship")

        assertEquals(
            "Region: Must be authorized to work in United States",
            (filters.check(authorized, indiaProfile) as HardFilterResult.Fail).rule,
        )
        val usProfile = profile(Preferences(locations = listOf("Austin, TX, United States")))
        assertEquals(HardFilterResult.Pass, filters.check(authorized, usProfile))
        assertEquals(
            "a user whose country is unknown passes",
            HardFilterResult.Pass,
            filters.check(authorized, profile(Preferences())),
        )
    }

    private companion object {
        const val REGION = "Region"
        const val SENIORITY = "Seniority"
        const val WORK_MODE = "Work mode"
        const val LOCATION = "Location"
    }
}
