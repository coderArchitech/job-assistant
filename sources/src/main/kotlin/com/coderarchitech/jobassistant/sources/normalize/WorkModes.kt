package com.coderarchitech.jobassistant.sources.normalize

import com.coderarchitech.jobassistant.core.model.WorkMode

/**
 * Where the work happens. The source's own field wins when it has one; then the location and title, which
 * are short and say it plainly; then the description, read only for clear phrases. Otherwise [WorkMode.UNKNOWN]:
 * a city alone does not say whether the role is on-site.
 */
object WorkModes {
    private fun words(vararg phrases: String) = Regex("\\b(${phrases.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

    private val SHORT_HYBRID = words("hybrid")
    private val SHORT_REMOTE = words("remote", "work from home", "wfh", "anywhere", "worldwide", "distributed")
    private val SHORT_ONSITE = words("on-?site", "in-?office", "in office", "office-based", "office based")

    private val TEXT_HYBRID =
        words(
            "hybrid (role|position|work|working|model|setup|schedule|arrangement|environment)",
            "this is a hybrid",
            "\\d (days?|times?) (a|per) week in (the |our )?office",
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

    /**
     * @param hint the source's own word, such as `remote`, `hybrid` or `on-site`, when it gave one.
     */
    fun detect(
        hint: String?,
        location: String?,
        title: String,
        description: String?,
    ): WorkMode {
        fromHint(hint)?.let { return it }
        val short = listOfNotNull(location, title).joinToString(" | ")
        return when {
            SHORT_HYBRID.containsMatchIn(short) -> WorkMode.HYBRID
            SHORT_REMOTE.containsMatchIn(short) -> WorkMode.REMOTE
            SHORT_ONSITE.containsMatchIn(short) -> WorkMode.ONSITE
            description == null -> WorkMode.UNKNOWN
            TEXT_HYBRID.containsMatchIn(description) -> WorkMode.HYBRID
            TEXT_REMOTE.containsMatchIn(description) -> WorkMode.REMOTE
            TEXT_ONSITE.containsMatchIn(description) -> WorkMode.ONSITE
            else -> WorkMode.UNKNOWN
        }
    }

    private fun fromHint(hint: String?): WorkMode? =
        when (
            hint
                ?.lowercase()
                ?.replace("-", "")
                ?.replace("_", "")
                ?.replace(" ", "")
        ) {
            "remote" -> WorkMode.REMOTE
            "hybrid" -> WorkMode.HYBRID
            "onsite", "office", "inoffice" -> WorkMode.ONSITE
            else -> null
        }
}
