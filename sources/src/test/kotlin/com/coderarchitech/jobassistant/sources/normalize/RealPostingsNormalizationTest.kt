package com.coderarchitech.jobassistant.sources.normalize

import com.coderarchitech.jobassistant.core.model.Salary
import com.coderarchitech.jobassistant.core.model.SalaryPeriod
import com.coderarchitech.jobassistant.core.model.WorkMode
import com.coderarchitech.jobassistant.core.model.WorkMode.HYBRID
import com.coderarchitech.jobassistant.core.model.WorkMode.ONSITE
import com.coderarchitech.jobassistant.core.model.WorkMode.REMOTE
import com.coderarchitech.jobassistant.core.model.WorkMode.UNKNOWN
import com.coderarchitech.jobassistant.sources.RawPosting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task T2.10 Done when: a table-driven test of at least 50 real postings. Each row is a real posting's title,
 * location, the source's own work-mode field and salary text, recorded from the live Remote OK, Remotive,
 * We Work Remotely, Ashby, Greenhouse and Lever feeds in November 2026, with the expected result labelled by
 * hand from the posting, not from this code.
 */
class RealPostingsNormalizationTest {
    private class Row(
        val source: String,
        val title: String,
        val location: String?,
        val hint: String? = null,
        val compensation: String? = null,
        val description: String? = null,
        val mode: WorkMode,
        val region: String? = null,
        val salary: Salary? = null,
    )

    private fun usd(
        min: Long,
        max: Long,
        period: SalaryPeriod = SalaryPeriod.YEAR,
    ) = Salary(min, max, "USD", period)

    private fun eur(
        min: Long,
        max: Long,
    ) = Salary(min, max, "EUR")

    private fun remoteOk(
        title: String,
        location: String,
        region: String?,
    ) = Row("remoteok", title, location, hint = "remote", mode = REMOTE, region = region)

    private fun remotive(
        title: String,
        location: String,
        salary: String,
        region: String?,
        expected: Salary?,
    ) = Row("remotive", title, location, "remote", salary, mode = REMOTE, region = region, salary = expected)

    private fun wwr(
        title: String,
        location: String,
        region: String?,
    ) = Row("weworkremotely:remote-design-jobs", title, location, hint = "remote", mode = REMOTE, region = region)

    private val rows =
        listOf(
            // Remote OK: the location is where candidates must be.
            remoteOk("Product Manager, Developer Ecosystem", "US Only", "US only"),
            remoteOk("Manager, Technical Accounting and Equity Reporting", "US Only", "US only"),
            remoteOk("Senior SRE, Site Experience", "US Only", "US only"),
            remoteOk("1099 Bilingual NP/PA - Virtual Urgent Care", "Worldwide", null),
            remoteOk("Regional Sales Director, Canada", "Canada Only", "Canada only"),
            remoteOk("Strategic Account Executive - Spain", "Europe", "Europe only"),
            remoteOk("Sr Analyst, Analytics & Insights", "North America", "North America only"),
            remoteOk("Deal Desk Analyst (Eastern Time Zone Preferred)", "US Only", "US only"),
            remoteOk("Corporate Counsel, Commercial", "Worldwide", null),
            remoteOk("Senior Software Engineer - Behavioral Security Products", "UK Only", "UK only"),
            remoteOk("Global Tax Intern", "US Only", "US only"),
            remoteOk("Physician - Virtual Urgent Care (Part-Time)", "Worldwide", null),
            // Remotive: candidate_required_location and the salary as written.
            remotive(
                "WordPress Theme & Plugin Developer",
                "Americas, LATAM, Canada, USA timezones",
                "\$57K - \$67K",
                "Americas, LATAM, Canada, USA timezones only",
                usd(57_000, 67_000),
            ),
            remotive(
                "Content Reviewer - United States",
                "USA",
                "\$14/hour",
                "USA only",
                usd(14, 14, SalaryPeriod.HOUR),
            ),
            remotive(
                "Tech Lead Full-Stack Rails Engineer",
                "USA, Canada, USA timezones",
                "\$170k - \$200k",
                "USA, Canada, USA timezones only",
                usd(170_000, 200_000),
            ),
            remotive(
                "Content Creator - YouTube",
                "USA, Canada",
                "\$85k - \$155k",
                "USA, Canada only",
                usd(85_000, 155_000),
            ),
            remotive("Remote Office Assistant", "Worldwide", "\$35,3k- \$52k", null, usd(35_300, 52_000)),
            remotive(
                "Senior Data Engineer",
                "LATAM, Europe, USA, Canada, APAC",
                "",
                "LATAM, Europe, USA, Canada, APAC only",
                null,
            ),
            remotive(
                "Customer Service Representative",
                "USA",
                "\$14 - \$20/hour",
                "USA only",
                usd(14, 20, SalaryPeriod.HOUR),
            ),
            remotive("Inside Sales Contractor", "Worldwide", "OTE \$25k - \$35k", null, usd(25_000, 35_000)),
            remotive("Tier III Service Desk Engineer", "Worldwide", "", null, null),
            remotive(
                "US Remote Patient Care Advocate (Customer Service)",
                "USA",
                "\$25k - \$35k",
                "USA only",
                usd(25_000, 35_000),
            ),
            remotive("Freelance Writer", "Worldwide", "\$50-\$75 /hour", null, usd(50, 75, SalaryPeriod.HOUR)),
            remotive("Freelance Copywriter", "Worldwide", "\$20k -\$35k", null, usd(20_000, 35_000)),
            // The feed sent a broken character where the dash was.
            remotive(
                "Full-Stack Developer",
                "Worldwide",
                "\$3.5k�\$5k/month",
                null,
                usd(3_500, 5_000, SalaryPeriod.MONTH),
            ),
            remotive(
                "Senior Back-end Developerusa",
                "Europe, USA, Canada, Australia, Singapore, Mexico, Saudi Arabia",
                "",
                "Europe, USA, Canada, Australia, Singapore, Mexico, Saudi Arabia only",
                null,
            ),
            remotive(
                "Content Reviewer - United States",
                "USA",
                "\$14/hour",
                "USA only",
                usd(14, 14, SalaryPeriod.HOUR),
            ),
            // We Work Remotely: region, then country and state as the client joins them.
            wwr(
                "Director, Business Solutions",
                "Anywhere in the World; United States of America",
                "United States of America only",
            ),
            wwr("Senior Director, Product Management, Agentic Platform", "Anywhere in the World", null),
            wwr("Sets&Props Concept Artist Internship", "Europe Only", "Europe only"),
            wwr("Remote High-End Automotive AI Retoucher", "Africa Only", "Africa only"),
            wwr("Business Solutions Partner - Enterprise AI", "Anywhere in the World; Costa Rica", "Costa Rica only"),
            wwr("Graphic Design Intern (Remote)", "Asia Only", "Asia only"),
            wwr("Instructional Designer", "USA Only", "USA only"),
            wwr("Senior Creative & Web Designer", "Anywhere in the World; Florida", "Florida only"),
            wwr(
                "Graphic Designer",
                "Anywhere in the World; United States of America; Florida",
                "United States of America, Florida only",
            ),
            wwr("Senior Web Application Security Engineer", "Anywhere in the World", null),
            wwr("Senior Software Engineer", "USA Only", "USA only"),
            // Ashby: workplaceType and the salary-only summary.
            Row(
                "ashby:ashby",
                "Engineering Manager - EU",
                "Remote - European Union",
                "remote",
                "€150K - €250K",
                mode = REMOTE,
                region = "European Union only",
                salary = eur(150_000, 250_000),
            ),
            Row(
                "ashby:ashby",
                "Mid Market Account Executive - EMEA (French Speaking)",
                "France",
                "remote",
                "€110K - €195.5K",
                mode = REMOTE,
                region = "France only",
                salary = eur(110_000, 195_500),
            ),
            Row(
                "ashby:ashby",
                "Senior Product Designer",
                "Remote - US",
                "remote",
                "\$160K - \$225K",
                mode = REMOTE,
                region = "US only",
                salary = usd(160_000, 225_000),
            ),
            Row("ashby:ashby", "Designer", "Remote", "hybrid", mode = HYBRID),
            // Greenhouse: no work-mode field, so a city alone says nothing.
            Row("greenhouse:stripe", "Abuse Investigator", "Seattle, San Francisco, New York City", mode = UNKNOWN),
            Row("greenhouse:stripe", "Platform engineer", "Remote", mode = REMOTE),
            Row("greenhouse:groww", "Assistant Manager - Internal Audit", "Bengaluru-VTP, India", mode = UNKNOWN),
            Row("greenhouse:groww", "Relationship Manager", "India", mode = UNKNOWN),
            // Lever (Indian companies): workplaceType given.
            Row("lever:zeta", "Analyst II - SIEM/SecOps", "Hyderabad", "onsite", mode = ONSITE),
            Row("lever:zeta", "Associate Director - Legal", "Mumbai / Bangalore", "onsite", mode = ONSITE),
            Row(
                "lever:zeta",
                "Director - Product and Solutions Engineering",
                "East Coast",
                "remote",
                mode = REMOTE,
                region = "East Coast only",
            ),
            Row(
                "lever:meesho",
                "AM/ Manager - Risk & Decision Science",
                "Bangalore, Karnataka",
                "onsite",
                mode = ONSITE,
            ),
            Row("lever:meesho", "Cluster Head (Security & Investigation)", "Surat, Gujarat", "onsite", mode = ONSITE),
            Row("lever:cred", "fraud analytics engineer", "bengaluru", "onsite", mode = ONSITE),
            // A rupee amount that is not a salary (the product's loan size) must not become one.
            Row(
                "lever:cred",
                "area collections manager chennai- flows",
                "tamil nadu",
                "onsite",
                description =
                    "<p>With loan offerings ranging from ₹50,000 to ₹5 Lakh, we’re enabling instant loans.</p>",
                mode = ONSITE,
            ),
        )

    @Test
    fun atLeastFiftyRealPostingsNormalizeAsLabelled() {
        assertTrue("the Done when asks for at least 50, there are ${rows.size}", rows.size >= 50)
        val failures =
            rows.mapIndexedNotNull { i, row ->
                val raw =
                    RawPosting(
                        externalId = "row-$i",
                        title = row.title,
                        company = "Company",
                        url = "https://example.test/$i",
                        location = row.location,
                        description = row.description,
                        compensation = row.compensation,
                        workplaceType = row.hint,
                    )
                val result = JobNormalizer.normalize(raw, row.source)
                val wrong =
                    listOfNotNull(
                        "mode ${result.workMode}".takeIf { result.workMode != row.mode },
                        "region ${result.regionRestriction}".takeIf { result.regionRestriction != row.region },
                        "salary ${result.salary}".takeIf { result.salary != row.salary },
                    )
                if (wrong.isEmpty()) null else "row $i '${row.title}': ${wrong.joinToString()}"
            }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }
}
