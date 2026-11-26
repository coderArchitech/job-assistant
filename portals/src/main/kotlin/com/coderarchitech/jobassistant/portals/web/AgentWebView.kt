package com.coderarchitech.jobassistant.portals.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * The WebView portals run in (design, WebView runtime; task T3.06), hardened and held to a
 * [NavigationPolicy]:
 * - file and content access off, Safe Browsing on, mixed content never allowed, no geolocation, no pop-up
 *   windows, downloads only reported;
 * - no `addJavascriptInterface`: it throws, so no page can reach Kotlin objects (the JS bridge uses origin-bound
 *   web message listeners instead, task T3.07);
 * - every top-level navigation is checked, whether it starts from [loadUrl], a link, a script, a form post or
 *   a redirect; one the policy does not allow is stopped and reported once to [listener], on the main thread;
 * - requests to LinkedIn are refused, including images and scripts in an allowed page and service-worker
 *   fetches.
 *
 * Limits a WebView cannot close: a sub-resource that redirects, a WebSocket, and a page framed from another
 * origin are not seen by any callback. The app itself never sends the runtime to LinkedIn, and framed pages
 * get no bridge, since T3.07 binds messages to the portal's origins.
 *
 * Use it on the main thread, like any WebView.
 *
 * @param requestInterceptor answers requests before the network, as the adapter test harness does with
 *   `WebViewAssetLoader` (task T3.12); null in production.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor") // Adapters are scripts; built in code, never inflated.
class AgentWebView(
    context: Context,
    private val policy: NavigationPolicy,
    private val listener: Listener,
    private val requestInterceptor: RequestInterceptor? = null,
) : WebView(context) {
    /** What the runtime tells its host screen. Every call arrives on the main thread. */
    interface Listener {
        /** A navigation to [url] was stopped; an [NavigationDecision.OPEN_OUTSIDE] one may be opened elsewhere. */
        fun onNavigationStopped(
            url: String,
            decision: NavigationDecision,
        )

        /** An allowed page finished loading. */
        fun onPageFinished(url: String) = Unit

        /** The page's renderer died or was killed; this view must be destroyed and the step ended. */
        fun onRendererGone() = Unit
    }

    /** Answers a request locally, or returns null to let it go to the network. */
    fun interface RequestInterceptor {
        fun intercept(request: WebResourceRequest): WebResourceResponse?
    }

    private val main = Handler(Looper.getMainLooper())

    /** The last navigation reported, so the several check points one navigation passes report it once. */
    @Volatile private var lastStopped: String? = null

    init {
        harden(settings)
        webViewClient = GuardClient()
        webChromeClient = NoNewWindows()
        // Downloads never start here; a web one is offered outside, LinkedIn or a non-web scheme is not.
        setDownloadListener { url, _, _, _, _ ->
            val decision = policy.decide(url)
            stopped(url, if (decision == NavigationDecision.ALLOW) NavigationDecision.OPEN_OUTSIDE else decision)
        }
        guardServiceWorkers()
    }

    override fun loadUrl(url: String) {
        if (allowed(url)) super.loadUrl(url)
    }

    override fun loadUrl(
        url: String,
        additionalHttpHeaders: MutableMap<String, String>,
    ) {
        if (allowed(url)) super.loadUrl(url, additionalHttpHeaders)
    }

    override fun postUrl(
        url: String,
        postData: ByteArray,
    ) {
        if (allowed(url)) super.postUrl(url, postData)
    }

    /** Pages come from the portal's hosts only; markup handed in directly has no origin to check. */
    override fun loadData(
        data: String,
        mimeType: String?,
        encoding: String?,
    ) {
        stopped("data:", NavigationDecision.BLOCK)
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        if (baseUrl != null && allowed(baseUrl)) {
            super.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)
        }
    }

    /** Never: a page could call any public method of the object (design, threat model). */
    @SuppressLint("JavascriptInterface")
    override fun addJavascriptInterface( // ci-allow: js-interface (the override that forbids it)
        obj: Any,
        name: String,
    ): Unit = throw UnsupportedOperationException("The portal runtime has no JavaScript interfaces")

    private fun allowed(url: String): Boolean {
        val decision = policy.decide(url)
        if (decision != NavigationDecision.ALLOW) stopped(url, decision)
        return decision == NavigationDecision.ALLOW
    }

    /** Reports a stopped navigation on the main thread, once even when several check points catch it. */
    private fun stopped(
        url: String,
        decision: NavigationDecision,
    ) {
        if (lastStopped == url) return
        lastStopped = url
        main.post { listener.onNavigationStopped(url, decision) }
    }

    /**
     * Service workers fetch outside [GuardClient], so LinkedIn is refused there too. The controller is one per
     * process; every runtime installs the same rule, so the last one installed is equivalent.
     */
    private fun guardServiceWorkers() {
        ServiceWorkerController.getInstance().setServiceWorkerClient(
            object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    if (policy.isBlockedHost(request.url.toString())) refused() else null
            },
        )
    }

    private inner class GuardClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            val decision = policy.decide(url)
            // A frame inside an allowed page (a captcha, an embedded form) may come from elsewhere, but never
            // from LinkedIn or a non-web scheme.
            val stop =
                if (request.isForMainFrame) {
                    decision != NavigationDecision.ALLOW
                } else {
                    decision ==
                        NavigationDecision.BLOCK
                }
            if (stop) stopped(url, decision)
            return stop
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            // Form posts never reach shouldOverrideUrlLoading, so a page load is also checked here, before the
            // request leaves the device.
            val decision = if (request.isForMainFrame) policy.decide(url) else NavigationDecision.ALLOW
            if (decision != NavigationDecision.ALLOW) stopped(url, decision)
            val refuse = decision != NavigationDecision.ALLOW || policy.isBlockedHost(url)
            return if (refuse) refused() else requestInterceptor?.intercept(request)
        }

        /** A backstop for any navigation that reached the page without passing the checks above. */
        override fun onPageStarted(
            view: WebView,
            url: String,
            favicon: Bitmap?,
        ) {
            val decision = policy.decide(url)
            if (decision != NavigationDecision.ALLOW) {
                view.stopLoading()
                stopped(url, decision)
                view.loadUrl(NavigationPolicy.ABOUT_BLANK)
            } else if (url != NavigationPolicy.ABOUT_BLANK) {
                lastStopped = null
            }
        }

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) {
            val shown = url != NavigationPolicy.ABOUT_BLANK && policy.decide(url) == NavigationDecision.ALLOW
            if (shown) listener.onPageFinished(url)
        }

        /** Keeps the app alive when the renderer dies; the host destroys this view. */
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            listener.onRendererGone()
            return true
        }
    }

    private class NoNewWindows : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean = false
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403

        @Suppress("DEPRECATION") // The file-URL settings are deprecated but still honoured; set them anyway.
        fun harden(settings: WebSettings) =
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                setGeolocationEnabled(false)
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = true
                saveFormData = false
            }

        fun refused() =
            WebResourceResponse(
                "text/plain",
                "utf-8",
                HTTP_FORBIDDEN,
                "Blocked by the app",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )
    }
}
