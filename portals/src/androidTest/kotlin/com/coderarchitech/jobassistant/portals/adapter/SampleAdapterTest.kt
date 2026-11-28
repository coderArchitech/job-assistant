package com.coderarchitech.jobassistant.portals.adapter

import android.os.Build
import android.webkit.WebStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.coderarchitech.jobassistant.core.model.ApplyMode
import com.coderarchitech.jobassistant.portals.web.AgentWebView
import com.coderarchitech.jobassistant.portals.web.NavigationDecision
import com.coderarchitech.jobassistant.portals.web.NavigationPolicy
import com.coderarchitech.jobassistant.portals.web.PortalSession
import com.coderarchitech.jobassistant.portals.web.bridge.JsBridge
import com.coderarchitech.jobassistant.portals.web.bridge.Pacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/**
 * Task T3.11 Done when: a sample adapter passes the harness. The sample manifest (`assets/adapter/sample.json`,
 * also read by the JVM tests) drives saved portal pages served from the test APK on a made-up origin, through
 * the real WebView, bridge and page driver: sign-in and session reuse, search, details, applying in both modes,
 * a past application and the health check. No network.
 */
@RunWith(AndroidJUnit4::class)
class SampleAdapterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val portal = PortalSession("sample", listOf("https://$HOST"))
    private lateinit var webView: AgentWebView
    private lateinit var bridge: JsBridge
    private lateinit var adapter: ScriptedPortalAdapter
    private var credentialReads = 0
    private var password = "fake-pass"

    @Before
    fun openTheRuntime() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertTrue("the WebView on API 34+ hosts the bridge", JsBridge.isSupported())
        }
        assumeTrue("WebView cannot host the bridge", JsBridge.isSupported())
        val assets =
            WebViewAssetLoader
                .Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instrumentation.context))
                .build()
        val manifest =
            checkNotNull(
                AdapterManifest.from(
                    Json
                        .parseToJsonElement(
                            instrumentation.context.assets
                                .open("adapter/sample.json")
                                .bufferedReader()
                                .use { it.readText() },
                        ).jsonObject,
                ),
            )
        onMain {
            // Each test starts signed out: the sample portal keeps its session in local storage.
            WebStorage.getInstance().deleteAllData()
            webView =
                AgentWebView(
                    instrumentation.targetContext,
                    NavigationPolicy(setOf(HOST), includeSignInAndAts = false),
                    object : AgentWebView.Listener {
                        override fun onNavigationStopped(
                            url: String,
                            decision: NavigationDecision,
                        ) = Unit
                    },
                ) { request -> assets.shouldInterceptRequest(request.url) }
            bridge = JsBridge.attach(webView, portal, Pacer.NONE)
        }
        val credentials =
            CredentialSource {
                credentialReads++
                PortalCredentials("asha", password)
            }
        adapter = ScriptedPortalAdapter("sample", manifest, WebViewPageDriver(webView, bridge), credentials, OUTCOME_MS)
    }

    @After
    fun close() {
        if (::webView.isInitialized) {
            onMain {
                bridge.close()
                webView.destroy()
            }
        }
    }

    @Test
    fun signsInThenReusesTheSession() =
        runBlocking {
            assertEquals(LoginResult.LoggedIn, adapter.login())
            assertEquals(1, credentialReads)

            assertEquals(LoginResult.LoggedIn, adapter.login())
            assertEquals("the second sign-in never read the credentials", 1, credentialReads)
        }

    @Test
    fun aWrongPasswordIsBadCredentials() =
        runBlocking {
            password = "wrong-pass"

            assertEquals(LoginResult.Failed(StepFailure.BAD_CREDENTIALS), adapter.login())
        }

    @Test
    fun searchReadsTheListingsOnThePortal() =
        runBlocking {
            val found = adapter.search("android") as SearchResult.Found

            assertEquals(listOf("101", "102"), found.listings.map { it.externalId })
            assertEquals("https://$HOST/assets/adapter/job.html?id=101", found.listings.first().url)
            assertEquals("Acme", found.listings.first().company)
        }

    @Test
    fun detailsReadThePosting() =
        runBlocking {
            val found = adapter.getDetails(JOB) as DetailsResult.Found

            assertEquals("Android Engineer", found.posting.title)
            assertEquals("Build the Android app in Kotlin and Compose.", found.posting.description)
        }

    @Test
    fun reviewedModeFillsTheFormAndLeavesSubmitToTheUser() =
        runBlocking {
            assertEquals(ApplyResult.ReadyForUser, adapter.apply(request(ApplyMode.REVIEWED)))

            // Nothing was sent: the page records a submission in window.sent.
            assertEquals("true", script("window.sent === undefined"))
        }

    @Test
    fun autoModeSubmitsWhatWasFilled() =
        runBlocking {
            var recorded = false
            val result =
                adapter.apply(
                    request(ApplyMode.AUTO) {
                        recorded = true
                        true
                    },
                )

            assertEquals(ApplyResult.Submitted, result)
            assertTrue(recorded)
            // evaluateJavascript answers with the JSON of the result, here a JSON string holding the object.
            val text = (Json.parseToJsonElement(script("JSON.stringify(window.sent)")) as JsonPrimitive).content
            val sent = Json.parseToJsonElement(text).jsonObject
            assertEquals("\"Asha Rao\"", sent["name"].toString())
            assertEquals("\"30\"", sent["notice"].toString())
            assertEquals("\"cv.pdf:$CV_BYTES\"", sent["cv"].toString())
        }

    @Test
    fun aPastApplicationIsNotFilledAgain() =
        runBlocking {
            val past = ApplyRequest("$JOB&applied", ANSWERS, ApplyMode.AUTO, beforeSubmit = { true })

            assertEquals(ApplyResult.AlreadyApplied, adapter.apply(past))
        }

    @Test
    fun theHealthCheckPasses() =
        runBlocking {
            assertEquals(HealthResult.Healthy, adapter.healthCheck())
        }

    private fun request(
        mode: ApplyMode,
        beforeSubmit: suspend () -> Boolean = { true },
    ) = ApplyRequest(
        JOB,
        ANSWERS,
        mode,
        ApplyRequest.Resume("cv.pdf", "application/pdf", ByteArray(CV_BYTES)),
        beforeSubmit,
    )

    private suspend fun script(source: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { done -> webView.evaluateJavascript(source) { done.resume(it) } }
        }

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private companion object {
        const val HOST = "portal.example"
        const val JOB = "https://$HOST/assets/adapter/job.html?id=101"
        const val CV_BYTES = 2_048
        const val OUTCOME_MS = 5_000L
        val ANSWERS = mapOf("name" to "Asha Rao", "email" to "asha@example.com", "notice" to "30")
    }
}
