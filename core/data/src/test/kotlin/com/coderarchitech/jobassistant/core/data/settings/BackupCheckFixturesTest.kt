package com.coderarchitech.jobassistant.core.data.settings

import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiSettings
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.model.AppSettings
import com.coderarchitech.jobassistant.core.model.Caps
import com.coderarchitech.jobassistant.core.model.FeatureToggles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalTime

/**
 * Keeps the `settings.pb` fixtures of `scripts/ci/backup_restore_check.sh` (task T1.08) in step
 * with the code. The script plants `settings-autosend-on.pb`, runs a backup and restore, and expects
 * the restored file to equal `settings-autosend-off.pb` byte for byte.
 *
 * To regenerate both files after a schema change, run this test with
 * `JOBASSISTANT_REGENERATE_FIXTURES=1` (and `--rerun`, so Gradle does not skip it) and commit the result.
 */
class BackupCheckFixturesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fixtureDir =
        File(
            requireNotNull(System.getProperty(FIXTURE_DIR_PROPERTY)) {
                "$FIXTURE_DIR_PROPERTY is set by core/data/build.gradle.kts"
            },
        )

    /** Non-default values in several groups, so a restore that drops fields would show. Fake ids only. */
    private val planted =
        AppSettings(
            reportTime = LocalTime.of(8, 45),
            caps = Caps(overallDailyApplies = 7, defaultPortalDailyApplies = 3, dailyEmails = 4),
            ai = AiSettings(AiProvider.ANTHROPIC, mapOf(AiTask.SCORE to "fake-model-small")),
            toggles = FeatureToggles(autoSendEmails = true, emailDigest = true),
        )

    private val expectedOn = planted.toProto().toByteArray()
    private val expectedOff =
        planted.copy(toggles = planted.toggles.copy(autoSendEmails = false)).toProto().toByteArray()

    @Test
    fun fixturesMatchTheCurrentSchemaAndRestoreBehavior() {
        val on = File(fixtureDir, ON_FIXTURE)
        val off = File(fixtureDir, OFF_FIXTURE)
        if (System.getenv(REGENERATE_ENV) == "1") {
            on.writeBytes(expectedOn)
            off.writeBytes(expectedOff)
        }
        val hint =
            "regenerate with $REGENERATE_ENV=1 ./gradlew :core:data:testDebugUnitTest --rerun " +
                "--tests '*BackupCheckFixturesTest'"
        assertTrue("$on is missing; $hint", on.isFile && off.isFile)
        assertArrayEquals("$ON_FIXTURE is out of date; $hint", expectedOn, on.readBytes())
        assertArrayEquals("$OFF_FIXTURE is out of date; $hint", expectedOff, off.readBytes())

        // What the backup agent does on the device must turn the one fixture into the other.
        val restored = SettingsRestore.settingsFile(tempFolder.root)
        restored.parentFile?.mkdirs()
        on.copyTo(restored)
        assertEquals(AutoSendReset.DISABLED, SettingsRestore.disableAutoSendAfterRestore(restored))
        assertArrayEquals(off.readBytes(), restored.readBytes())
    }

    private companion object {
        const val FIXTURE_DIR_PROPERTY = "jobassistant.backupFixtureDir"
        const val REGENERATE_ENV = "JOBASSISTANT_REGENERATE_FIXTURES"
        const val ON_FIXTURE = "settings-autosend-on.pb"
        const val OFF_FIXTURE = "settings-autosend-off.pb"
    }
}
