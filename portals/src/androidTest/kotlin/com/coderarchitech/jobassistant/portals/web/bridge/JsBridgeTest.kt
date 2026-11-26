package com.coderarchitech.jobassistant.portals.web.bridge

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.coderarchitech.jobassistant.portals.web.AgentWebView
import com.coderarchitech.jobassistant.portals.web.NavigationDecision
import com.coderarchitech.jobassistant.portals.web.NavigationPolicy
import com.coderarchitech.jobassistant.portals.web.PortalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.coroutines.resume

/**
 * Task T3.07 Done when: a page from another origin cannot send bridge messages. Pages come from the test APK's
 * assets on two made-up origins, the portal's and a hostile one, so nothing touches the network. Skipped only
 * below API 34 where the WebView cannot host the bridge.
 */
@RunWith(AndroidJUnit4::class)
class JsBridgeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val portal = PortalSession("portal", listOf("https://$PORTAL_HOST"))
    private val rejected: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var webView: AgentWebView
    private lateinit var bridge: JsBridge

    @Before
    fun openTheRuntime() {
        // The API 34 and 36 emulators' WebViews have both features, so the tests must run there, never skip;
        // only an older WebView (the API 29 image's) may skip them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertTrue("the WebView on API 34+ hosts the bridge", JsBridge.isSupported())
        }
        assumeTrue("WebView cannot host the bridge", JsBridge.isSupported())
        val loaders = listOf(PORTAL_HOST, HOSTILE_HOST).associateWith(::assetsOn)
        onMain {
            webView =
                AgentWebView(
                    instrumentation.targetContext,
                    NavigationPolicy(setOf(PORTAL_HOST, HOSTILE_HOST), includeSignInAndAts = false),
                    object : AgentWebView.Listener {
                        override fun onNavigationStopped(
                            url: String,
                            decision: NavigationDecision,
                        ) = Unit
                    },
                ) { request -> loaders[request.url.host]?.shouldInterceptRequest(request.url) }
            bridge = JsBridge.attach(webView, portal, Pacer.NONE) { rejected += it }
        }
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
    fun commandsDriveThePortalsPage() {
        openForm()
        onMain {
            assertEquals(
                "Apply for Android Engineer",
                done(bridge.send(BridgeCommand.Query("#title"))).jsonObject.text("text"),
            )
            done(bridge.send(BridgeCommand.Type("#name", "Asha Rao")))
            done(bridge.send(BridgeCommand.Type("#password", "fake-pass", secret = true)))
            done(bridge.send(BridgeCommand.Select("#city", "Pune")))
            done(bridge.send(BridgeCommand.Click("#submit")))
            assertEquals("Asha Rao|pune|9", done(bridge.send(BridgeCommand.Query("#out"))).jsonObject.text("text"))

            done(bridge.send(BridgeCommand.Upload("#cv", "cv.pdf", "application/pdf", ByteArray(CV_BYTES))))
            assertEquals(
                "cv.pdf:$CV_BYTES:application/pdf",
                done(bridge.send(BridgeCommand.Query("#file-out"))).jsonObject.text("text"),
            )

            done(bridge.send(BridgeCommand.WaitFor("#late", WAIT_MS)))
        }
    }

    @Test
    fun thePageReportsWhatItCannotDo() {
        openForm()
        onMain {
            assertEquals(BridgeError.NOT_FOUND, failed(bridge.send(BridgeCommand.Click("#nothing"))))
            assertEquals(BridgeError.NOT_VISIBLE, failed(bridge.send(BridgeCommand.Click("#hidden"))))
            assertEquals(BridgeError.NO_OPTION, failed(bridge.send(BridgeCommand.Select("#city", "Mars"))))
            val notAFileInput = BridgeCommand.Upload("#name", "a", "b", ByteArray(1))
            assertEquals(BridgeError.NOT_FILE_INPUT, failed(bridge.send(notAFileInput)))
            assertEquals(BridgeError.TIMEOUT, failed(bridge.send(BridgeCommand.WaitFor("#never", SHORT_WAIT_MS))))
            assertEquals(BridgeError.BAD_COMMAND, failed(bridge.send(BridgeCommand.Query("##"))))
        }
    }

    @Test
    fun theSnapshotHidesPasswords() {
        openForm()
        onMain {
            done(bridge.send(BridgeCommand.Type("#password", "fake-pass")))
            val snapshot = done(bridge.send(BridgeCommand.Snapshot)).jsonObject
            val elements = snapshot.getValue("elements").jsonArray.map { it.jsonObject }
            val password = elements.single { it.text("selector") == "#password" }
            assertEquals("{{password}}", password.text("value"))
            assertFalse(snapshot.toString().contains("fake-pass"))
            assertTrue(elements.any { it.text("selector") == "#submit" && it.text("text") == "Send" })
            assertTrue("hidden elements are left out", elements.none { it.text("selector") == "#hidden" })
        }
    }

    @Test
    fun secretsStayHiddenEvenWhenThePageShowsThem() {
        openForm()
        onMain {
            done(bridge.send(BridgeCommand.Type("#password", "fake-pass", secret = true)))
            done(bridge.send(BridgeCommand.Click("#show")))
            assertEquals("{{password}}", done(bridge.send(BridgeCommand.Query("#password"))).jsonObject.text("value"))

            // A one-time-code field is a secret by its markup, whoever filled it.
            done(bridge.send(BridgeCommand.Type("#otp", "123456")))
            val snapshot = done(bridge.send(BridgeCommand.Snapshot)).toString()
            assertFalse(snapshot, snapshot.contains("fake-pass") || snapshot.contains("123456"))
        }
    }

    @Test
    fun aNewDocumentGetsANewerGenerationAndEndsWaitingCommands() {
        openForm()
        onMain {
            val before = bridge.generation
            coroutineScope {
                val waiting = async { bridge.send(BridgeCommand.WaitFor("#never", WAIT_MS)) }
                yield() // Let the wait reach the page before it goes away.
                delay(POLL_MS)
                webView.reload()
                assertEquals(BridgeResult.PageChanged, waiting.await())
            }
            assertTrue("the reloaded page is ready", bridge.awaitPage(newerThan = before, timeoutMs = LOAD_MS))
            assertTrue(bridge.generation > before)
        }
    }

    @Test
    fun aFrameFromAnotherOriginCannotSendBridgeMessages() {
        openForm()
        val report = hostileReport()

        // The hostile frame finds no channel, in itself or in the portal page, and cannot read the page.
        assertEquals(JsonArray(emptyList()), report.getValue("own"))
        assertEquals(JsonArray(emptyList()), report.getValue("parent"))
        assertEquals(JsonPrimitive(false), report.getValue("parentReadable"))
        assertTrue("no message reached the app from elsewhere: $rejected", rejected.isEmpty())
        // The portal's own page is still the one the bridge talks to.
        assertEquals(FORM, bridge.pageUrl)
    }

    @Test
    fun aPageFromAnotherOriginGetsNoBridge() {
        openForm()
        onMain { webView.loadUrl(HOSTILE) }
        // Once the hostile page has run, the portal page is gone and nothing took its place.
        val report = hostileReport()

        assertEquals(JsonArray(emptyList()), report.getValue("own"))
        onMain {
            assertEquals(null, bridge.pageUrl)
            assertFalse("no bridge on the hostile page", bridge.awaitPage(timeoutMs = SHORT_WAIT_MS))
            assertEquals(BridgeResult.NoPage, bridge.send(BridgeCommand.Query("p")))
        }
        assertTrue("no message reached the app from elsewhere: $rejected", rejected.isEmpty())
    }

    private fun openForm() =
        onMain {
            webView.loadUrl(FORM)
            assertTrue("the portal page's bridge is ready", bridge.awaitPage(timeoutMs = LOAD_MS))
            assertEquals(FORM, bridge.pageUrl)
        }

    /** What the hostile page found, once it has run. */
    private fun hostileReport(): JsonObject =
        onMain {
            var report: JsonObject? = null
            repeat(POLLS) {
                if (report == null) {
                    val value = Json.parseToJsonElement(script("JSON.stringify(window.hostileReport || null)"))
                    report = (value as? JsonPrimitive)?.content?.let { Json.parseToJsonElement(it) as? JsonObject }
                    if (report == null) delay(POLL_MS)
                }
            }
            checkNotNull(report) { "the hostile page reported" }
        }

    private suspend fun script(source: String): String =
        suspendCancellableCoroutine { done -> webView.evaluateJavascript(source) { done.resume(it) } }

    private fun done(result: BridgeResult) =
        when (result) {
            is BridgeResult.Done -> result.value
            else -> throw AssertionError("expected Done, got $result")
        }

    private fun failed(result: BridgeResult) =
        when (result) {
            is BridgeResult.Failed -> result.error
            else -> throw AssertionError("expected Failed, got $result")
        }

    private fun JsonObject.text(key: String) = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun assetsOn(host: String) =
        WebViewAssetLoader
            .Builder()
            .setDomain(host)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instrumentation.context))
            .build()

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private companion object {
        const val PORTAL_HOST = "portal.example"
        const val HOSTILE_HOST = "hostile.example"
        const val FORM = "https://$PORTAL_HOST/assets/bridge/form.html"
        const val HOSTILE = "https://$HOSTILE_HOST/assets/bridge/hostile.html"
        const val CV_BYTES = 1_234
        const val WAIT_MS = 5_000L
        const val SHORT_WAIT_MS = 1_500L
        const val LOAD_MS = 15_000L
        const val POLLS = 50
        const val POLL_MS = 200L
    }
}
