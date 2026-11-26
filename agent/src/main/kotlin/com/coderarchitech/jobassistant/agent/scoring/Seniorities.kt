package com.coderarchitech.jobassistant.agent.scoring

import com.coderarchitech.jobassistant.core.model.Seniority

/** Seniority read from a job title or from years of experience, on one ladder so they can be compared. */
internal object Seniorities {
    private fun words(vararg w: String) =
        Regex("(?<![\\p{L}])(${w.joinToString("|")})(?![\\p{L}])", RegexOption.IGNORE_CASE)

    /** The disciplines where "Staff" names a level, as in Staff Engineer; a Staff Accountant is not one. */
    private const val STAFF_ROLES =
        "(engineer|software|developer|scientist|designer|data|ml|machine|security|site|backend|frontend|" +
            "mobile|android|ios|product|platform|infrastructure|research engineer)"

    // Most senior first, so "Senior Director" is a director and "Senior Staff" is staff. A bare "Manager"
    // (Product, Project, Account Manager) is a role, not a level, so only a manager of engineers counts.
    private val TITLE_WORDS =
        listOf(
            Seniority.DIRECTOR to words("director", "vp", "vice president", "head of", "chief", "cto", "ceo", "cfo"),
            Seniority.PRINCIPAL to words("principal", "distinguished engineer"),
            Seniority.STAFF to words("staff $STAFF_ROLES"),
            Seniority.MANAGER to
                words(
                    "engineering manager",
                    "manager, engineering",
                    "software development manager",
                    "development manager",
                ),
            Seniority.SENIOR to words("senior", "sr", "snr", "lead"),
            Seniority.INTERN to words("intern", "internship", "trainee", "apprentice"),
            Seniority.JUNIOR to words("junior", "jr", "entry level", "entry-level", "graduate", "fresher", "associate"),
        )

    /**
     * The ladder: individual roles in order, with a manager at the staff step and a director above
     * principal. A user is filtered from a title two or more steps away.
     */
    fun rank(level: Seniority): Int = LADDER.indexOfFirst { level in it }

    private val LADDER =
        listOf(
            setOf(Seniority.INTERN),
            setOf(Seniority.JUNIOR),
            setOf(Seniority.MID),
            setOf(Seniority.SENIOR),
            setOf(Seniority.STAFF, Seniority.MANAGER),
            setOf(Seniority.PRINCIPAL),
            setOf(Seniority.DIRECTOR),
        )

    /** Years of experience below which a profile is at each level; beyond the last it is staff. */
    private val YEARS_BELOW =
        listOf(
            YEARS_INTERN to Seniority.INTERN,
            YEARS_JUNIOR to Seniority.JUNIOR,
            YEARS_MID to Seniority.MID,
            YEARS_SENIOR to Seniority.SENIOR,
        )

    /** The level a title names, or null when it names none (most titles are then mid-level, but unsaid). */
    fun fromTitle(title: String): Seniority? {
        // "Assistant Manager" and "Associate Director" are a step below the noun, not the noun.
        val lowered = title.replace(Regex("(?i)\\b(assistant|associate)\\s+(manager|director)\\b"), "senior")
        return TITLE_WORDS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(lowered) }?.first
    }

    /** The level for [years] of experience; null when unknown. */
    fun fromYears(years: Double?): Seniority? =
        years?.let { y -> YEARS_BELOW.firstOrNull { (below, _) -> y < below }?.second ?: Seniority.STAFF }
}

private const val YEARS_INTERN = 1.0
private const val YEARS_JUNIOR = 3.0
private const val YEARS_MID = 6.0
private const val YEARS_SENIOR = 10.0
