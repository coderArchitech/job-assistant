package com.coderarchitech.jobassistant

import com.coderarchitech.jobassistant.core.network.HttpClientFor
import com.coderarchitech.jobassistant.core.network.NetworkGroup
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Task T1.10: Hilt hands out one client per destination group, all sharing one connection pool. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class NetworkWiringTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    @HttpClientFor(NetworkGroup.AI)
    lateinit var ai: OkHttpClient

    @Inject
    @HttpClientFor(NetworkGroup.JOB_SOURCES)
    lateinit var jobSources: OkHttpClient

    @Inject
    @HttpClientFor(NetworkGroup.GOOGLE)
    lateinit var google: OkHttpClient

    @Inject
    @HttpClientFor(NetworkGroup.CONFIG)
    lateinit var config: OkHttpClient

    @Inject
    lateinit var json: Json

    @Before
    fun inject() = hiltRule.inject()

    @Test
    fun everyGroupGetsItsOwnClientOverOneConnectionPool() {
        val clients = listOf(ai, jobSources, google, config)

        assertNotSame(ai, jobSources)
        assertNotSame(google, config)
        clients.forEach { assertSame(ai.connectionPool, it.connectionPool) }
    }

    @Test
    fun theSharedJsonToleratesUnknownFields() {
        assertSame(true, json.configuration.ignoreUnknownKeys)
    }
}
