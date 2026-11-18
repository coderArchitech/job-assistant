package com.coderarchitech.jobassistant.core.data.settings

import com.coderarchitech.jobassistant.core.data.settings.proto.AiSettingsProto
import com.coderarchitech.jobassistant.core.data.settings.proto.BudgetProto
import com.coderarchitech.jobassistant.core.data.settings.proto.CapsProto
import com.coderarchitech.jobassistant.core.data.settings.proto.ScheduleProto
import com.coderarchitech.jobassistant.core.data.settings.proto.SettingsProto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/** T1.09 (note from T1.06): an invalid stored setting is logged by field name, never by value. */
class SettingsFallbackLogTest {
    private val lines = mutableListOf<String>()

    @Before
    fun plant() {
        Timber.plant(
            object : Timber.Tree() {
                override fun log(
                    priority: Int,
                    tag: String?,
                    message: String,
                    t: Throwable?,
                ) {
                    lines += message
                }
            },
        )
    }

    @After
    fun uproot() = Timber.uprootAll()

    @Test
    fun invalidStoredValuesAreLoggedByFieldNameOnly() {
        val proto =
            SettingsProto
                .newBuilder()
                .setCaps(CapsProto.newBuilder().setDailyEmails(-3))
                .setBudget(BudgetProto.newBuilder().setMonthlyCapMicros(-1))
                .setDefaultSchedule(ScheduleProto.newBuilder().setInterval("EVERY_MINUTE"))
                .setAi(
                    AiSettingsProto
                        .newBuilder()
                        .setProvider("SOMEONE_ELSE")
                        .putModelOverrides("SCORE", "sk-ant-api03-FAKEFAKEFAKEFAKE"),
                ).setReportMinuteOfDay(99_999)
                .build()

        proto.toDomain()

        val logged = lines.joinToString("\n")
        for (field in listOf(
            "caps.dailyEmails",
            "budget.monthlyCapMicros",
            "defaultSchedule.interval",
            "ai.provider",
            "ai.modelOverrides",
            "reportTime",
        )) {
            assertTrue("No log for $field in:\n$logged", logged.contains(field))
        }
        for (value in listOf("-3", "EVERY_MINUTE", "SOMEONE_ELSE", "sk-ant", "FAKEFAKE", "99999")) {
            assertFalse("Logged the value $value:\n$logged", logged.contains(value))
        }
    }

    @Test
    fun absentFieldsAreNotLogged() {
        SettingsProto.getDefaultInstance().toDomain()
        assertEquals(emptyList<String>(), lines)
    }
}
