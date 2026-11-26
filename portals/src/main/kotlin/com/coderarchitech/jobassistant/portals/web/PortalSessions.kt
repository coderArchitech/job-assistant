package com.coderarchitech.jobassistant.portals.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Keeps each portal's sign-in apart (design, WebView runtime; task T3.08).
 *
 * Where the WebView supports it, every portal gets its own profile from `ProfileStore`, with its own cookies,
 * storage and cache, so wiping one portal empties its profile and touches nothing else. This is the real
 * isolation.
 *
 * Older WebViews have one cookie jar for the whole app. There a wipe expires the cookies sent to the portal's own
 * hosts (on each host and on its parent domains, except a parent another portal also uses) and deletes their
 * site storage. A shared jar cannot be wiped per site completely: a cookie on another path, a session on a
 * sign-in provider's host, and the HTTP cache are not reachable per site, so those survive the fallback's wipe.
 *
 * Use it on the main thread, like WebView itself.
 *
 * @param portals every portal the app knows, so a wipe on the shared jar spares domains another portal uses.
 * @param multiProfile whether to use profiles; null asks the WebView. Tests force the shared-jar path with false.
 */
class PortalSessions(
    private val portals: List<PortalSession>,
    multiProfile: Boolean? = null,
) {
    /** True when each portal has its own profile; false on the shared-jar fallback. */
    val isolated: Boolean = multiProfile ?: WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /**
     * Puts [webView] in [portal]'s profile, creating it if needed. Call it before the view loads anything; a
     * WebView cannot change profile after its first load. On the fallback every view shares the jar.
     */
    @SuppressLint("RequiresFeature") // Checked once in isolated.
    fun attach(
        webView: WebView,
        portal: PortalSession,
    ) {
        if (isolated) WebViewCompat.setProfile(webView, portal.profileName)
    }

    /** The cookie jar [portal]'s pages use, or null when its profile does not exist yet (never opened). */
    @SuppressLint("RequiresFeature")
    fun cookiesOf(portal: PortalSession): CookieManager? =
        if (isolated) existingProfile(portal)?.cookieManager else CookieManager.getInstance()

    /**
     * The portal's profile if it exists. Checks the names first: some WebViews (API 36's) crash inside
     * `getProfile` for a name with no profile instead of returning null.
     */
    @SuppressLint("RequiresFeature")
    private fun existingProfile(portal: PortalSession): Profile? {
        val store = ProfileStore.getInstance()
        return if (portal.profileName in store.allProfileNames) store.getProfile(portal.profileName) else null
    }

    /** Signs [portal] out by removing what it stored, leaving every other portal signed in. */
    suspend fun wipe(portal: PortalSession) = if (isolated) wipeProfile(portal) else wipeSharedJar(portal)

    /**
     * Empties the portal's profile in place rather than deleting it: deleting fails while a WebView still holds
     * the profile, and an empty profile is signed out all the same.
     */
    @SuppressLint("RequiresFeature")
    private suspend fun wipeProfile(portal: PortalSession) {
        val profile = existingProfile(portal) ?: return
        profile.cookieManager.removeAll()
        profile.cookieManager.flush()
        profile.webStorage.deleteAllData()
    }

    private fun wipeSharedJar(portal: PortalSession) {
        val cookies = CookieManager.getInstance()
        val shared = portals.filter { it.id != portal.id }.flatMap { other -> other.hosts.flatMap(::parentsOf) }.toSet()
        portal.origins.zip(portal.hosts).forEach { (origin, host) ->
            val names =
                cookies
                    .getCookie(origin)
                    .orEmpty()
                    .split(';')
                    .mapNotNull { pair -> pair.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
                    .toSet()
            val domains = listOf(host) + parentsOf(host).filter { it !in shared }
            for (name in names) expire(cookies, origin, name, domains)
            WebStorage.getInstance().deleteOrigin(origin)
        }
        cookies.flush()
    }

    /** Always Secure, so `__Secure-` and `__Host-` cookies are replaced too; `__Host-` ones never take a Domain. */
    private fun expire(
        cookies: CookieManager,
        origin: String,
        name: String,
        domains: List<String>,
    ) {
        cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure")
        if (!name.startsWith(HOST_PREFIX)) {
            domains.forEach { cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; Secure; Domain=$it") }
        }
    }

    /** `login.jobs.example.com` gives `jobs.example.com` and `example.com`, never a bare top-level domain. */
    private fun parentsOf(host: String): List<String> {
        val labels = host.split('.')
        return (1..labels.size - MIN_LABELS).map { labels.drop(it).joinToString(".") }
    }

    private suspend fun CookieManager.removeAll() =
        suspendCancellableCoroutine { done ->
            removeAllCookies {
                done.resume(Unit)
            }
        }

    private companion object {
        const val HOST_PREFIX = "__Host-"
        const val MIN_LABELS = 2
    }
}

/**
 * A portal as far as its session is concerned: a stable [id] for its profile and the [origins] it signs in on,
 * such as `https://www.naukri.com` and `https://login.naukri.com`, written canonically: lower case, HTTPS, no
 * port or path.
 */
data class PortalSession(
    val id: String,
    val origins: List<String>,
) {
    init {
        require(ID.matches(id)) { "A portal id is lower-case letters, digits and '-'" }
        require(
            origins.isNotEmpty() && origins.all { ORIGIN.matches(it) },
        ) { "Origins are https://host, lower case, no port" }
    }

    /** The WebView profile name, one per portal. */
    val profileName: String get() = "portal-$id"

    /** The hosts of [origins]. */
    val hosts: List<String> get() = origins.map { it.removePrefix("https://") }

    private companion object {
        val ID = Regex("[a-z0-9][a-z0-9-]{0,40}")
        val ORIGIN = Regex("https://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
    }
}
