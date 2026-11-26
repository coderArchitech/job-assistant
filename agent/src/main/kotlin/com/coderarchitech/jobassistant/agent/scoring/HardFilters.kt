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
 * unknown work mode, a title with no seniority word or a user whose country is unknown passes that rule.
 */
class HardFilters(
    private val clock: Clock = Clock.systemUTC(),
) {
    /** The first rule [job] fails for [profile], or [HardFilterResult.Pass]. */
    fun check(
        job: Job,
        profile: Profile,
    ): HardFilterResult {
        val text = listOf(job.title, job.company, job.description).joinToString("\n")
        val rule =
            dealBreaker("Deal-breaker", profile.dealBreakers.keywords, text)
                ?: dealBreaker("Deal-breaker industry", profile.dealBreakers.industries, text)
                ?: dealBreaker("Deal-breaker company type", profile.dealBreakers.companyTypes, text)
                ?: seniority(job, profile)
                ?: workMode(job, profile)
                ?: officeLocation(job, profile)
                ?: region(job, profile)
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

    private fun dealBreaker(
        label: String,
        terms: List<String>,
        text: String,
    ): String? =
        terms
            .map { it.trim() }
            .firstOrNull { term -> term.isNotEmpty() && wholeWord(term).containsMatchIn(text) }
            ?.let { "$label: $it" }

    /** A title two or more steps from the user's level, such as Intern or Director for a 6-year engineer. */
    private fun seniority(
        job: Job,
        profile: Profile,
    ): String? {
        val titleLevel = Seniorities.fromTitle(job.title)
        val userLevel = profile.preferences.seniority ?: Seniorities.fromYears(experienceYears(profile))
        if (titleLevel == null || userLevel == null) return null
        val apart = kotlin.math.abs(Seniorities.rank(titleLevel) - Seniorities.rank(userLevel))
        return if (apart >=
            SENIORITY_GAP
        ) {
            "Seniority: ${titleLevel.label()} role, ${userLevel.label()} profile"
        } else {
            null
        }
    }

    private fun workMode(
        job: Job,
        profile: Profile,
    ): String? =
        when (profile.preferences.workMode) {
            WorkModePreference.REMOTE_ONLY ->
                "Work mode: ${job.workMode.label()}, remote only wanted".takeIf {
                    job.workMode in
                        setOf(WorkMode.HYBRID, WorkMode.ONSITE)
                }
            WorkModePreference.REMOTE_OR_HYBRID ->
                "Work mode: on-site, remote or hybrid wanted".takeIf { job.workMode == WorkMode.ONSITE }
            WorkModePreference.ANY -> null
        }

    /** An on-site or hybrid job must be somewhere the user named, when they named anywhere. */
    private fun officeLocation(
        job: Job,
        profile: Profile,
    ): String? {
        val wanted =
            profile.preferences.locations.map { it.substringBefore(',').trim() }.filter {
                it.isNotEmpty() &&
                    !it.equals("remote", true)
            }
        val inOffice = job.workMode == WorkMode.ONSITE || job.workMode == WorkMode.HYBRID
        if (!inOffice || wanted.isEmpty() || job.location.isBlank()) return null
        val matches =
            wanted.any { place ->
                CITY_ALIASES[place.lowercase()]
                    .orEmpty()
                    .plus(
                        place,
                    ).any { wholeWord(it).containsMatchIn(job.location) }
            }
        return if (matches) null else "Location: ${job.location}"
    }

    /** A limit such as `US only` or `Must be authorized to work in United States` that leaves the user out. */
    private fun region(
        job: Job,
        profile: Profile,
    ): String? {
        val limits =
            job.regionRestriction
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        val country = Places.countryOf(profile.preferences.locations, profile.preferences.timeZones)
        val failed =
            limits.firstOrNull { limit ->
                val places =
                    when {
                        limit.endsWith(" only", ignoreCase = true) -> limit.dropLast(" only".length)
                        limit.startsWith(AUTHORIZED, ignoreCase = true) -> limit.drop(AUTHORIZED.length)
                        else -> null
                    }
                country != null && places != null && !Places.includes(places, country)
            }
        return failed?.let { "$REGION: $it" }
    }

    /**
     * Years of experience from the roles with a start date, counting months once where roles overlap, or
     * null when no role has one.
     */
    private fun experienceYears(profile: Profile): Double? {
        val now = YearMonth.now(clock)
        val months =
            profile.experience
                .mapNotNull { role -> role.start?.let { start -> start to minOf(role.end ?: now, now) } }
                .flatMap { (start, end) ->
                    generateSequence(start) { it.plusMonths(1) }.takeWhile { it < end }.toList()
                }.toSet()
        return if (profile.experience.none { it.start != null }) null else months.size / MONTHS_PER_YEAR
    }

    private fun wholeWord(term: String) =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    private companion object {
        const val REGION = "Region"
        const val AUTHORIZED = "Must be authorized to work in "
        const val SENIORITY_GAP = 2
        const val MONTHS_PER_YEAR = 12.0

        /** Indian cities written two ways on job boards. */
        val CITY_ALIASES =
            mapOf(
                "bengaluru" to listOf("bangalore"),
                "bangalore" to listOf("bengaluru"),
                "gurugram" to listOf("gurgaon"),
                "gurgaon" to listOf("gurugram"),
                "mumbai" to listOf("bombay"),
                "chennai" to listOf("madras"),
                "kolkata" to listOf("calcutta"),
            )
    }
}

private fun WorkMode.label() = name.lowercase(java.util.Locale.ROOT).replace("onsite", "on-site")

private fun Seniority.label() = name.lowercase(java.util.Locale.ROOT).replaceFirstChar { it.uppercase() }
