package com.coderarchitech.jobassistant.portals.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task T3.08 Done when: wiping one portal leaves another portal's session signed in. Run with profiles where the
 * device's WebView supports them, and always on the shared-jar fallback. Sessions are planted as cookies, which is
 * what a portal's sign-in leaves behind; no network is used.
 */
@RunWith(AndroidJUnit4::class)
class PortalSessionsTest {
    private val naukri = PortalSession("naukri", listOf("https://www.naukri.com", "https://login.naukri.com"))
    private val instahyre = PortalSession("instahyre", listOf("https://www.instahyre.com"))

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun signIn(
        sessions: PortalSessions,
        portal: PortalSession,
        cookie: String,
    ) = onMain {
        val origin = portal.origins.first()
        sessions.cookiesOf(portal).setCookie(origin, "$cookie; Path=/; Secure")
        // A sign-in often sets the session for the whole site, not just one host.
        val parent = origin.substringAfter("://www.")
        sessions.cookiesOf(portal).setCookie(origin, "site_$cookie; Path=/; Secure; Domain=.$parent")
    }

    private fun cookies(
        sessions: PortalSessions,
        portal: PortalSession,
    ): String = onMain { sessions.cookiesOf(portal).getCookie(portal.origins.first()).orEmpty() }

    private fun assertWipingOneKeepsTheOther(sessions: PortalSessions) {
        signIn(sessions, naukri, "nauk_sid=abc")
        signIn(sessions, instahyre, "insta_sid=xyz")
        assertTrue(cookies(sessions, naukri).contains("nauk_sid=abc"))

        onMain { sessions.wipe(naukri) }

        val naukriAfter = cookies(sessions, naukri)
        assertFalse("Naukri is signed out: $naukriAfter", naukriAfter.contains("sid="))
        val instahyreAfter = cookies(sessions, instahyre)
        assertTrue("Instahyre is still signed in: $instahyreAfter", instahyreAfter.contains("insta_sid=xyz"))
        assertTrue(instahyreAfter.contains("site_insta_sid=xyz"))
        onMain { sessions.wipe(instahyre) }
    }

    @Test
    fun withProfilesWipingOnePortalKeepsAnotherSignedIn() {
        assumeTrue(
            "this WebView has no multi-profile support",
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE),
        )
        val sessions = PortalSessions()
        assertTrue(sessions.isolated)

        assertWipingOneKeepsTheOther(sessions)
    }

    @Test
    fun withProfilesOnePortalNeverSeesAnothersCookies() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val sessions = PortalSessions()
        signIn(sessions, naukri, "nauk_sid=abc")

        // The other profile's jar has no cookie for Naukri's site, even though it is the same site.
        val seenFromInstahyre = onMain { sessions.cookiesOf(instahyre).getCookie(naukri.origins.first()).orEmpty() }

        assertFalse(seenFromInstahyre.contains("nauk_sid"))
        onMain { sessions.wipe(naukri) }
    }

    @Test
    fun onTheSharedJarWipingOnePortalKeepsAnotherSignedIn() {
        assertWipingOneKeepsTheOther(PortalSessions(multiProfile = false))
    }
}
