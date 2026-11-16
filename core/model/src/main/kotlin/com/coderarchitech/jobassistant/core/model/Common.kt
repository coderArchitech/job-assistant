package com.coderarchitech.jobassistant.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.YearMonth

/** How a job is worked: on site, hybrid, fully remote, or not stated. */
@Serializable
enum class WorkMode { REMOTE, HYBRID, ONSITE, UNKNOWN }

/** How often a salary amount is paid. */
@Serializable
enum class SalaryPeriod { YEAR, MONTH, HOUR }

/**
 * A salary range in whole currency units, for example INR per year for "12–18 LPA"
 * (1_200_000 to 1_800_000). Either bound may be missing when the posting gives only one.
 */
@Serializable
data class Salary(
    val min: Long?,
    val max: Long?,
    /** ISO 4217 code, such as "INR" or "USD". */
    val currency: String,
    val period: SalaryPeriod = SalaryPeriod.YEAR,
) {
    init {
        require(min != null || max != null) { "A salary needs at least one bound" }
        require(min == null || min >= 0) { "Salary min must not be negative" }
        require(min == null || max == null || min <= max) { "Salary min must not exceed max" }
        require(currency.length == ISO_CURRENCY_LENGTH) { "Currency must be an ISO 4217 code" }
    }

    private companion object {
        const val ISO_CURRENCY_LENGTH = 3
    }
}

/** Seniority levels used by preferences and the seniority hard filter. */
@Serializable
enum class Seniority { INTERN, JUNIOR, MID, SENIOR, STAFF, PRINCIPAL, MANAGER, DIRECTOR }

/** Stores a [YearMonth] as "YYYY-MM" in JSON columns. */
object YearMonthSerializer : KSerializer<YearMonth> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("YearMonth", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: YearMonth,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): YearMonth = YearMonth.parse(decoder.decodeString())
}
