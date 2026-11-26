package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.JobFilters
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.SearchPreset
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fetches two real We Work Remotely feeds. Runs only with `JOBASSISTANT_LIVE=1`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeWorkRemotelyLiveTest {
    @Test
    fun twoRealFeedsYieldPostings() {
        assumeTrue(System.getenv("JOBASSISTANT_LIVE") == "1")
        val preset = SearchPreset(1, "Any", emptyList(), emptyList(), "", JobFilters(), RunSchedule())

        for (category in listOf("remote-programming-jobs", "remote-design-jobs")) {
            val postings = runBlocking { WeWorkRemotelySource(category, OkHttpClient()).fetch(null, preset) }

            assertTrue("$category: expected postings, got ${postings.size}", postings.size > 5)
            assertTrue(postings.all { it.url.startsWith("https://") && it.postedAt != null })
        }
    }
}
