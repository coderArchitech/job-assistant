package com.coderarchitech.jobassistant.portals.web.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BridgeProtocolTest {
    private val protocol = BridgeProtocol(MARK, listOf("https://www.naukri.com", "https://login.naukri.com"))

    @Test
    fun onlyTheMainFrameOfThePortalsOwnOriginsIsHeard() {
        val cases =
            listOf(
                Triple("https://www.naukri.com", true, true),
                Triple("https://www.naukri.com/", true, true),
                Triple("https://login.naukri.com", true, true),
                // A frame of the portal itself is not trusted either: it may be an ad or a third-party widget.
                Triple("https://www.naukri.com", false, false),
                Triple("https://evil.example", true, false),
                Triple("https://naukri.com", true, false),
                Triple("http://www.naukri.com", true, false),
                Triple("https://www.naukri.com.evil.example", true, false),
                Triple("https://accounts.google.com", true, false),
                Triple("null", true, false),
            )
        for ((origin, mainFrame, expected) in cases) {
            assertEquals("$origin main=$mainFrame", expected, protocol.accepts(origin, mainFrame))
        }
    }

    @Test
    fun onlyThePortalsOwnPagesGetTheBridge() {
        val cases =
            mapOf(
                "https://www.naukri.com/" to true,
                "https://www.naukri.com" to true,
                "https://WWW.Naukri.com/jobs?q=1#top" to true,
                "https://www.naukri.com:443/x" to true,
                "https://login.naukri.com/sign-in" to true,
                "https://www.naukri.com:8443/" to false,
                "http://www.naukri.com/" to false,
                "https://naukri.com/" to false,
                "https://www.naukri.com.evil.example/" to false,
                "https://user@www.naukri.com/" to false,
                "about:blank" to false,
                "data:text/html,hi" to false,
            )
        for ((url, expected) in cases) assertEquals(url, expected, protocol.isPortalPage(url))
    }

    @Test
    fun commandsCarryWhatThePageScriptReads() {
        val type = decodeJson(protocol.encode(1, BridgeCommand.Type("#user", "someone@example.com")))
        assertEquals("type", type.text("cmd"))
        assertEquals("#user", type.text("selector"))
        assertEquals("someone@example.com", type.text("text"))
        assertEquals("1", type["id"]?.jsonPrimitive?.content)
        assertEquals("false", type["secret"]?.jsonPrimitive?.content)
        val password = decodeJson(protocol.encode(5, BridgeCommand.Type("#pass", "fake", secret = true)))
        assertEquals("true", password["secret"]?.jsonPrimitive?.content)

        val cv = BridgeCommand.Upload("#cv", "cv.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        val upload = decodeJson(protocol.encode(2, cv))
        assertEquals("cv.pdf", upload.text("fileName"))
        assertEquals("application/pdf", upload.text("mimeType"))
        assertEquals(listOf<Byte>(1, 2, 3), Base64.getDecoder().decode(upload.text("data")).toList())

        val wait = decodeJson(protocol.encode(3, BridgeCommand.WaitFor("#late", 2_500)))
        assertEquals("2500", wait["timeoutMs"]?.jsonPrimitive?.content)

        assertEquals("snapshot", decodeJson(protocol.encode(4, BridgeCommand.Snapshot)).text("cmd"))

        val extract =
            decodeJson(
                protocol.encode(
                    6,
                    BridgeCommand.Extract(
                        mapOf("id" to BridgeCommand.Field("", "data-id"), "title" to BridgeCommand.Field(".title")),
                        items = ".job",
                    ),
                ),
            )
        assertEquals(".job", extract.text("items"))
        assertEquals(
            "data-id",
            extract["fields"]
                ?.jsonObject
                ?.get("id")
                ?.jsonObject
                ?.text("attribute"),
        )
        assertEquals(
            null,
            extract["fields"]
                ?.jsonObject
                ?.get("title")
                ?.jsonObject
                ?.get("attribute"),
        )
        assertEquals("${BridgeCommand.MAX_ITEMS}", extract["max"]?.jsonPrimitive?.content)
    }

    @Test
    fun theTokenNeverGoesToThePage() {
        val commands =
            listOf(
                BridgeCommand.Query("a"),
                BridgeCommand.Click("a"),
                BridgeCommand.Select("s", "x"),
                BridgeCommand.Snapshot,
            )
        commands.forEach { assertFalse(protocol.encode(1, it).contains(MARK)) }
    }

    @Test
    fun readyAndRepliesAreDecoded() {
        assertEquals(
            BridgeProtocol.Incoming.Ready("https://www.naukri.com/"),
            protocol.decode("""{"type":"ready","url":"https://www.naukri.com/","token":"$MARK"}"""),
        )
        assertEquals(
            BridgeProtocol.Incoming.Reply(7, BridgeResult.Done(JsonPrimitive(true))),
            protocol.decode("""{"type":"reply","id":7,"ok":true,"value":true,"token":"$MARK"}"""),
        )
        assertEquals(
            BridgeProtocol.Incoming.Reply(8, BridgeResult.Done(JsonNull)),
            protocol.decode("""{"type":"reply","id":8,"ok":true,"token":"$MARK"}"""),
        )
        assertEquals(
            BridgeProtocol.Incoming.Reply(9, BridgeResult.Failed(BridgeError.NOT_FOUND, "#apply")),
            protocol.decode(
                """{"type":"reply","id":9,"ok":false,"code":"not_found","detail":"#apply","token":"$MARK"}""",
            ),
        )
        val unknown = protocol.decode("""{"type":"reply","id":9,"ok":false,"code":"weird","token":"$MARK"}""")
        assertEquals(BridgeResult.Failed(BridgeError.PAGE_ERROR, ""), (unknown as BridgeProtocol.Incoming.Reply).result)
    }

    @Test
    fun messagesWithoutTheTokenOrMalformedAreIgnored() {
        val ignored =
            listOf(
                null,
                "",
                "not json",
                "[1,2]",
                """{"type":"ready","url":"https://www.naukri.com/"}""",
                """{"type":"ready","url":"https://www.naukri.com/","token":"guess"}""",
                """{"type":"ready","url":"https://www.naukri.com/","token":7}""",
                """{"type":"reply","id":"7","ok":true,"token":"$MARK"}""",
                """{"type":"reply","id":7,"token":"$MARK"}""",
                """{"type":"command","token":"$MARK"}""",
            )
        ignored.forEach { assertNull(it, protocol.decode(it)) }
    }

    @Test
    fun aFailureDetailIsKeptShort() {
        val long = "x".repeat(5_000)
        val reply =
            protocol.decode("""{"type":"reply","id":1,"ok":false,"code":"timeout","detail":"$long","token":"$MARK"}""")
        val failed = (reply as BridgeProtocol.Incoming.Reply).result as BridgeResult.Failed
        assertEquals(200, failed.detail.length)
    }

    @Test
    fun typedTextAndFileBytesStayOutOfLogs() {
        val type = BridgeCommand.Type("#password", "hunter2-fake").toString()
        assertFalse(type, type.contains("hunter2"))
        assertTrue(type.contains("12 chars"))
        val upload = BridgeCommand.Upload("#cv", "cv.pdf", "application/pdf", "secret".toByteArray()).toString()
        assertFalse(upload.contains("secret"))
    }

    @Test
    fun onlyActionsArePaced() {
        val actions =
            listOf(
                BridgeCommand.Click("a"),
                BridgeCommand.Type("a", "b"),
                BridgeCommand.Select("a", "b"),
                BridgeCommand.Upload("a", "b", "c", ByteArray(0)),
            )
        val reads = listOf(BridgeCommand.Query("a"), BridgeCommand.WaitFor("a"), BridgeCommand.Snapshot)
        assertTrue(actions.all { it.isAction })
        assertTrue(reads.none { it.isAction })
    }

    private fun decodeJson(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun kotlinx.serialization.json.JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    private companion object {
        const val MARK = "test-bridge-mark"
    }
}
