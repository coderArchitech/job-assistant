package com.coderarchitech.jobassistant.sources.normalize

import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.Salary
import com.coderarchitech.jobassistant.core.model.WorkMode
import com.coderarchitech.jobassistant.sources.RawPosting
import java.time.Instant

/**
 * A posting after normalization, ready for the deduplicator (which adds the canonical URL and `dedupeHash`)
 * and then storage as a `Job`. Text is plain, trimmed and never blank where a value is required.
 */
data class NormalizedPosting(
    /** The source's key, stored as `Job.source`. */
    val source: String,
    val externalId: String,
    val company: String,
    /** [CompanyNames.key] of [company], for matching across sources. */
    val companyKey: String,
    val title: String,
    /** The location as written, or empty when the source gave none. */
    val location: String,
    val workMode: WorkMode,
    val regionRestriction: String?,
    val salary: Salary?,
    /** Plain text, at most [HtmlText.PROMPT_CAP] characters. */
    val description: String,
    val url: String,
    val applyUrl: String?,
    val postedAt: Instant?,
    val atsType: AtsType?,
) {
    // Descriptions are long and are not worth printing into logs.
    override fun toString(): String = "NormalizedPosting($source/$externalId, $title at $company)"
}

/** Turns what a source sent into a [NormalizedPosting] (design, Normalization). Pure and thread-safe. */
object JobNormalizer {
    private val SPACES = Regex("\\s+")

    fun normalize(
        raw: RawPosting,
        sourceKey: String,
    ): NormalizedPosting {
        val description = raw.description?.let(HtmlText::toPlainText).orEmpty()
        val title = raw.title.replace(SPACES, " ").trim()
        val location =
            raw.location
                ?.replace(SPACES, " ")
                ?.trim()
                .orEmpty()
        val workMode = WorkModes.detect(raw.workplaceType, location, title, description)
        val company = CompanyNames.clean(raw.company)
        return NormalizedPosting(
            source = sourceKey,
            externalId = raw.externalId,
            company = company,
            companyKey = CompanyNames.key(company),
            title = title,
            location = location,
            workMode = workMode,
            regionRestriction = RegionLimits.find(workMode, location, description),
            salary = SalaryParser.parse(raw.compensation) ?: SalaryParser.findIn(description),
            description = HtmlText.cap(description),
            url = raw.url.trim(),
            applyUrl = raw.applyUrl?.trim()?.takeIf { it.isNotEmpty() },
            postedAt = raw.postedAt,
            atsType = raw.atsType,
        )
    }
}
