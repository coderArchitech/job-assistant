package com.coderarchitech.jobassistant.agent.watchlist

import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.network.HttpClientFor
import com.coderarchitech.jobassistant.core.network.NetworkGroup
import com.coderarchitech.jobassistant.sources.AshbySource
import com.coderarchitech.jobassistant.sources.BoardCheck
import com.coderarchitech.jobassistant.sources.GreenhouseSource
import com.coderarchitech.jobassistant.sources.LeverSource
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * The watchlist's "check board" button (task T2.09): asks the company's ATS whether the board exists and how
 * many postings it has, through the job-sources client (HTTPS, host rules, two requests per host at a time).
 */
interface BoardChecker {
    /** [BoardCheck.Valid] with the posting count, [BoardCheck.NotFound], or [BoardCheck.Failed]; never throws. */
    suspend fun check(
        ats: AtsType,
        board: String,
    ): BoardCheck

    companion object {
        // The same rules the clients enforce: Ashby board names may hold dots, the others may not.
        private val PLAIN = Regex("[A-Za-z0-9_-]{1,100}")
        private val DOTTED = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}")

        /** True when [board] is a name [ats] accepts, so a check can be sent at all. */
        fun isWellFormed(
            ats: AtsType,
            board: String,
        ): Boolean = (if (ats == AtsType.ASHBY) DOTTED else PLAIN).matches(board.trim())

        /** The board's public page, which the form shows so the user can see what they typed points at. */
        fun publicPage(
            ats: AtsType,
            board: String,
        ): String =
            when (ats) {
                AtsType.GREENHOUSE -> "https://boards.greenhouse.io/${board.trim()}"
                AtsType.LEVER -> "https://jobs.lever.co/${board.trim()}"
                AtsType.ASHBY -> "https://jobs.ashbyhq.com/${board.trim()}"
            }
    }
}

/** The [BoardChecker] over the Greenhouse, Lever and Ashby clients. */
class SourceBoardChecker
    @Inject
    constructor(
        @HttpClientFor(NetworkGroup.JOB_SOURCES) private val client: OkHttpClient,
    ) : BoardChecker {
        override suspend fun check(
            ats: AtsType,
            board: String,
        ): BoardCheck {
            val slug = board.trim()
            if (!BoardChecker.isWellFormed(ats, slug)) return BoardCheck.Failed(NOT_A_BOARD)
            // The name is only used in the source's key and requests; the check does not depend on it.
            return when (ats) {
                AtsType.GREENHOUSE -> GreenhouseSource(slug, CHECK, client).checkBoard()
                AtsType.LEVER -> LeverSource(slug, CHECK, client).checkBoard()
                AtsType.ASHBY -> AshbySource(slug, CHECK, client).checkBoard()
            }
        }

        private companion object {
            const val CHECK = "Board check"
            const val NOT_A_BOARD = "Not a board name this system accepts"
        }
    }
