package com.coderarchitech.jobassistant.sources.normalize

import com.coderarchitech.jobassistant.core.model.WorkMode

/**
 * Limits on where a candidate may live or work, such as `US only`, found by rules (design, Normalization) and
 * passed to the hard filters and scoring. For a remote job the location names where candidates must be, so
 * `USA, Canada` there becomes `USA, Canada only`; for an on-site job it is just the office. The description
 * adds limits stated in plain words, such as needing to be authorized to work in a country.
 */
object RegionLimits {
    /**
     * A place name: capitalised words (`United States`, `Bosnia and Herzegovina`), or a dotted abbreviation
     * such as `U.S.`, which ends the name. `and` or `of` is taken only when a capitalised word follows, and a
     * full stop always ends it, so `the United States and able to travel` or `the U.S. This role` stop at the
     * place.
     */
    private const val NAME = "(?:[A-Z][A-Za-z]*)"
    private const val ABBREVIATION = "(?:[A-Z](?:\\.[A-Z])+\\.)"
    private const val PLACE = "((?:the )?(?:$ABBREVIATION|$NAME(?:(?:[ -](?:of|and))?[ -]$NAME)*))"

    private val WORLDWIDE =
        Regex(
            "^(remote|worldwide|anywhere|anywhere in the world|global|globally|international|earth|" +
                "remote - worldwide|remote \\(worldwide\\)|remote, worldwide)$",
            RegexOption.IGNORE_CASE,
        )
    private val ONLY = Regex("^(.+?)\\s+only$", RegexOption.IGNORE_CASE)
    private val REMOTE_PREFIX =
        Regex(
            "^(?:remote|fully remote)\\s*(?:[-–—:,(]|\\bin\\b|\\bwithin\\b|\\bfrom\\b)\\s*(.+?)\\)?$",
            RegexOption.IGNORE_CASE,
        )
    private val REMOTE_SUFFIX = Regex("^(.+?)[\\s,–—-]+\\(?remote\\)?$", RegexOption.IGNORE_CASE)

    private val AUTHORIZED =
        Regex("(?i:must be (?:legally )?(?:authori[sz]ed|eligible|permitted) to work in) $PLACE")
    private val BASED_IN =
        Regex(
            "(?i:(?:must|need to|required to) (?:be based|reside|live|be located|be residing) in|" +
                "(?:only )?(?:open to|accepting|considering) (?:candidates|applicants|residents|people)" +
                " (?:who are )?(?:based |located |residing |living )?in) $PLACE",
        )
    private val NO_SPONSORSHIP =
        Regex(
            "\\b(?:no|not able to|unable to|cannot|can't|can not|do not|does not|don't|will not|won't)" +
                " (?:offer |provide |support )?(?:visa |immigration |work visa )?sponsor",
            RegexOption.IGNORE_CASE,
        )

    /** The limits joined with `; `, or null when the posting states none. */
    fun find(
        workMode: WorkMode,
        location: String?,
        description: String?,
    ): String? {
        val limits = mutableListOf<String>()
        fromLocation(workMode, location)?.let(limits::add)
        if (description != null) {
            AUTHORIZED.find(description)?.let { limits += "Must be authorized to work in ${place(it)}" }
            BASED_IN.find(description)?.let { limits += "${place(it)} only" }
            if (NO_SPONSORSHIP.containsMatchIn(description)) limits += "No visa sponsorship"
        }
        return limits.distinctBy { it.lowercase() }.joinToString("; ").ifEmpty { null }
    }

    /** `US Only` and `Remote - US` give `US only`; a remote job's `USA, Canada` gives `USA, Canada only`. */
    private fun fromLocation(
        workMode: WorkMode,
        location: String?,
    ): String? {
        // A location may list several parts (`Anywhere in the World; United States of America`).
        val parts =
            location
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !WORLDWIDE.matches(it) }
                .orEmpty()
        val text = parts.joinToString(", ").ifEmpty { return null }
        val only = ONLY.matchEntire(text)
        val prefixed = REMOTE_PREFIX.matchEntire(text)
        val suffixed = REMOTE_SUFFIX.matchEntire(text)
        val place =
            when {
                only != null -> only.groupValues[1].trim()
                prefixed != null -> prefixed.groupValues[1].trim()
                suffixed != null -> suffixed.groupValues[1].trim()
                workMode == WorkMode.REMOTE -> text
                else -> null
            }
        // `Remote Only`, `Worldwide (Remote)` and `Anywhere - Remote` are no limit at all.
        return place?.takeUnless { WORLDWIDE.matches(it) }?.let { "$it only" }
    }

    private fun place(match: MatchResult): String = match.groupValues[1].removePrefix("the ").trim()
}
