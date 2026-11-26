package com.coderarchitech.jobassistant.portals.web

import android.webkit.WebSettings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Task T3.06 Done when: a test page that tries to navigate off the allowlist is stopped. The page is served
 * from the test APK's assets on `https://appassets.androidplatform.net`, the only allowed host, so nothing
 * here touches the network: a navigation that were not stopped would fail to load instead of being reported.
 */
@RunWith(AndroidJUnit4::class)
class AgentWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val stopped = LinkedBlockingQueue<Pair<String, NavigationDecision>>()
    private val finished = LinkedBlockingQueue<String>()
    private lateinit var webView: AgentWebView

    private val listener =
        object : AgentWebView.Listener {
            override fun onNavigationStopped(
                url: String,
                decision: NavigationDecision,
            ) {
                stopped += url to decision
            }

            override fun onPageFinished(url: String) {
                finished += url
            }
        }

    @Before
    fun openTheStartPage() {
        val assets =
            WebViewAssetLoader
                .Builder()
                .addPathHandler(
                    "/assets/",
                    WebViewAssetLoader.AssetsPathHandler(instrumentation.context),
                ).build()
        onMain {
            webView =
                AgentWebView(
                    instrumentation.targetContext,
                    NavigationPolicy(setOf(ASSET_HOST), includeSignInAndAts = false),
                    listener,
                ) { request -> assets.shouldInterceptRequest(request.url) }
            webView.loadUrl(START)
        }
        assertEquals(START, finished.poll(TIMEOUT_S, TimeUnit.SECONDS))
    }

    @After
    fun close() = onMain { webView.destroy() }

    @Test
    fun aScriptNavigatingOffTheAllowlistIsStopped() {
        run("go('https://evil.example/steal')")

        assertEquals("https://evil.example/steal" to NavigationDecision.OPEN_OUTSIDE, nextStop())
        assertStillOnTheStartPage()
    }

    @Test
    fun aClickedLinkOffTheAllowlistIsStopped() {
        run("document.getElementById('off').click()")

        assertEquals(NavigationDecision.OPEN_OUTSIDE, nextStop().second)
        assertStillOnTheStartPage()
    }

    @Test
    fun linkedInIsBlocked() {
        run("document.getElementById('linkedin').click()")

        assertEquals("https://www.linkedin.com/in/someone" to NavigationDecision.BLOCK, nextStop())
        assertStillOnTheStartPage()
    }

    @Test
    fun aPopUpAndAFormPostOffTheAllowlistAreStopped() {
        run("popUp('https://evil.example/popup')")
        assertEquals("https://evil.example/popup", nextStop().first)

        run("submitTo('https://evil.example/collect')")
        assertEquals("https://evil.example/collect", nextStop().first)
        assertStillOnTheStartPage()
    }

    @Test
    fun anAllowedPageStillLoads() {
        run("document.getElementById('next').click()")

        assertEquals("https://$ASSET_HOST/assets/runtime/next.html", finished.poll(TIMEOUT_S, TimeUnit.SECONDS))
        assertTrue("nothing was stopped", stopped.isEmpty())
    }

    @Test
    fun theHostCannotLoadAFileOrPlantAnInterface() {
        onMain { webView.loadUrl("file:///system/etc/hosts") }
        assertEquals(NavigationDecision.BLOCK, nextStop().second)

        onMain {
            try {
                webView.addJavascriptInterface(Any(), "bridge")
                fail("addJavascriptInterface must throw")
            } catch (expected: UnsupportedOperationException) {
                // As the design requires.
            }
        }
    }

    @Test
    fun theSettingsAreHardened() =
        onMain {
            val settings = webView.settings
            assertFalse(settings.allowFileAccess)
            assertFalse(settings.allowContentAccess)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings.mixedContentMode)
            assertTrue(settings.safeBrowsingEnabled)
            assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
        }

    private fun run(script: String) = onMain { webView.evaluateJavascript(script, null) }

    /** The first stop reported; later check points may report the same navigation again. */
    private fun nextStop(): Pair<String, NavigationDecision> {
        val stop = stopped.poll(TIMEOUT_S, TimeUnit.SECONDS)
        assertNotNull("a navigation was stopped", stop)
        Thread.sleep(SETTLE_MS)
        stopped.clear()
        return stop!!
    }

    private fun assertStillOnTheStartPage() {
        // Give a navigation that slipped through time to commit before checking.
        Thread.sleep(SETTLE_MS)
        var url: String? = null
        onMain { url = webView.url }
        assertEquals(START, url)
        assertTrue("no other page finished loading", finished.isEmpty())
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val START = "https://$ASSET_HOST/assets/runtime/start.html"
        const val TIMEOUT_S = 10L
        const val SETTLE_MS = 1_000L
    }
}
