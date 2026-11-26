package com.coderarchitech.jobassistant.sources.normalize

import com.coderarchitech.jobassistant.core.model.Salary
import com.coderarchitech.jobassistant.core.model.SalaryPeriod
import com.coderarchitech.jobassistant.core.model.WorkMode
import com.coderarchitech.jobassistant.sources.RawPosting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizerPartsTest {
    private fun inr(
        min: Long?,
        max: Long?,
        period: SalaryPeriod = SalaryPeriod.YEAR,
    ) = Salary(min, max, "INR", period)

    @Test
    fun salariesAsPeopleWriteThem() {
        val cases =
            listOf(
                // Indian formats.
                "12–18 LPA" to inr(1_200_000, 1_800_000),
                "12-18 LPA" to inr(1_200_000, 1_800_000),
                "12 - 18 Lakhs per annum" to inr(1_200_000, 1_800_000),
                "15-25 Lacs P.A." to inr(1_500_000, 2_500_000),
                "₹12L - ₹18L" to inr(1_200_000, 1_800_000),
                "INR 12,00,000 - 18,00,000" to inr(1_200_000, 1_800_000),
                "Rs. 8,50,000 to 12,00,000 per annum" to inr(850_000, 1_200_000),
                "₹1.2 Cr" to inr(12_000_000, 12_000_000),
                "Up to 30 LPA" to inr(null, 3_000_000),
                "₹50,000 per month" to inr(50_000, 50_000, SalaryPeriod.MONTH),
                "₹25,000/month stipend" to inr(25_000, 25_000, SalaryPeriod.MONTH),
                // Other currencies and forms.
                "\$90,000 - \$120,000" to Salary(90_000, 120_000, "USD"),
                "USD 150,000 – 200,000" to Salary(150_000, 200_000, "USD"),
                "\$57-67K" to Salary(57_000, 67_000, "USD"),
                "€60k–€75k" to Salary(60_000, 75_000, "EUR"),
                "£50,000" to Salary(50_000, 50_000, "GBP"),
                "From £45k" to Salary(45_000, null, "GBP"),
                "\$120k+" to Salary(120_000, null, "USD"),
                "CAD 80,000 to 95,000" to Salary(80_000, 95_000, "CAD"),
                "\$40 per hour" to Salary(40, 40, "USD", SalaryPeriod.HOUR),
                "\$120,000 - \$90,000" to Salary(90_000, 120_000, "USD"),
            )
        val wrong = cases.filter { (text, expected) -> SalaryParser.parse(text) != expected }
        assertEquals(wrong.joinToString("\n") { "${it.first} -> ${SalaryParser.parse(it.first)}" }, 0, wrong.size)
    }

    @Test
    fun textWithoutACurrencyOrAmountIsNoSalary() {
        for (text in listOf("", "Competitive", "Offers Equity", "DOE", "12-18", "2026 graduates", "Negotiable")) {
            assertNull(text, SalaryParser.parse(text))
        }
    }

    @Test
    fun aSalaryIsFoundOnlyInASentenceAboutPay() {
        val description =
            "We raised \$50M in our Series B and serve 10M users.\n" +
                "The annual base salary range for this role is \$150,000 - \$200,000 USD."

        assertEquals(Salary(150_000, 200_000, "USD"), SalaryParser.findIn(description))
        assertNull(SalaryParser.findIn("Loans from ₹50,000 to ₹5 Lakh. We raised \$50M."))
        assertEquals(inr(1_800_000, 2_400_000), SalaryParser.findIn("Role: Android\nCTC: 18-24 LPA\nNotice: 30 days"))
    }

    @Test
    fun htmlBecomesReadablePlainText() {
        val html =
            "<div><h2>About</h2><p>We build&nbsp;<b>payments</b> &amp; more.</p>" +
                "<ul><li>Kotlin</li><li>Compose</li></ul><script>alert(1)</script><p>Apply   now</p></div>"

        assertEquals(
            "About\n\nWe build payments & more.\n\n- Kotlin\n- Compose\n\nApply now",
            HtmlText.toPlainText(html),
        )
        assertEquals("plain text passes through", "Just text", HtmlText.toPlainText("Just text"))
    }

    @Test
    fun descriptionsAreCappedAtAWordBreak() {
        val text = "word ".repeat(3_000).trim()

        val capped = HtmlText.cap(text)

        assertTrue(capped.length <= HtmlText.PROMPT_CAP)
        assertTrue(capped.endsWith("word…"))
        assertEquals("short text is untouched", "short", HtmlText.cap("short"))
    }

    @Test
    fun companyKeysMatchTheSameCompany() {
        val same =
            listOf(
                listOf("Stripe", "Stripe, Inc.", "STRIPE INC", " stripe "),
                listOf("Acme Technologies Pvt. Ltd.", "Acme Technologies Private Limited", "ACME TECHNOLOGIES"),
                listOf("The Browser Company", "Browser Company"),
                listOf("Procter & Gamble", "Procter and Gamble Co."),
                listOf("Nestlé S.A.", "Nestle"),
            )
        for (group in same) {
            assertEquals(group.toString(), 1, group.map(CompanyNames::key).distinct().size)
        }
        assertEquals("a name of legal-form words keeps them", "company", CompanyNames.key("Company"))
        assertFalse(CompanyNames.key("Zeta") == CompanyNames.key("Zetta"))
    }

    @Test
    fun workModeFromTheDescriptionOnlyForClearPhrases() {
        fun mode(description: String) = WorkModes.detect(null, "Bengaluru, India", "Android Engineer", description)

        assertEquals(WorkMode.HYBRID, mode("This is a hybrid role with 3 days a week in the office."))
        assertEquals(WorkMode.REMOTE, mode("We are a remote-first team."))
        assertEquals(WorkMode.ONSITE, mode("You will work from our office in Koramangala."))
        assertEquals("a passing mention is not a mode", WorkMode.UNKNOWN, mode("Our app helps remote teams plan."))
        assertEquals(
            "the source's field wins",
            WorkMode.ONSITE,
            WorkModes.detect("on-site", "Remote", "Engineer", null),
        )
    }

    @Test
    fun regionLimitsStatedInTheDescription() {
        val limits =
            RegionLimits.find(
                WorkMode.REMOTE,
                "Remote",
                "You must be legally authorized to work in the United States. We are unable to sponsor visas.",
            )

        assertEquals("Must be authorized to work in United States; No visa sponsorship", limits)
        assertEquals(
            "EU only",
            RegionLimits.find(WorkMode.REMOTE, null, "This role is only open to candidates based in EU."),
        )
        assertNull("an office city is not a limit", RegionLimits.find(WorkMode.ONSITE, "Pune, India", "Join us."))
    }

    @Test
    fun aPostingIsNormalizedEndToEnd() {
        val raw =
            RawPosting(
                externalId = "42",
                title = "  Senior   Android Engineer ",
                company = "Acme Pvt. Ltd.",
                url = " https://jobs.example/42 ",
                location = "Remote - India",
                description = "<p>CTC: 30-40 LPA</p>",
                applyUrl = " ",
            )

        val posting = JobNormalizer.normalize(raw, "lever:acme")

        assertEquals("Senior Android Engineer", posting.title)
        assertEquals("Acme Pvt. Ltd.", posting.company)
        assertEquals("acme", posting.companyKey)
        assertEquals(WorkMode.REMOTE, posting.workMode)
        assertEquals("India only", posting.regionRestriction)
        assertEquals(inr(3_000_000, 4_000_000), posting.salary)
        assertEquals("CTC: 30-40 LPA", posting.description)
        assertEquals("https://jobs.example/42", posting.url)
        assertNull(posting.applyUrl)
        assertEquals("lever:acme", posting.source)
    }
}
