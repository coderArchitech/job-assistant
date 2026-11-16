package com.coderarchitech.jobassistant.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.YearMonth

/**
 * The user's profile: the only source of facts for scoring and tailored text (design: Tailoring).
 * There is one profile per install; [version] goes up on every edit and keys the score cache.
 */
data class Profile(
    val resumeText: String,
    val skills: List<Skill>,
    val experience: List<Role>,
    val education: List<Education>,
    val preferences: Preferences,
    val dealBreakers: DealBreakers,
    val answersBank: List<StandardAnswer>,
    val writingSamples: List<String>,
    val version: Int,
    val updatedAt: Instant,
) {
    init {
        require(version >= 1) { "Profile version starts at 1" }
    }
}

/** A skill with optional years of experience, for example "Kotlin, 4.5 years". */
@Serializable
data class Skill(
    val name: String,
    val years: Double? = null,
)

/** One past or current job. A null [end] means the role is current. */
@Serializable
data class Role(
    val company: String,
    val title: String,
    @Serializable(with = YearMonthSerializer::class) val start: YearMonth? = null,
    @Serializable(with = YearMonthSerializer::class) val end: YearMonth? = null,
    val summary: String = "",
)

/** One degree or certificate. */
@Serializable
data class Education(
    val institution: String,
    val qualification: String,
    val year: Int? = null,
)

/** Which work modes the user accepts. */
@Serializable
enum class WorkModePreference { REMOTE_ONLY, REMOTE_OR_HYBRID, ANY }

/** What the user is looking for (PRD FR1). Compensation fields are personal data, not secrets. */
@Serializable
data class Preferences(
    val targetRoles: List<String> = emptyList(),
    val seniority: Seniority? = null,
    val minSalary: Salary? = null,
    val currentCtc: Salary? = null,
    val expectedCtc: Salary? = null,
    val noticePeriodDays: Int? = null,
    val workMode: WorkModePreference = WorkModePreference.ANY,
    val locations: List<String> = emptyList(),
    /** IANA time zone IDs the user can work in, such as "Asia/Kolkata". */
    val timeZones: List<String> = emptyList(),
)

/** Jobs matching any of these are filtered out before any AI call. */
@Serializable
data class DealBreakers(
    val industries: List<String> = emptyList(),
    val companyTypes: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
)

/** A saved answer to a common portal question, such as notice period or relocation. */
@Serializable
data class StandardAnswer(
    val question: String,
    val answer: String,
)
