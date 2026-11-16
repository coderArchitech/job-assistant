package com.coderarchitech.jobassistant.core.data.settings

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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalTime

class SettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val file: File by lazy { File(tempFolder.root, SETTINGS_FILE_NAME) }

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
    ) {
        private val scope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler) + Job())
        val repository: SettingsRepository = DataStoreSettingsRepository(createSettingsDataStore(scope) { file })

        /** Ends the DataStore's scope so another store may open the same file. */
        suspend fun close() = scope.coroutineContext.job.cancelAndJoin()
    }

    private fun TestScope.open() = Store(this, file)

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
    fun anUnreadableFileEmitsTheDefaultsInsteadOfFailing() =
        runTest {
            // A directory where the file should be makes every read throw an IOException.
            check(file.mkdirs())
            val store = open()
            assertEquals(AppSettings(), store.repository.settings.first())
            store.close()
        }
}
