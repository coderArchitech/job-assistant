package com.coderarchitech.jobassistant.core.data.db

import androidx.room.TypeConverter
import com.coderarchitech.jobassistant.core.model.DealBreakers
import com.coderarchitech.jobassistant.core.model.Education
import com.coderarchitech.jobassistant.core.model.JobFilters
import com.coderarchitech.jobassistant.core.model.Preferences
import com.coderarchitech.jobassistant.core.model.Role
import com.coderarchitech.jobassistant.core.model.RunError
import com.coderarchitech.jobassistant.core.model.RunSchedule
import com.coderarchitech.jobassistant.core.model.RunStage
import com.coderarchitech.jobassistant.core.model.Skill
import com.coderarchitech.jobassistant.core.model.StandardAnswer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/**
 * Room type converters. Times are stored as epoch milliseconds (so sub-millisecond precision is
 * dropped), dates as ISO text, and lists, maps and value types as JSON through kotlinx.serialization.
 * Enums need no converter: Room stores them by constant name, so those names are a storage format
 * (see `StoredDbEnumNamesTest`).
 */
@Suppress("TooManyFunctions") // One pair per stored type; splitting them would only scatter the registry.
object Converters {
    /**
     * Older rows must keep decoding when a field is added (new fields have defaults), and a row
     * written by a newer app version (restored from backup) must not crash an older one.
     */
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val stringList = ListSerializer(String.serializer())
    private val longList = ListSerializer(Long.serializer())
    private val skillList = ListSerializer(Skill.serializer())
    private val roleList = ListSerializer(Role.serializer())
    private val educationList = ListSerializer(Education.serializer())
    private val answerList = ListSerializer(StandardAnswer.serializer())
    private val runErrorList = ListSerializer(RunError.serializer())
    private val stageCounts = MapSerializer(RunStage.serializer(), Int.serializer())
    private val stringIntMap = MapSerializer(String.serializer(), Int.serializer())
    private val longIntMap = MapSerializer(Long.serializer(), Int.serializer())
    private val stringMap = MapSerializer(String.serializer(), String.serializer())

    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): String = json.encodeToString(serializer, value)

    private fun <T> decode(
        serializer: KSerializer<T>,
        value: String,
    ): T = json.decodeFromString(serializer, value)

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter
    fun stringListToJson(value: List<String>): String = encode(stringList, value)

    @TypeConverter
    fun jsonToStringList(value: String): List<String> = decode(stringList, value)

    @TypeConverter
    fun longListToJson(value: List<Long>): String = encode(longList, value)

    @TypeConverter
    fun jsonToLongList(value: String): List<Long> = decode(longList, value)

    @TypeConverter
    fun skillsToJson(value: List<Skill>): String = encode(skillList, value)

    @TypeConverter
    fun jsonToSkills(value: String): List<Skill> = decode(skillList, value)

    @TypeConverter
    fun rolesToJson(value: List<Role>): String = encode(roleList, value)

    @TypeConverter
    fun jsonToRoles(value: String): List<Role> = decode(roleList, value)

    @TypeConverter
    fun educationToJson(value: List<Education>): String = encode(educationList, value)

    @TypeConverter
    fun jsonToEducation(value: String): List<Education> = decode(educationList, value)

    @TypeConverter
    fun answersToJson(value: List<StandardAnswer>): String = encode(answerList, value)

    @TypeConverter
    fun jsonToAnswers(value: String): List<StandardAnswer> = decode(answerList, value)

    @TypeConverter
    fun runErrorsToJson(value: List<RunError>): String = encode(runErrorList, value)

    @TypeConverter
    fun jsonToRunErrors(value: String): List<RunError> = decode(runErrorList, value)

    @TypeConverter
    fun preferencesToJson(value: Preferences): String = encode(Preferences.serializer(), value)

    @TypeConverter
    fun jsonToPreferences(value: String): Preferences = decode(Preferences.serializer(), value)

    @TypeConverter
    fun dealBreakersToJson(value: DealBreakers): String = encode(DealBreakers.serializer(), value)

    @TypeConverter
    fun jsonToDealBreakers(value: String): DealBreakers = decode(DealBreakers.serializer(), value)

    @TypeConverter
    fun jobFiltersToJson(value: JobFilters): String = encode(JobFilters.serializer(), value)

    @TypeConverter
    fun jsonToJobFilters(value: String): JobFilters = decode(JobFilters.serializer(), value)

    @TypeConverter
    fun runScheduleToJson(value: RunSchedule): String = encode(RunSchedule.serializer(), value)

    @TypeConverter
    fun jsonToRunSchedule(value: String): RunSchedule = decode(RunSchedule.serializer(), value)

    @TypeConverter
    fun stageCountsToJson(value: Map<RunStage, Int>): String = encode(stageCounts, value)

    @TypeConverter
    fun jsonToStageCounts(value: String): Map<RunStage, Int> = decode(stageCounts, value)

    @TypeConverter
    fun stringIntMapToJson(value: Map<String, Int>): String = encode(stringIntMap, value)

    @TypeConverter
    fun jsonToStringIntMap(value: String): Map<String, Int> = decode(stringIntMap, value)

    @TypeConverter
    fun longIntMapToJson(value: Map<Long, Int>): String = encode(longIntMap, value)

    @TypeConverter
    fun jsonToLongIntMap(value: String): Map<Long, Int> = decode(longIntMap, value)

    @TypeConverter
    fun stringMapToJson(value: Map<String, String>): String = encode(stringMap, value)

    @TypeConverter
    fun jsonToStringMap(value: String): Map<String, String> = decode(stringMap, value)
}
