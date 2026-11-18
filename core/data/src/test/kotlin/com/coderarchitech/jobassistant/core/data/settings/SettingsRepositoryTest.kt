package com.coderarchitech.jobassistant.core.data.settings

import com.coderarchitech.jobassistant.core.data.settings.proto.SettingsProto
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiSettings
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.model.AppSettings
import com.coderarchitech.jobassistant.core.model.Caps
import com.coderarchitech.jobassistant.core.model.NetworkRequirement
import com.coderarchitech.jobassistant.core.model.RunInterval
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.TierThresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalTime

class SettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val file: File by lazy { File(tempFolder.root, SETTINGS_FILE_NAME) }

    /** The consent marker, in a separate directory like `noBackupFilesDir` on a device. */
    private val marker: File by lazy { File(File(tempFolder.root, "no_backup"), AUTO_SEND_CONSENT_FILE_NAME) }

    private val changed =
        AppSettings(
            thresholds = TierThresholds(high = 80, partial = 55),
            defaultSchedule = RunSchedule(RunInterval.EVERY_3_HOURS, NetworkRequirement.UNMETERED, chargingOnly = true),
            reportTime = LocalTime.of(18, 15),
            caps = Caps(overallDailyApplies = 0, defaultPortalDailyApplies = 4, dailyEmails = 2),
            ai = AiSettings(AiProvider.ANTHROPIC, mapOf(AiTask.SCORE to "fake-model-small")),
        )

    /** One app process: a repository over its own DataStore, closed by [close]. */
    private class Store(
        testScope: TestScope,
        file: File,
        marker: File,
    ) {
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        private val scope = CoroutineScope(dispatcher + Job())
        val repository: SettingsRepository =
            DataStoreSettingsRepository(
                dataStore = createSettingsDataStore(scope) { file },
                consentMarker = AutoSendConsentMarker { marker },
                ioDispatcher = dispatcher,
            )

        /** Ends the DataStore's scope so another store may open the same file. */
        suspend fun close() = scope.coroutineContext.job.cancelAndJoin()
    }

    private fun TestScope.open() = Store(this, file, marker)

    @Test
    fun aFreshStoreEmitsTheDefaults() =
        runTest {
            val store = open()
            assertEquals(AppSettings(), store.repository.settings.first())
            store.close()
        }

    @Test
    fun updateReturnsAndEmitsTheNewValue() =
        runTest {
            val store = open()
            val saved = store.repository.update { changed }
            assertEquals(changed, saved)
            assertEquals(changed, store.repository.settings.first())

            val next = store.repository.update { it.copy(toggles = it.toggles.copy(emailDigest = true)) }
            assertEquals(changed.copy(toggles = changed.toggles.copy(emailDigest = true)), next)
            store.close()
        }

    @Test
    fun doneWhen_settingsSurviveAnAppRestart() =
        runTest {
            val first = open()
            first.repository.update { changed }
            first.close()

            val second = open()
            assertEquals(changed, second.repository.settings.first())
            second.close()
        }

    @Test
    fun resetRestoresTheDefaultsAndSurvivesARestart() =
        runTest {
            val first = open()
            first.repository.update { changed }
            first.repository.reset()
            assertEquals(AppSettings(), first.repository.settings.first())
            first.close()

            val second = open()
            assertEquals(AppSettings(), second.repository.settings.first())
            second.close()
        }

    @Test
    fun aTransformThatThrowsWritesNothing() =
        runTest {
            val first = open()
            first.repository.update { changed }
            val failures: List<Pair<String, (AppSettings) -> AppSettings>> =
                listOf(
                    "negative cap" to { it.copy(caps = it.caps.copy(dailyEmails = -1)) },
                    "partial above high" to { it.copy(thresholds = TierThresholds(high = 40, partial = 60)) },
                    "blank override" to { it.copy(ai = it.ai.copy(modelOverrides = mapOf(AiTask.TAILOR to " "))) },
                )
            for ((name, transform) in failures) {
                var thrown: Throwable? = null
                try {
                    first.repository.update(transform)
                } catch (e: IllegalArgumentException) {
                    thrown = e
                }
                assertEquals("$name throws", IllegalArgumentException::class.java, thrown?.javaClass)
                assertEquals("$name leaves settings", changed, first.repository.settings.first())
            }
            first.close()

            val second = open()
            assertEquals(changed, second.repository.settings.first())
            second.close()
        }

    @Test
    fun aCorruptFileReadsAsDefaultsAndStaysWritable() =
        runTest {
            file.writeBytes(byteArrayOf(0x0A, 0x7F, 0xFF.toByte(), 0x00, 0x13, 0x37))

            val first = open()
            assertEquals(AppSettings(), first.repository.settings.first())
            assertEquals(changed, first.repository.update { changed })
            first.close()

            val second = open()
            assertEquals(changed, second.repository.settings.first())
            second.close()
        }

    @Test
    fun aFileThatStaysUnreadableFailsTheFlowInsteadOfInventingDefaults() =
        runTest {
            // A directory where the file should be makes every read throw an IOException.
            check(file.mkdirs())
            val store = open()
            // runTest skips the retry delays in virtual time.
            val error =
                try {
                    store.repository.settings.first()
                    null
                } catch (e: IOException) {
                    e
                }
            assertNotNull("Reading should fail after the retries", error)
            store.close()
        }

    @Test
    fun aTransientReadErrorIsRetriedAndThenReadsTheStoredValue() =
        runTest {
            val writer = open()
            writer.repository.update { changed }
            writer.close()

            // Hide the file behind a directory, start reading, then put the file back.
            val saved = file.readBytes()
            check(file.delete() && file.mkdirs())
            val store = open()
            val read = async { store.repository.settings.first() }
            runCurrent()
            check(file.delete())
            file.writeBytes(saved)
            advanceUntilIdle()
            assertEquals(changed, read.await())
            store.close()
        }

    // Auto-send emails: the consent lives only in the no-backup marker (task T1.08, decision D03).

    private val autoSendOn: (AppSettings) -> AppSettings = { it.copy(toggles = it.toggles.copy(autoSendEmails = true)) }
    private val autoSendOff: (
        AppSettings,
    ) -> AppSettings = { it.copy(toggles = it.toggles.copy(autoSendEmails = false)) }

    /** What `settings.pb` says about auto-send, read without the repository. */
    private fun storedAutoSend(): Boolean =
        file
            .inputStream()
            .use { SettingsProto.parseFrom(it) }
            .toggles.autoSendEmails

    @Test
    fun autoSendIsOffByDefaultWithNoMarker() =
        runTest {
            val store = open()
            assertFalse(
                store.repository.settings
                    .first()
                    .toggles.autoSendEmails,
            )
            assertFalse(marker.exists())
            store.close()
        }

    @Test
    fun enablingAutoSendCreatesTheMarkerAndKeepsSettingsPbOff() =
        runTest {
            val store = open()
            // Change something else too, so DataStore writes settings.pb (it skips unchanged values).
            val saved = store.repository.update { autoSendOn(changed) }
            assertEquals(autoSendOn(changed), saved)
            assertTrue(saved.toggles.autoSendEmails)
            assertTrue(
                store.repository.settings
                    .first()
                    .toggles.autoSendEmails,
            )
            assertTrue("marker created", marker.isFile)
            assertFalse("settings.pb, which is backed up, never stores auto-send on", storedAutoSend())
            store.close()
        }

    @Test
    fun disablingAutoSendDeletesTheMarker() =
        runTest {
            val store = open()
            store.repository.update(autoSendOn)
            val saved = store.repository.update(autoSendOff)
            assertFalse(saved.toggles.autoSendEmails)
            assertFalse(
                store.repository.settings
                    .first()
                    .toggles.autoSendEmails,
            )
            assertFalse("marker deleted", marker.exists())
            store.close()
        }

    @Test
    fun doneWhen_aRestoredSettingsPbWithAutoSendOnButNoMarkerReadsAsOff() =
        runTest {
            // A restore or device transfer brings settings.pb back but never no_backup/.
            val restored = changed.copy(toggles = changed.toggles.copy(autoSendEmails = true, emailDigest = true))
            file.writeBytes(restored.toProto().toByteArray())
            check(storedAutoSend())

            val store = open()
            val read = store.repository.settings.first()
            assertFalse("auto-send stays off after a restore", read.toggles.autoSendEmails)
            assertEquals("every other restored setting is kept", autoSendOff(restored), read)

            // Changing another setting does not turn it on either.
            val next = store.repository.update { it.copy(caps = it.caps.copy(dailyEmails = 1)) }
            assertFalse(next.toggles.autoSendEmails)
            assertFalse(marker.exists())
            store.close()
        }

    @Test
    fun aMarkerWithNoSettingsPbReadsAsOnWithDefaults() =
        runTest {
            check(marker.parentFile?.mkdirs() == true && marker.createNewFile())
            val store = open()
            assertEquals(autoSendOn(AppSettings()), store.repository.settings.first())
            store.close()
        }

    @Test
    fun resetDeletesTheMarker() =
        runTest {
            val store = open()
            store.repository.update { autoSendOn(changed) }
            store.repository.reset()
            assertEquals(AppSettings(), store.repository.settings.first())
            assertFalse(marker.exists())
            store.close()
        }

    @Test
    fun autoSendSurvivesARestartThroughTheMarker() =
        runTest {
            val first = open()
            first.repository.update { autoSendOn(changed) }
            first.close()

            val second = open()
            assertEquals(autoSendOn(changed), second.repository.settings.first())
            second.close()
        }

    @Test
    fun theFlowReEmitsWhenOnlyTheMarkerChanges() =
        runTest {
            val store = open()
            store.repository.update { changed }
            val emissions = mutableListOf<AppSettings>()
            val collector = launch { store.repository.settings.toList(emissions) }
            runCurrent()
            assertEquals(listOf(changed), emissions)

            store.repository.update(autoSendOn)
            runCurrent()
            store.repository.update(autoSendOff)
            runCurrent()
            assertEquals(listOf(changed, autoSendOn(changed), changed), emissions)
            collector.cancelAndJoin()
            store.close()
        }
}
