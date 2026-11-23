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
 * One live fetch of a public board (task T2.02 Done when). It talks to the real Greenhouse API, so it
 * runs only when the environment variable `JOBASSISTANT_LIVE` is `1`, never in CI.
 */
class GreenhouseLiveTest {
    @Test
    fun aPublicBoardCanBeFetchedAndChecked() =
        runBlocking {
            assumeTrue(System.getenv("JOBASSISTANT_LIVE") == "1")
            val source = GreenhouseSource("stripe", "Stripe", OkHttpClient())
            val preset = SearchPreset(1, "Live", listOf("engineer"), emptyList(), "", JobFilters(), RunSchedule())

            val postings = source.fetch(null, preset)

            assertTrue("a big public board has postings", postings.size > 10)
            assertTrue(postings.all { it.externalId.isNotBlank() && it.url.startsWith("https://") })
            assertTrue(postings.any { !it.description.isNullOrBlank() && it.description!!.contains("<") })
            assertEquals(
                BoardCheck.NotFound,
                GreenhouseSource("this-board-does-not-exist-xyz", "None", OkHttpClient()).checkBoard(),
            )
            assertTrue(source.checkBoard() is BoardCheck.Valid)
        }
}
