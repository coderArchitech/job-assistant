package com.coderarchitech.jobassistant.sources.normalize

import com.coderarchitech.jobassistant.core.model.WorkMode
import java.util.Locale

/**
 * Where the work happens. The source's own field wins when it has one; then the location, which says it
 * plainly; then a mode tagged onto the title (`Designer (Remote)`, `Hybrid - Engineer`); then the description,
 * read only for clear phrases that are not negated (`this is not a remote position`). Otherwise
 * [WorkMode.UNKNOWN]: a city alone does not say whether the role is on-site.
 */
object WorkModes {
    private fun words(vararg phrases: String) = Regex("\\b(${phrases.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

    private val LOCATION_HYBRID = words("hybrid")
    private val LOCATION_REMOTE = words("remote", "work from home", "wfh", "anywhere", "worldwide")
    private val LOCATION_ONSITE = words("on-?site", "in-?office", "in office", "office-based", "office based")

    /** A mode written as a tag at the start or end of a title, not a word inside it (`Remote Sensing`). */
    private val TITLE_TAG =
        Regex(
            "(?:[(\\[]\\s*(remote|hybrid|on-?site|wfh)\\s*[)\\]]|[-–—|,:]\\s*(remote|hybrid|on-?site|wfh)\\s*$|" +
                "^\\s*(remote|hybrid|on-?site)\\s*[-–—|:])",
            RegexOption.IGNORE_CASE,
        )

    private val TEXT_HYBRID =
        words(
            "hybrid (role|position|work|working|model|setup|schedule|arrangement|environment)",
            "this is a hybrid",
            "[1-4] (days?|times?) (a|per) week in (the |our )?office",
        )
    private val TEXT_REMOTE =
        words(
            "fully remote",
            "100% remote",
            "remote-first",
            "remote first",
            "work from anywhere",
            "this (is a|role is|position is) (fully )?remote",
            "remote (role|position|opportunity)",
            "work remotely",
            "working remotely",
        )
    private val TEXT_ONSITE =
        words(
            "on-?site (role|position)",
            "this (is an?|role is) (on-?site|in-office)",
            "work from (the |our )?office",
            "5 days (a|per) week (in|from) (the |our )?office",
            "in-office (role|position)",
            "must be on-?site",
        )
    private val NEGATION =
        Regex("\\b(not|isn't|isnt|is not|no|never|cannot|can't)\\b[^.]{0,12}$", RegexOption.IGNORE_CASE)
    private const val LOOK_BACK = 20

    /**
     * @param hint the source's own word, such as `remote`, `hybrid` or `on-site`, when it gave one.
     */
    fun detect(
        hint: String?,
        location: String?,
        title: String,
        description: String?,
    ): WorkMode = fromHint(hint) ?: fromLocation(location) ?: fromTitle(title) ?: fromDescription(description)

    private fun fromHint(hint: String?): WorkMode? =
        when (hint?.lowercase(Locale.ROOT)?.filter { it.isLetter() }) {
            "remote" -> WorkMode.REMOTE
            "hybrid" -> WorkMode.HYBRID
            "onsite", "office", "inoffice" -> WorkMode.ONSITE
            else -> null
        }

    private fun fromLocation(location: String?): WorkMode? =
        when {
            location.isNullOrBlank() -> null
            LOCATION_HYBRID.containsMatchIn(location) -> WorkMode.HYBRID
            LOCATION_REMOTE.containsMatchIn(location) -> WorkMode.REMOTE
            LOCATION_ONSITE.containsMatchIn(location) -> WorkMode.ONSITE
            else -> null
        }

    private fun fromTitle(title: String): WorkMode? {
        val match = TITLE_TAG.find(title) ?: return null
        return fromHint(match.groupValues.drop(1).firstOrNull { it.isNotEmpty() })
    }

    private fun fromDescription(description: String?): WorkMode =
        when {
            description == null -> WorkMode.UNKNOWN
            TEXT_HYBRID.affirmedIn(description) -> WorkMode.HYBRID
            TEXT_ONSITE.affirmedIn(description) -> WorkMode.ONSITE
            TEXT_REMOTE.affirmedIn(description) -> WorkMode.REMOTE
            else -> WorkMode.UNKNOWN
        }

    /** A match not preceded closely by a negation such as `not` or `no`. */
    private fun Regex.affirmedIn(text: String): Boolean =
        findAll(text).any { match ->
            val before = text.substring(maxOf(0, match.range.first - LOOK_BACK), match.range.first)
            !NEGATION.containsMatchIn(before)
        }
}
