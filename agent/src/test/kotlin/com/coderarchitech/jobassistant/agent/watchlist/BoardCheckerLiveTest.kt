package com.coderarchitech.jobassistant.agent.watchlist

import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.sources.BoardCheck
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Task T2.09 Done when, against the real boards: a Greenhouse, a Lever and an Ashby company each pass the
 * check, and a made-up board does not. Runs only with `JOBASSISTANT_LIVE=1`.
 */
class BoardCheckerLiveTest {
    @Test
    fun aGreenhouseALeverAndAnAshbyBoardEachPass() =
        runBlocking {
            assumeTrue(System.getenv("JOBASSISTANT_LIVE") == "1")
            val checker = SourceBoardChecker(OkHttpClient())

            for ((ats, board) in listOf(
                AtsType.GREENHOUSE to "stripe",
                AtsType.LEVER to "leverdemo",
                AtsType.ASHBY to "Ashby",
            )) {
                val result = checker.check(ats, board)
                assertTrue("$ats $board: $result", result is BoardCheck.Valid && result.jobCount > 0)
            }
            assertEquals(BoardCheck.NotFound, checker.check(AtsType.GREENHOUSE, "no-such-board-xyz-123"))
        }
}
