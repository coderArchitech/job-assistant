package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.JobFilters
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.SearchPreset
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Fetches the real Remote OK feed. Runs only with `JOBASSISTANT_LIVE=1`. */
class RemoteOkLiveTest {
    @Test
    fun theRealFeedYieldsPostings() {
        assumeTrue(System.getenv("JOBASSISTANT_LIVE") == "1")
        val preset = SearchPreset(1, "Any", emptyList(), emptyList(), "", JobFilters(), RunSchedule())

        val postings = runBlocking { RemoteOkSource(OkHttpClient()).fetch(null, preset) }

        assertTrue("expected postings, got ${postings.size}", postings.size > 10)
        assertTrue(postings.all { it.url.startsWith("https://") })
    }
}
