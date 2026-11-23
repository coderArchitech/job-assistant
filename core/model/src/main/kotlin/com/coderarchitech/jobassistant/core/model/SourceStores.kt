package com.coderarchitech.jobassistant.core.model

/** Where each source's discovery cursor is kept between runs. Implemented over Room in `:core:data`. */
interface SourceCursorStore {
    /** The cursor for [sourceKey], or null before the source's first committed run. */
    suspend fun get(sourceKey: String): SourceCursor?

    /** Stores [cursor], replacing the earlier one for its source. */
    suspend fun save(cursor: SourceCursor)
}

/** Which postings the app already holds, so a run never handles the same posting twice. */
interface KnownPostings {
    /** The subset of [externalIds] already stored as jobs from [sourceKey]. */
    suspend fun knownExternalIds(
        sourceKey: String,
        externalIds: Collection<String>,
    ): Set<String>
}
