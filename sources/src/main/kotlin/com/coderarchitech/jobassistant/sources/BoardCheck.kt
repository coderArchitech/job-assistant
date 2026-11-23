package com.coderarchitech.jobassistant.sources

/** Result of asking an ATS whether a watchlist board exists (the "check board" button, PRD FR3). */
sealed interface BoardCheck {
    /**
     * The board exists and lists [jobCount] open postings. A source that pages (Lever) counts only its first
     * page, so a large board can have more.
     */
    data class Valid(
        val jobCount: Int,
    ) : BoardCheck

    /** The ATS has no board with that slug. */
    data object NotFound : BoardCheck

    /** The board could not be checked (network failure, rate limit or an unreadable reply); try again. */
    data class Failed(
        val message: String,
    ) : BoardCheck
}
