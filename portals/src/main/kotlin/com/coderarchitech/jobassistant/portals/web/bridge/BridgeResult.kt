package com.coderarchitech.jobassistant.portals.web.bridge

import kotlinx.serialization.json.JsonElement

/** What became of one [BridgeCommand]. */
sealed interface BridgeResult {
    /** The page carried it out; [value] is the command's answer (`true` for actions). */
    data class Done(
        val value: JsonElement,
    ) : BridgeResult

    /** The page tried and could not. [detail] is the page's short explanation, never page content. */
    data class Failed(
        val error: BridgeError,
        val detail: String,
    ) : BridgeResult

    /** No page of the portal has its bridge ready (nothing loaded yet, or another origin is showing). */
    data object NoPage : BridgeResult

    /** The page navigated away before it answered; the step's outcome is unknown. */
    data object PageChanged : BridgeResult

    /** The page did not answer in time. */
    data object NoReply : BridgeResult
}
