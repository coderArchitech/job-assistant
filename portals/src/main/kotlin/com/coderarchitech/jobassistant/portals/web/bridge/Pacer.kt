package com.coderarchitech.jobassistant.portals.web.bridge

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Waits a random 1.5 to 4 seconds before each action on a page (design, WebView runtime: pacing), so the
 * agent moves at a person's pace. [random] and [pause] are injected so tests run without waiting.
 */
class Pacer(
    private val random: Random = Random.Default,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /** Waits before the next action. */
    suspend fun beforeAction() = pause(random.nextLong(MIN_MS, MAX_MS + 1))

    /** The pacing range. */
    companion object {
        const val MIN_MS = 1_500L
        const val MAX_MS = 4_000L

        /** No pause at all, for tests and the adapter harness. */
        val NONE = Pacer(pause = {})
    }
}
