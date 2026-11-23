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
 * One live fetch of Lever's public demo site (task T2.03 Done when). It talks to the real Lever API, so it
 * runs only when the environment variable `JOBASSISTANT_LIVE` is `1`, never in CI.
 */
class LeverLiveTest {
    @Test
    fun aPublicSiteCanBeFetchedAndChecked() =
        runBlocking {
            assumeTrue(System.getenv("JOBASSISTANT_LIVE") == "1")
            val source = LeverSource("leverdemo", "Lever Demo", OkHttpClient())
            val preset = SearchPreset(1, "Live", listOf("engineer"), emptyList(), "", JobFilters(), RunSchedule())

            val postings = source.fetch(null, preset)

            assertTrue("the demo site has postings", postings.isNotEmpty())
            assertTrue(
                postings.all { it.externalId.isNotBlank() && it.url.startsWith("https://") && it.postedAt != null },
            )
            assertTrue(source.checkBoard() is BoardCheck.Valid)
            assertEquals(
                BoardCheck.NotFound,
                LeverSource("this-site-does-not-exist-xyz", "None", OkHttpClient()).checkBoard(),
            )
        }
}
