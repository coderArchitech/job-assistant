package com.coderarchitech.jobassistant.core.data.settings

import com.coderarchitech.jobassistant.core.data.settings.proto.SettingsProto
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiSettings
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.model.AppSettings
import com.coderarchitech.jobassistant.core.model.Caps
import com.coderarchitech.jobassistant.core.model.FeatureToggles
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalTime

/** Task T1.08 (decision D03): after a backup restore, auto-send emails is switched off. */
class SettingsRestoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val file: File by lazy { SettingsRestore.settingsFile(tempFolder.root) }

    private val autoSendOn =
        AppSettings(
            reportTime = LocalTime.of(7, 30),
            caps = Caps(overallDailyApplies = 5, defaultPortalDailyApplies = 2, dailyEmails = 3),
            ai = AiSettings(AiProvider.OPENAI, mapOf(AiTask.SCORE to "fake-model-small")),
            toggles = FeatureToggles(autoSendEmails = true, emailDigest = true),
        )

    private fun write(settings: SettingsProto) {
        file.parentFile?.mkdirs()
        file.writeBytes(settings.toByteArray())
    }

    private fun read(): SettingsProto = SettingsProto.parseFrom(file.readBytes())

    @Test
    fun settingsFileIsWhereTheDataStoreKeepsIt() {
        assertEquals(File(tempFolder.root, "datastore/settings.pb"), file)
    }

    @Test
    fun autoSendOnIsSwitchedOffAndNothingElseChanges() {
        write(autoSendOn.toProto())

        assertEquals(AutoSendReset.DISABLED, SettingsRestore.disableAutoSendAfterRestore(file))

        val expected = autoSendOn.copy(toggles = autoSendOn.toggles.copy(autoSendEmails = false))
        assertEquals(expected, read().toDomain())
        assertArrayEquals(expected.toProto().toByteArray(), file.readBytes())
    }

    @Test
    fun fieldsUnknownToThisVersionAreKept() {
        // Field 99 stands for a setting added by a newer app version before a downgrade and restore.
        val unknownField = byteArrayOf(0x98.toByte(), 0x06, 0x01)
        val withUnknown = autoSendOn.toProto().toByteString().concat(ByteString.copyFrom(unknownField))
        file.parentFile?.mkdirs()
        file.writeBytes(withUnknown.toByteArray())

        assertEquals(AutoSendReset.DISABLED, SettingsRestore.disableAutoSendAfterRestore(file))

        val bytes = file.readBytes()
        assertArrayEquals(unknownField, bytes.copyOfRange(bytes.size - unknownField.size, bytes.size))
        assertFalse(read().toggles.autoSendEmails)
    }

    @Test
    fun autoSendAlreadyOffOrNeverSetIsNotRewritten() {
        val cases =
            listOf(
                "off" to AppSettings().toProto(),
                "never set" to SettingsProto.newBuilder().setSchemaVersion(SETTINGS_SCHEMA_VERSION).build(),
            )
        for ((name, settings) in cases) {
            write(settings)
            file.setLastModified(LAST_MODIFIED)

            assertEquals(name, AutoSendReset.ALREADY_OFF, SettingsRestore.disableAutoSendAfterRestore(file))
            assertArrayEquals(name, settings.toByteArray(), file.readBytes())
            assertEquals(name, LAST_MODIFIED, file.lastModified())
        }
    }

    @Test
    fun aMissingFileStaysMissing() {
        assertEquals(AutoSendReset.NO_FILE, SettingsRestore.disableAutoSendAfterRestore(file))
        assertFalse(file.exists())
        assertFalse("no datastore directory is created", file.parentFile?.exists() ?: false)
    }

    @Test
    fun aCorruptFileIsLeftAsIs() {
        val corrupt = byteArrayOf(0x0A, 0x7F, 0xFF.toByte(), 0x00, 0x13, 0x37)
        file.parentFile?.mkdirs()
        file.writeBytes(corrupt)

        assertEquals(AutoSendReset.UNREADABLE, SettingsRestore.disableAutoSendAfterRestore(file))
        assertArrayEquals(corrupt, file.readBytes())
    }

    @Test
    fun theWriteLeavesNoTemporaryFileBehind() {
        write(autoSendOn.toProto())

        SettingsRestore.disableAutoSendAfterRestore(file)

        assertEquals(listOf(SETTINGS_FILE_NAME), file.parentFile?.list()?.toList())
    }

    @Test
    fun aFailedWriteDeletesTheFileSoTheDefaultsApply() {
        write(autoSendOn.toProto())
        // A directory where the temporary file would go makes the write fail.
        val blocker = File(file.parentFile, "$SETTINGS_FILE_NAME.restore.tmp")
        assertTrue(blocker.mkdir())

        assertEquals(AutoSendReset.DELETED, SettingsRestore.disableAutoSendAfterRestore(file))
        assertFalse(file.exists())
    }

    private companion object {
        const val LAST_MODIFIED = 1_000_000_000_000L
    }
}
