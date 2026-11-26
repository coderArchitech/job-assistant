package com.coderarchitech.jobassistant.agent.scoring

import com.coderarchitech.jobassistant.core.model.Job
import com.coderarchitech.jobassistant.core.model.MatchResult
import com.coderarchitech.jobassistant.core.model.Profile
import com.coderarchitech.jobassistant.core.model.RemoteEligibility
import com.coderarchitech.jobassistant.core.model.ScoreStage
import com.coderarchitech.jobassistant.core.model.Seniority
import com.coderarchitech.jobassistant.core.model.WorkMode
import com.coderarchitech.jobassistant.core.model.WorkModePreference
import java.time.Clock
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

/** What the hard filters decided for one job. */
sealed interface HardFilterResult {
    /** No rule failed: the job goes on to the embedding floor. */
    data object Pass : HardFilterResult

    /** [rule] failed; it is the job's reason, such as `Region: US only`. */
    data class Fail(
        val rule: String,
    ) : HardFilterResult
}

/**
 * The first layer of scoring (design, Matching and scoring): rules with no AI, cheapest first. A job that
 * fails gets score 0 and the rule's name as its reason. A rule never fails on missing information: an
 * unknown work mode, a title with no seniority word, a place this app does not know or a user whose country is
 * unknown passes that rule, because rejecting a good job is worse than scoring a poor one.
 */
class HardFilters(
    private val clock: Clock = Clock.systemUTC(),
) {
    /** The first rule [job] fails for [profile], or [HardFilterResult.Pass]. */
    fun check(
        job: Job,
        profile: Profile,
    ): HardFilterResult {
        // The end of a description is mostly boilerplate ("we do not accept resumes from agencies").
        val text = listOf(job.title, job.company, job.description.take(DEAL_BREAKER_TEXT)).joinToString("\n")
        val countries = Places.countriesOf(profile.preferences.locations, profile.preferences.timeZones)
        val rule =
            dealBreaker("Deal-breaker", profile.dealBreakers.keywords, text)
                ?: dealBreaker("Deal-breaker industry", profile.dealBreakers.industries, text)
                ?: dealBreaker("Deal-breaker company type", profile.dealBreakers.companyTypes, text)
                ?: seniority(job, profile)
                ?: workMode(job, profile)
                ?: officeCountry(job, countries)
                ?: region(job, countries)
        return rule?.let { HardFilterResult.Fail(it.take(MatchResult.MAX_REASON_LENGTH)) } ?: HardFilterResult.Pass
    }

    /** The stored result for a job that failed: score 0, decided by the hard filters, no model. */
    fun resultFor(
        job: Job,
        profile: Profile,
        failure: HardFilterResult.Fail,
    ): MatchResult =
        MatchResult(
            jobId = job.id,
            profileVersion = profile.version,
            score = 0,
            remoteEligible = if (failure.rule.startsWith(REGION)) RemoteEligibility.NO else RemoteEligibility.UNCLEAR,
            matchedSkills = emptyList(),
            missingSkills = emptyList(),
            redFlags = emptyList(),
            reason = failure.rule,
            decidedBy = ScoreStage.HARD_FILTER,
            model = null,
            scoredAt = clock.instant(),
        )

    /** The first term found as a whole word and not negated (`no crypto experience needed` is no match). */
    private fun dealBreaker(
        label: String,
        terms: List<String>,
        text: String,
    ): String? =
        terms
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { term ->
                wholeWord(term).findAll(text).any { match ->
                    val before = text.substring(maxOf(0, match.range.first - NEGATION_WINDOW), match.range.first)
                    !NEGATION.containsMatchIn(before.substringAfterLast('.').substringAfterLast('\n'))
                }
            }?.let { "$label: $it" }

    /** A title two or more steps from the user's level, such as Intern or Director for a 6-year engineer. */
    private fun seniority(
        job: Job,
        profile: Profile,
    ): String? {
        val titleLevel = Seniorities.fromTitle(job.title)
        val userLevel = profile.preferences.seniority ?: Seniorities.fromYears(experienceYears(profile))
        val apart =
            if (titleLevel == null ||
                userLevel == null
            ) {
                0
            } else {
                abs(Seniorities.rank(titleLevel) - Seniorities.rank(userLevel))
            }
        return if (apart >=
            SENIORITY_GAP
        ) {
            "Seniority: ${titleLevel?.label()} role, ${userLevel?.label()} profile"
        } else {
            null
        }
    }

    private fun workMode(
        job: Job,
        profile: Profile,
    ): String? {
        val fails =
            when (profile.preferences.workMode) {
                WorkModePreference.REMOTE_ONLY -> job.workMode == WorkMode.HYBRID || job.workMode == WorkMode.ONSITE
                WorkModePreference.REMOTE_OR_HYBRID -> job.workMode == WorkMode.ONSITE
                WorkModePreference.ANY -> false
            }
        val wanted = profile.preferences.workMode.label()
        return if (fails) "Work mode: ${job.workMode.label()} role, $wanted wanted" else null
    }

    /**
     * An on-site or hybrid job in another country (`Seattle, WA` for a user in India). A city in the user's own
     * country passes, since the user may move within it; an unknown place passes too.
     */
    private fun officeCountry(
        job: Job,
        countries: Set<String>,
    ): String? {
        val inOffice = job.workMode == WorkMode.ONSITE || job.workMode == WorkMode.HYBRID
        val named = if (inOffice && countries.isNotEmpty()) Places.countriesNamedIn(job.location) else emptySet()
        return if (named.isNotEmpty() && named.none { it in countries }) "Location: ${job.location}" else null
    }

    /** A limit such as `US only` or `Must be authorized to work in United States` that leaves the user out. */
    private fun region(
        job: Job,
        countries: Set<String>,
    ): String? {
        if (countries.isEmpty()) return null
        val failed =
            job.regionRestriction
                ?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { limit ->
                    val places =
                        when {
                            limit.endsWith(ONLY, ignoreCase = true) -> limit.dropLast(ONLY.length)
                            limit.startsWith(AUTHORIZED, ignoreCase = true) -> limit.drop(AUTHORIZED.length)
                            else -> null
                        }
                    places != null && Places.includes(places, countries) == false
                }
        return failed?.let { "$REGION: $it" }
    }

    /**
     * Years of experience, counting each month once where roles overlap and the last month of a role too.
     * Null when any role has no start date, since the total would then be too low and reject senior titles.
     */
    private fun experienceYears(profile: Profile): Double? {
        if (profile.experience.isEmpty() || profile.experience.any { it.start == null }) return null
        val now = YearMonth.now(clock)
        val months =
            profile.experience
                .flatMap { role ->
                    val end = minOf(role.end ?: now, now)
                    generateSequence(role.start) { it.plusMonths(1) }.takeWhile { it <= end }.toList()
                }.toSet()
        return months.size / MONTHS_PER_YEAR
    }

    private fun wholeWord(term: String) =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    private companion object {
        const val REGION = "Region"
        const val ONLY = " only"
        const val AUTHORIZED = "Must be authorized to work in "
        const val SENIORITY_GAP = 2
        const val MONTHS_PER_YEAR = 12.0
        const val DEAL_BREAKER_TEXT = 1_500
        const val NEGATION_WINDOW = 40
        val NEGATION = Regex("\\b(no|not|non|never|without|nor|don't|doesn't|isn't)\\b", RegexOption.IGNORE_CASE)
    }
}

private fun WorkMode.label() = name.lowercase(Locale.ROOT).replace("onsite", "on-site")

private fun WorkModePreference.label() =
    when (this) {
        WorkModePreference.REMOTE_ONLY -> "remote only"
        WorkModePreference.REMOTE_OR_HYBRID -> "remote or hybrid"
        WorkModePreference.ANY -> "any"
    }

private fun Seniority.label() = name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
