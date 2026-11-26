package com.coderarchitech.jobassistant.portals.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Keeps each portal's sign-in apart (design, WebView runtime; task T3.08). Where the WebView supports it, every
 * portal gets its own profile from `ProfileStore`, with its own cookies and storage, so wiping one portal deletes
 * its profile and nothing else. Older WebViews have one shared cookie jar; there a wipe expires only the cookies
 * and site storage of the portal's own hosts, so other portals stay signed in.
 *
 * Use it on the main thread, like WebView itself.
 *
 * @param multiProfile whether to use profiles; null asks the WebView. Tests force the shared-jar path with false.
 */
class PortalSessions(
    multiProfile: Boolean? = null,
) {
    /** True when each portal has its own profile; false on the shared-jar fallback. */
    val isolated: Boolean = multiProfile ?: WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /**
     * Puts [webView] in [portal]'s profile. Call it before the view loads anything; a WebView cannot change
     * profile after its first load. On the fallback there is nothing to do: every view shares the jar.
     */
    @SuppressLint("RequiresFeature") // Checked once in isolated.
    fun attach(
        webView: WebView,
        portal: PortalSession,
    ) {
        if (isolated) {
            ProfileStore.getInstance().getOrCreateProfile(portal.profileName)
            WebViewCompat.setProfile(webView, portal.profileName)
        }
    }

    /** The cookie jar [portal]'s pages use. */
    @SuppressLint("RequiresFeature")
    fun cookiesOf(portal: PortalSession): CookieManager =
        if (isolated) {
            ProfileStore
                .getInstance()
                .getOrCreateProfile(
                    portal.profileName,
                ).cookieManager
        } else {
            CookieManager.getInstance()
        }

    /**
     * Signs [portal] out by removing everything it stored: with profiles, its whole profile (no WebView may still be
     * using it); on the fallback, the cookies and site storage of each of its hosts. Other portals are untouched.
     */
    @SuppressLint("RequiresFeature")
    suspend fun wipe(portal: PortalSession) {
        if (isolated) {
            val store = ProfileStore.getInstance()
            if (store.getProfile(portal.profileName) != null && !store.deleteProfile(portal.profileName)) {
                // The profile is still in use: clear it in place, so the wipe never silently fails.
                val profile = store.getOrCreateProfile(portal.profileName)
                profile.cookieManager.removeAll()
                profile.webStorage.deleteAllData()
            }
        } else {
            val cookies = CookieManager.getInstance()
            portal.origins.forEach { origin ->
                expireCookies(cookies, origin)
                WebStorage.getInstance().deleteOrigin(origin)
            }
            cookies.flush()
        }
    }

    /**
     * Expires every cookie the shared jar sends to [origin]. `CookieManager` has no per-site delete, so each cookie
     * is set again already expired, on the host and on its parent domain, where a site may have scoped it.
     */
    private fun expireCookies(
        cookies: CookieManager,
        origin: String,
    ) {
        val names =
            cookies
                .getCookie(origin)
                .orEmpty()
                .split(';')
                .mapNotNull { pair -> pair.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
                .toSet()
        val host = origin.substringAfter("://").substringBefore('/')
        for (name in names) {
            cookies.setCookie(origin, "$name=; Max-Age=0; Path=/")
            cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=$host")
            parentDomain(host)?.let { cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=.$it") }
        }
    }

    private fun parentDomain(host: String): String? =
        host
            .split('.')
            .takeIf { it.size > 2 }
            ?.drop(1)
            ?.joinToString(".")

    private suspend fun CookieManager.removeAll() =
        suspendCancellableCoroutine { done ->
            removeAllCookies {
                done.resume(Unit)
            }
        }
}

/**
 * A portal as far as its session is concerned: a stable [id] for its profile and the [origins] it signs in on,
 * such as `https://www.naukri.com` and `https://login.naukri.com`.
 */
data class PortalSession(
    val id: String,
    val origins: List<String>,
) {
    init {
        require(ID.matches(id)) { "A portal id is lower-case letters, digits and '-'" }
        require(origins.all { it.startsWith("https://") }) { "Portal origins are HTTPS" }
    }

    /** The WebView profile name, one per portal. */
    val profileName: String get() = "portal-$id"

    private companion object {
        val ID = Regex("[a-z0-9][a-z0-9-]{0,40}")
    }
}
