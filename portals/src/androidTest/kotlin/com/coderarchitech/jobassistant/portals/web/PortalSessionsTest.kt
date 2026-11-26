package com.coderarchitech.jobassistant.portals.web

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task T3.08 Done when: wiping one portal leaves another portal's session signed in. Run with profiles where the
 * device's WebView supports them, and always on the shared-jar fallback. Sessions are planted as the cookies a
 * sign-in leaves (a host cookie, a site-wide one, a `__Host-` one, and one on the second sign-in host); no network.
 */
@RunWith(AndroidJUnit4::class)
class PortalSessionsTest {
    private val naukri = PortalSession("naukri", listOf("https://www.naukri.com", "https://login.naukri.com"))
    private val instahyre = PortalSession("instahyre", listOf("https://www.instahyre.com"))
    private val portals = listOf(naukri, instahyre)

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    @After
    fun signOutEverywhere() =
        onMain {
            CookieManager.getInstance().removeAllCookies(null)
            // Profiles are emptied, never deleted: the WebView crashes on a deleted profile's object.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                portals.forEach {
                    ProfileStore
                        .getInstance()
                        .getProfile(
                            it.profileName,
                        )?.cookieManager
                        ?.removeAllCookies(null)
                }
            }
        }

    private fun jar(
        sessions: PortalSessions,
        portal: PortalSession,
    ): CookieManager =
        sessions.cookiesOf(portal) ?: ProfileStore.getInstance().getOrCreateProfile(portal.profileName).cookieManager

    private fun signIn(
        sessions: PortalSessions,
        portal: PortalSession,
        tag: String,
    ) = onMain {
        val jar = jar(sessions, portal)
        val origin = portal.origins.first()
        val site = origin.substringAfter("://www.")
        jar.setCookie(origin, "${tag}_sid=1; Path=/; Secure")
        jar.setCookie(origin, "${tag}_site=1; Path=/; Secure; Domain=.$site")
        jar.setCookie(origin, "__Host-${tag}_auth=1; Path=/; Secure")
        portal.origins.drop(1).forEach { jar.setCookie(it, "${tag}_login=1; Path=/; Secure") }
    }

    private fun cookiesAt(
        sessions: PortalSessions,
        portal: PortalSession,
    ): String = onMain { portal.origins.joinToString(";") { sessions.cookiesOf(portal)?.getCookie(it).orEmpty() } }

    private fun assertWipingOneKeepsTheOther(sessions: PortalSessions) {
        signIn(sessions, naukri, "nauk")
        signIn(sessions, instahyre, "insta")
        assertTrue(cookiesAt(sessions, naukri).contains("__Host-nauk_auth=1"))

        onMain { sessions.wipe(naukri) }

        val naukriAfter = cookiesAt(sessions, naukri)
        assertFalse("Naukri is signed out: $naukriAfter", naukriAfter.contains("nauk_"))
        val instahyreAfter = cookiesAt(sessions, instahyre)
        for (cookie in listOf("insta_sid=1", "insta_site=1", "__Host-insta_auth=1")) {
            assertTrue("Instahyre keeps $cookie: $instahyreAfter", instahyreAfter.contains(cookie))
        }
    }

    @Test
    fun withProfilesWipingOnePortalKeepsAnotherSignedIn() {
        assumeTrue("no multi-profile support", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val sessions = PortalSessions(portals)
        assertTrue(sessions.isolated)

        assertWipingOneKeepsTheOther(sessions)
    }

    @Test
    fun withProfilesAPortalStillOpenIsSignedOutToo() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val sessions = PortalSessions(portals)
        val view =
            onMain {
                WebView(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                ).also { sessions.attach(it, naukri) }
            }
        try {
            signIn(sessions, naukri, "nauk")
            signIn(sessions, instahyre, "insta")

            onMain { sessions.wipe(naukri) }

            assertFalse(cookiesAt(sessions, naukri).contains("nauk_"))
            assertTrue(cookiesAt(sessions, instahyre).contains("insta_sid=1"))
        } finally {
            onMain { view.destroy() }
        }
    }

    @Test
    fun withProfilesOnePortalNeverSeesAnothersCookies() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val sessions = PortalSessions(portals)
        signIn(sessions, naukri, "nauk")

        val seenFromInstahyre = onMain { jar(sessions, instahyre).getCookie(naukri.origins.first()).orEmpty() }

        assertFalse(seenFromInstahyre.contains("nauk_"))
    }

    @Test
    fun onTheSharedJarWipingOnePortalKeepsAnotherSignedIn() =
        assertWipingOneKeepsTheOther(PortalSessions(portals, multiProfile = false))

    @Test
    fun originsMustBeCanonical() {
        for (bad in listOf(
            "http://www.naukri.com",
            "https://Www.Naukri.com",
            "https://naukri.com:443",
            "https://naukri.com/jobs",
        )) {
            assertTrue(bad, runCatching { PortalSession("x", listOf(bad)) }.isFailure)
        }
    }
}
