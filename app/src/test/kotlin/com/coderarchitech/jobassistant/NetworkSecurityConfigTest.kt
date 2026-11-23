package com.coderarchitech.jobassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Task T1.10: the network security config forbids cleartext traffic and trusts only the system
 * certificate store, and the manifest uses it. Reads the files as written, so a loosened rule fails
 * here before it reaches a device.
 */
class NetworkSecurityConfigTest {
    private fun parse(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(File(path)).documentElement
    }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private val config get() = parse("src/main/res/xml/network_security_config.xml")

    @Test
    fun cleartextTrafficIsForbiddenByDefault() {
        val base = config.children().single { it.tagName == "base-config" }

        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
    }

    @Test
    fun onlyTheSystemCertificateStoreIsTrusted() {
        val base = config.children().single { it.tagName == "base-config" }
        val anchors = base.children().single { it.tagName == "trust-anchors" }.children()

        assertEquals(listOf("system"), anchors.map { it.getAttribute("src") })
    }

    @Test
    fun noDomainAllowsCleartextAndNoBuildTypeTrustsUserCertificates() {
        val tags = config.children().map { it.tagName }

        assertEquals("only the base config: $tags", listOf("base-config"), tags)
        val text = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue("no user CAs", !text.contains("src=\"user\""))
        assertTrue("no debug overrides", !text.contains("debug-overrides"))
    }

    @Test
    fun theManifestUsesTheConfigAndRequestsInternet() {
        val manifest = parse("src/main/AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0) as Element
        val permissions =
            manifest.children().filter { it.tagName == "uses-permission" }.map {
                it.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
            }

        assertEquals(
            "@xml/network_security_config",
            application.getAttributeNS("http://schemas.android.com/apk/res/android", "networkSecurityConfig"),
        )
        assertTrue(permissions.toString(), "android.permission.INTERNET" in permissions)
    }
}
