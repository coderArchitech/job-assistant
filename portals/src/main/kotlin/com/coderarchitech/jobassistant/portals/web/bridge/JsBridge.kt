package com.coderarchitech.jobassistant.portals.web.bridge

import android.annotation.SuppressLint
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.coderarchitech.jobassistant.portals.web.AgentWebView
import com.coderarchitech.jobassistant.portals.web.PortalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * The channel between an adapter and the portal's page (design, WebView runtime; task T3.07).
 *
 * The page script is injected at document start, and the message channel is offered, only in the portal's own
 * origins ([PortalSession.origins]). A message is read only from the main frame of one of those origins and
 * only with this bridge's random token, so a frame or page from anywhere else, a sign-in host included, can
 * never send it anything. Nothing on the page can call into the app: the page only answers commands.
 *
 * Each document that says it is ready gets a new [generation]. After an action that navigates, wait for a page
 * newer than the one acted on with [awaitPage]; commands left waiting on a page that went away end with
 * [BridgeResult.PageChanged]. A page of another origin ends the bridge's page at once.
 *
 * Actions ([BridgeCommand.isAction]) wait for the [Pacer] first. The bridge runs on the main thread, like the
 * WebView; [send] and [awaitPage] switch to it themselves.
 */
class JsBridge private constructor(
    private val webView: AgentWebView,
    private val protocol: BridgeProtocol,
    private val pacer: Pacer,
    private val main: CoroutineDispatcher,
    private val onRejected: (origin: String) -> Unit,
    private val channelName: String,
) {
    private class Page(
        val url: String,
        val proxy: JavaScriptReplyProxy,
        val generation: Int,
    )

    private val page = MutableStateFlow<Page?>(null)
    private val pending = HashMap<Int, CompletableDeferred<BridgeResult>>()
    private var nextId = 0
    private var readyCount = 0
    private var script: ScriptHandler? = null

    private val listener =
        WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, replyProxy ->
            val origin = sourceOrigin.toString()
            val incoming = if (protocol.accepts(origin, isMainFrame)) protocol.decode(message.data) else null
            when (incoming) {
                null -> onRejected(origin)
                is BridgeProtocol.Incoming.Ready -> {
                    endPage()
                    page.value = Page(incoming.url, replyProxy, ++readyCount)
                }
                is BridgeProtocol.Incoming.Reply -> pending[incoming.id]?.complete(incoming.result)
            }
        }

    /** The URL of the page whose bridge is ready, or null. Never log it: a query can carry a token. */
    val pageUrl: String? get() = page.value?.url

    /** The current page's generation, or the last one's when no page is ready; 0 before any page. */
    val generation: Int get() = page.value?.generation ?: readyCount

    /**
     * Waits until a page of the portal newer than generation [newerThan] has its bridge ready; false after
     * [timeoutMs]. With the default, any ready page will do.
     */
    suspend fun awaitPage(
        newerThan: Int = 0,
        timeoutMs: Long = REPLY_MS,
    ): Boolean =
        withContext(main) {
            withTimeoutOrNull(timeoutMs) { page.first { it != null && it.generation > newerThan } } != null
        }

    /** Carries out [command] on the current page and returns what happened. */
    @SuppressLint("RequiresFeature") // A bridge exists only where attach found the feature.
    suspend fun send(
        command: BridgeCommand,
        replyTimeoutMs: Long = REPLY_MS,
    ): BridgeResult =
        withContext(main) {
            if (page.value == null) return@withContext BridgeResult.NoPage
            if (command.isAction) pacer.beforeAction()
            val current = page.value ?: return@withContext BridgeResult.PageChanged
            val id = ++nextId
            val reply = CompletableDeferred<BridgeResult>()
            pending[id] = reply
            try {
                current.proxy.postMessage(protocol.encode(id, command))
                val wait = replyTimeoutMs + ((command as? BridgeCommand.WaitFor)?.timeoutMs ?: 0L)
                withTimeoutOrNull(wait) { reply.await() } ?: BridgeResult.NoReply
            } finally {
                pending.remove(id)
            }
        }

    /** Removes the bridge from the view; later commands return [BridgeResult.NoPage]. Main thread only. */
    @SuppressLint("RequiresFeature") // Checked in attach.
    fun close() {
        webView.onPageStartedForBridge = null
        script?.remove()
        script = null
        WebViewCompat.removeWebMessageListener(webView, channelName)
        endPage()
    }

    /**
     * A page started loading. One of the portal's own will say it is ready, which replaces the old page then;
     * any other page never will, so the old page ends now.
     */
    private fun pageStarted(url: String) {
        if (!protocol.isPortalPage(url)) endPage()
    }

    private fun endPage() {
        page.value = null
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.complete(BridgeResult.PageChanged) }
    }

    /** Installing a bridge. */
    companion object {
        /** How long a command waits for the page's answer, on top of a [BridgeCommand.WaitFor]'s own wait. */
        const val REPLY_MS = 10_000L

        private const val SCRIPT = "portals/bridge.js"
        private const val TOKEN_BYTES = 24
        private const val NAME_CHARS = 12

        /** True when the WebView can host the bridge (origin-bound messages and document-start scripts). */
        fun isSupported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        /**
         * Installs the bridge for [portal] on [webView]. Call it on the main thread before the view loads
         * anything, and only when [isSupported]; it throws otherwise. [onRejected] hears the origin of every
         * message that was ignored. [main] is the WebView's thread, injected for tests.
         */
        @SuppressLint("RequiresFeature") // Checked first.
        fun attach(
            webView: AgentWebView,
            portal: PortalSession,
            pacer: Pacer = Pacer(),
            main: CoroutineDispatcher = Dispatchers.Main.immediate,
            onRejected: (origin: String) -> Unit = {},
        ): JsBridge {
            check(isSupported()) { "This WebView cannot host the JS bridge" }
            val token = randomHex()
            val name = "_" + randomHex().take(NAME_CHARS)
            val bridge = JsBridge(webView, BridgeProtocol(token, portal.origins), pacer, main, onRejected, name)
            val origins = portal.origins.toSet()
            WebViewCompat.addWebMessageListener(webView, name, origins, bridge.listener)
            val source =
                webView.context.assets
                    .open(SCRIPT)
                    .bufferedReader()
                    .use { it.readText() }
                    .replace("__NAME__", name)
                    .replace("__TOKEN__", token)
            bridge.script = WebViewCompat.addDocumentStartJavaScript(webView, source, origins)
            webView.onPageStartedForBridge = bridge::pageStarted
            return bridge
        }

        private fun randomHex(): String =
            ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
