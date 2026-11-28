package com.coderarchitech.jobassistant.portals.adapter

import com.coderarchitech.jobassistant.portals.web.AgentWebView
import com.coderarchitech.jobassistant.portals.web.bridge.BridgeCommand
import com.coderarchitech.jobassistant.portals.web.bridge.BridgeResult
import com.coderarchitech.jobassistant.portals.web.bridge.JsBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The page an adapter drives: loading pages and sending bridge commands. Faked in tests. */
interface PageDriver {
    /** The bridge's current page generation (see [JsBridge.generation]). */
    val generation: Int

    /** Loads [url] and waits for its bridge; false if it never became ready (blocked, failed or too slow). */
    suspend fun open(url: String): Boolean

    /** Carries out [command] on the current page. */
    suspend fun send(command: BridgeCommand): BridgeResult

    /** Waits for a page newer than generation [after], as after a click that navigates. */
    suspend fun awaitNewPage(after: Int): Boolean
}

/** Credentials for signing in, read from the vault by the caller (task T3.17). Never logged. */
class PortalCredentials(
    val username: String,
    val password: String,
) {
    override fun toString() = "PortalCredentials(<hidden>)"
}

/** Where [ScriptedPortalAdapter.login] gets credentials; null when the portal has none stored. */
fun interface CredentialSource {
    /** The portal's credentials, or null. */
    suspend fun credentials(): PortalCredentials?
}

/**
 * The real [PageDriver]: an [AgentWebView] with a [JsBridge] attached for the portal. Loads run on the main
 * thread, like the WebView; [loadTimeoutMs] bounds how long a page may take to say it is ready.
 */
class WebViewPageDriver(
    private val webView: AgentWebView,
    private val bridge: JsBridge,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val loadTimeoutMs: Long = LOAD_TIMEOUT_MS,
) : PageDriver {
    override val generation: Int get() = bridge.generation

    override suspend fun open(url: String): Boolean {
        val before = withContext(main) { bridge.generation.also { webView.loadUrl(url) } }
        return bridge.awaitPage(newerThan = before, timeoutMs = loadTimeoutMs)
    }

    override suspend fun send(command: BridgeCommand): BridgeResult = bridge.send(command)

    override suspend fun awaitNewPage(after: Int): Boolean =
        bridge.awaitPage(newerThan = after, timeoutMs = loadTimeoutMs)

    private companion object {
        const val LOAD_TIMEOUT_MS = 30_000L
    }
}
