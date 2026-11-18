package com.coderarchitech.jobassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Task T1.08, decision D03: backups exclude the vault, WebView data and apply proofs by exact path,
 * and nothing else. Reads the resource files as written, so a rule added or widened by mistake fails
 * here before it reaches a device.
 */
class BackupRulesTest {
    private val expectedExcludes =
        setOf(
            Rule(domain = "file", path = "datastore/vault.pb"),
            // DataStore's temporary file during a vault write (FileStorage writes <name>.tmp, then renames).
            Rule(domain = "file", path = "datastore/vault.pb.tmp"),
            Rule(domain = "root", path = "app_webview/"),
            Rule(domain = "file", path = "proofs/"),
        )

    private data class Rule(
        val domain: String,
        val path: String,
    )

    private fun parse(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(File(path)).documentElement
    }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    /** Asserts [section] holds exactly the expected excludes and no include, which would narrow the backup to it. */
    private fun assertOnlyExpectedExcludes(
        name: String,
        section: Element,
    ) {
        val rules = section.children()
        assertTrue("$name has no <include>", rules.none { it.tagName == "include" })
        assertTrue("$name has only <exclude>: ${rules.map { it.tagName }}", rules.all { it.tagName == "exclude" })
        val excludes = rules.map { Rule(it.getAttribute("domain"), it.getAttribute("path")) }
        assertEquals("$name has no duplicate rule", excludes.size, excludes.toSet().size)
        assertEquals(name, expectedExcludes, excludes.toSet())
        assertTrue("$name keeps settings.pb in the backup", excludes.none(::coversWholeDatastoreDir))
        assertTrue(
            "$name has no attribute such as requireFlags that would make a rule conditional",
            rules.all { it.attributes.length == 2 },
        )
    }

    private fun coversWholeDatastoreDir(rule: Rule): Boolean =
        (rule.domain == "file" && rule.path.trimEnd('/') in setOf("datastore", "", ".")) ||
            (rule.domain == "root" && rule.path.trimEnd('/') in setOf("files", "files/datastore", "", "."))

    @Test
    fun dataExtractionRulesExcludeExactlyTheSecretsForCloudBackupAndDeviceTransfer() {
        val root = parse(DATA_EXTRACTION_RULES)
        assertEquals("data-extraction-rules", root.tagName)
        val sections = root.children()
        assertEquals(listOf("cloud-backup", "device-transfer"), sections.map { it.tagName }.sorted())
        for (section in sections) {
            assertOnlyExpectedExcludes(section.tagName, section)
        }
        val cloud = sections.first { it.tagName == "cloud-backup" }
        assertEquals(
            "cloud-backup changes no default such as disableIfNoEncryptionCapabilities",
            0,
            cloud.attributes.length,
        )
    }

    @Test
    fun fullBackupContentExcludesExactlyTheSecretsOnApi29And30() {
        val root = parse(FULL_BACKUP_CONTENT)
        assertEquals("full-backup-content", root.tagName)
        assertOnlyExpectedExcludes("full-backup-content", root)
    }

    @Test
    fun manifestWiresBothRuleFilesAndTheRestoreAgent() {
        val application = parse(MANIFEST).getElementsByTagName("application").item(0) as Element

        fun attr(name: String) = application.getAttributeNS(ANDROID_NS, name)

        assertEquals("true", attr("allowBackup"))
        assertEquals("@xml/data_extraction_rules", attr("dataExtractionRules"))
        assertEquals("@xml/backup_rules", attr("fullBackupContent"))
        assertEquals(".JobAssistantBackupAgent", attr("backupAgent"))
        // Without fullBackupOnly a custom agent would switch the app to key/value backup.
        assertEquals("true", attr("fullBackupOnly"))
        assertEquals(JobAssistantBackupAgent::class.java.simpleName, attr("backupAgent").removePrefix("."))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        // Unit tests run with the module directory as the working directory.
        const val MANIFEST = "src/main/AndroidManifest.xml"
        const val DATA_EXTRACTION_RULES = "src/main/res/xml/data_extraction_rules.xml"
        const val FULL_BACKUP_CONTENT = "src/main/res/xml/backup_rules.xml"
    }
}
