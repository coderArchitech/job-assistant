package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.SourceCursor
import java.time.Instant

/**
 * One place jobs come from: a job board API, an RSS feed or a company's ATS board. The normalizer and
 * deduplicator turn what it returns into `Job` rows. A source only fetches; it never stores anything.
 */
interface JobSource {
    /** Stable name of this source and the cursor's key, such as `greenhouse:stripe` or `remotive`. */
    val key: String

    /**
     * Postings from this source for [preset]. [since] is where the last committed run stopped, or null
     * on the first run. A source that can ask the provider for only newer postings should do so; one that
     * cannot may return everything, because [SourceSync] drops postings the app already holds.
     *
     * Skip any entry that cannot become a [RawPosting] (a blank title, say) instead of failing the fetch,
     * and use [key] as the job's `source` so postings from different boards never collide.
     *
     * Throw [java.io.IOException] (or any exception) when the fetch fails; the run records the failure
     * and keeps the cursor where it was. Never return a partial list as if it were complete.
     */
    suspend fun fetch(
        since: SourceCursor?,
        preset: SearchPreset,
    ): List<RawPosting>
}

/**
 * A posting as a source returned it, before normalization. Text fields are exactly what the source
 * sent, including HTML, so the normalizer decides how to clean them.
 */
data class RawPosting(
    /** The source's own id for the posting; unique within [JobSource.key]. */
    val externalId: String,
    val title: String,
    val company: String,
    /** The posting's public page. */
    val url: String,
    val location: String? = null,
    /** The description as sent, usually HTML. */
    val description: String? = null,
    val postedAt: Instant? = null,
    /** Where to apply, when it differs from [url]. */
    val applyUrl: String? = null,
    /** Salary as written, for example "12-18 LPA"; parsed later. */
    val compensation: String? = null,
    /** Set by ATS sources; they are preferred as the primary copy when postings are merged. */
    val atsType: AtsType? = null,
    /** The source's own word for where the work happens, such as `remote` or `hybrid`, when it gives one. */
    val workplaceType: String? = null,
) {
    init {
        require(externalId.isNotBlank()) { "A posting needs an external id" }
        require(title.isNotBlank()) { "A posting needs a title" }
        require(company.isNotBlank()) { "A posting needs a company" }
        require(url.isNotBlank()) { "A posting needs a URL" }
    }

    // Descriptions are long and are not worth printing into logs.
    override fun toString(): String = "RawPosting($externalId, $title at $company)"
}
