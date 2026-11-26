package com.coderarchitech.jobassistant.portals.web.bridge

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.Locale

/**
 * The messages between the app and the page script, kept pure so they are tested on the JVM. Every message
 * from the page carries the bridge's [token], which only the injected script holds; a page script that
 * found the channel could not make up a reply without it.
 */
internal class BridgeProtocol(
    private val token: String,
    private val origins: List<String>,
) {
    /** A message from the page. */
    sealed interface Incoming {
        /** The script started in a new document at [url]. */
        data class Ready(
            val url: String,
        ) : Incoming

        /** The answer to command [id]. */
        data class Reply(
            val id: Int,
            val result: BridgeResult,
        ) : Incoming
    }

    /**
     * True when a message may be read at all: it comes from the main frame of one of the portal's own
     * origins. A frame, or any page of another origin, is never heard.
     */
    fun accepts(
        sourceOrigin: String,
        isMainFrame: Boolean,
    ): Boolean = isMainFrame && sourceOrigin.trimEnd('/') in origins

    /**
     * True when a page at [url] gets the bridge: HTTPS on one of the portal's origins. Any other page that
     * starts (another origin, a blocked one, `about:blank`) will never say it is ready.
     */
    fun isPortalPage(url: String): Boolean {
        val host = PAGE_HOST.find(url)?.groupValues?.get(1) ?: return false
        return "https://${host.lowercase(Locale.ROOT)}" in origins
    }

    /** The command as the page script reads it. */
    fun encode(
        id: Int,
        command: BridgeCommand,
    ): String =
        buildJsonObject {
            put("id", id)
            put("cmd", command.name)
            when (command) {
                is BridgeCommand.Query -> put("selector", command.selector)
                is BridgeCommand.Click -> put("selector", command.selector)
                is BridgeCommand.Type -> {
                    put("selector", command.selector)
                    put("text", command.text)
                    put("secret", command.secret)
                }
                is BridgeCommand.Select -> {
                    put("selector", command.selector)
                    put("option", command.option)
                }
                is BridgeCommand.Upload -> {
                    put("selector", command.selector)
                    put("fileName", command.fileName)
                    put("mimeType", command.mimeType)
                    put("data", Base64.getEncoder().encodeToString(command.bytes))
                }
                is BridgeCommand.WaitFor -> {
                    put("selector", command.selector)
                    put("timeoutMs", command.timeoutMs)
                }
                BridgeCommand.Snapshot -> Unit
            }
        }.toString()

    /** The page's message, or null when it is malformed or lacks the token. */
    fun decode(message: String?): Incoming? {
        val json = parse(message)?.takeIf { it.text("token") == token } ?: return null
        return when (json.text("type")) {
            "ready" -> json.text("url")?.let { Incoming.Ready(it) }
            "reply" -> reply(json)
            else -> null
        }
    }

    private fun reply(json: JsonObject): Incoming.Reply? {
        val id = json.literal("id")?.intOrNull
        val ok = json.literal("ok")?.booleanOrNull
        if (id == null || ok == null) return null
        val result =
            if (ok) {
                BridgeResult.Done(json["value"] ?: JsonNull)
            } else {
                BridgeResult.Failed(BridgeError.of(json.text("code")), json.text("detail").orEmpty().take(MAX_DETAIL))
            }
        return Incoming.Reply(id, result)
    }

    /** A number or boolean, never a string that looks like one. */
    private fun JsonObject.literal(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }

    private fun parse(message: String?): JsonObject? =
        try {
            message?.let { Json.parseToJsonElement(it) as? JsonObject }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val MAX_DETAIL = 200

        /** The host of an https URL, on the default port, with no user name. */
        val PAGE_HOST = Regex("^https://([a-zA-Z0-9.-]+)(?::443)?(?:[/?#]|$)")
    }
}
