package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.JobFilters
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.SearchPreset
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One live fetch of Ashby's own public board (task T2.04 Done when). It talks to the real Ashby API, so it
 * runs only when the environment variable `JOBASSISTANT_LIVE` is `1`, never in CI.
 */
class AshbyLiveTest {
    @Test
    fun aPublicBoardCanBeFetchedAndChecked() =
        runBlocking {
            assumeTrue(System.getenv("JOBASSISTANT_LIVE") == "1")
            val source = AshbySource("Ashby", "Ashby", OkHttpClient())
            val preset = SearchPreset(1, "Live", listOf("engineer"), emptyList(), "", JobFilters(), RunSchedule())

            val postings = source.fetch(null, preset)

            assertTrue("Ashby has open roles", postings.isNotEmpty())
            assertTrue(postings.all { it.externalId.isNotBlank() && it.url.startsWith("https://") })
            assertTrue("compensation is included", postings.any { !it.compensation.isNullOrBlank() })
            assertTrue(source.checkBoard() is BoardCheck.Valid)
            assertEquals(
                BoardCheck.NotFound,
                AshbySource("this-board-does-not-exist-xyz", "None", OkHttpClient()).checkBoard(),
            )
        }
}
