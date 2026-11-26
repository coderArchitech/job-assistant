package com.coderarchitech.jobassistant.sources.normalize

import com.coderarchitech.jobassistant.core.model.Salary
import com.coderarchitech.jobassistant.core.model.SalaryPeriod
import java.math.BigDecimal
import java.util.Locale

/**
 * Reads a salary written by a person into a [Salary]: `$90,000 - $120,000`, `$57K - $67K`, `€60k–€75k`,
 * `$14 - $20/hour`, `$3.5k-$5k/month`, `12–18 LPA`, `₹12L - ₹18L`, `INR 12,00,000 - 18,00,000` or
 * `15-25 Lacs P.A.`.
 *
 * It returns null rather than guess: there must be a currency (a symbol or code, or an Indian unit such as
 * `LPA`, `lakh` or `crore`, which means rupees), and an amount. One figure is both bounds, unless it is
 * written `up to X` (a maximum) or `from X` / `X+` (a minimum).
 */
object SalaryParser {
    /** Words that mark a sentence of a description as being about pay. */
    private val PAY_WORDS =
        Regex(
            "\\b(salary|salaries|compensation|pay range|base pay|pay rate|hourly rate|ctc|package|stipend|" +
                "remuneration|ote|wage)\\b",
            RegexOption.IGNORE_CASE,
        )

    // Longest first, so "us$" wins over "$" and "rs." over "rs".
    private val CURRENCIES =
        listOf(
            "us$" to "USD",
            "c$" to "CAD",
            "ca$" to "CAD",
            "a$" to "AUD",
            "au$" to "AUD",
            "s$" to "SGD",
            "usd" to "USD",
            "cad" to "CAD",
            "aud" to "AUD",
            "sgd" to "SGD",
            "eur" to "EUR",
            "gbp" to "GBP",
            "inr" to "INR",
            "rs." to "INR",
            "rs" to "INR",
            "$" to "USD",
            "€" to "EUR",
            "£" to "GBP",
            "₹" to "INR",
        )

    private val AMOUNT =
        Regex(
            "(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?)\\s*" +
                "(k|m|mn|l|lakhs?|lacs?|lpa|cr|crores?)?(?![\\p{L}])",
            RegexOption.IGNORE_CASE,
        )
    private val GROUPED = Regex("\\d{1,3}(,\\d{2,3})*,\\d{3}(\\.\\d+)?")
    private val DASHES = Regex("[\\u2010-\\u2015\\u2212\\uFFFD~]")
    private val RANGE_WORD = Regex("\\s+(to|and)\\s+", RegexOption.IGNORE_CASE)

    private val HOUR = Regex("(/|per |an |a )\\s*(hour|hr)\\b|\\bhourly\\b|/\\s*h\\b", RegexOption.IGNORE_CASE)
    private val MONTH = Regex("(/|per |a )\\s*(month|mo)\\b|\\bmonthly\\b|\\bp\\.?m\\.?$", RegexOption.IGNORE_CASE)
    private val UP_TO = Regex("\\b(up to|upto|max(imum)?|not more than)\\b", RegexOption.IGNORE_CASE)
    private val FROM =
        Regex("\\b(from|min(imum)?|at least|starting( at)?)\\b|\\d\\s*[kKlL]?\\s*\\+", RegexOption.IGNORE_CASE)

    private const val THOUSAND = 1_000L
    private const val MILLION = 1_000_000L
    private const val LAKH = 100_000L
    private const val CRORE = 10_000_000L

    /** A salary field such as a source's `compensation`, or null when it names no amount and currency. */
    @Suppress("ReturnCount") // Each early exit is a reason the text is no salary.
    fun parse(text: String?): Salary? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val line = raw.replace(DASHES, "-").replace(RANGE_WORD, " - ")
        val lower = line.lowercase(Locale.ROOT)
        val amounts =
            AMOUNT
                .findAll(
                    line,
                ).map { Amount(it.groupValues[1], it.groupValues[2].lowercase(Locale.ROOT)) }
                .toList()
        if (amounts.isEmpty()) return null
        val indianUnit = amounts.any { it.unit in INDIAN_UNITS }
        val currency = currencyOf(lower) ?: if (indianUnit) "INR" else return null
        val values = resolve(amounts.take(2))
        if (values.isEmpty() || values.any { it <= 0 }) return null
        val period =
            when {
                HOUR.containsMatchIn(line) -> SalaryPeriod.HOUR
                MONTH.containsMatchIn(line) -> SalaryPeriod.MONTH
                else -> SalaryPeriod.YEAR
            }
        return toSalary(values, raw, currency, period)
    }

    /**
     * A salary stated in a posting's description: the first sentence or line that talks about pay and
     * parses. Text about anything else with money in it (a loan size, a funding round) is ignored.
     */
    fun findIn(description: String?): Salary? =
        description
            ?.split('\n', '.', ';')
            ?.asSequence()
            ?.filter { PAY_WORDS.containsMatchIn(it) }
            ?.firstNotNullOfOrNull { parse(it) }

    private fun toSalary(
        values: List<Long>,
        line: String,
        currency: String,
        period: SalaryPeriod,
    ): Salary {
        if (values.size >= 2) return Salary(minOf(values[0], values[1]), maxOf(values[0], values[1]), currency, period)
        val only = values.single()
        return when {
            UP_TO.containsMatchIn(line) -> Salary(null, only, currency, period)
            FROM.containsMatchIn(line) -> Salary(only, null, currency, period)
            else -> Salary(only, only, currency, period)
        }
    }

    private fun currencyOf(lower: String): String? =
        CURRENCIES.firstOrNull { (mark, _) -> hasMark(lower, mark) }?.second

    /** Letter codes must stand alone (`usd`, not part of a word); symbols match anywhere. */
    private fun hasMark(
        lower: String,
        mark: String,
    ): Boolean =
        if (mark.first().isLetter()) {
            Regex("(?<![\\p{L}])${Regex.escape(mark)}(?![\\p{L}])").containsMatchIn(lower)
        } else {
            mark in lower
        }

    /** Whole currency units. In `12-18 LPA` and `$57-67K` the unit written on the second applies to both. */
    private fun resolve(amounts: List<Amount>): List<Long> {
        val units = amounts.map { it.unit }.toMutableList()
        if (amounts.size == 2 && units[0].isEmpty() && units[1].isNotEmpty()) {
            val first = amounts[0].number
            val second = amounts[1].number
            if (first <= second) units[0] = units[1]
        }
        return amounts.mapIndexed { i, amount -> amount.number.multiply(BigDecimal(multiplier(units[i]))).toLong() }
    }

    private fun multiplier(unit: String): Long =
        when (unit) {
            "k" -> THOUSAND
            "m", "mn" -> MILLION
            "cr", "crore", "crores" -> CRORE
            in INDIAN_UNITS -> LAKH
            else -> 1
        }

    private val INDIAN_UNITS = setOf("l", "lakh", "lakhs", "lac", "lacs", "lpa", "cr", "crore", "crores")

    private class Amount(
        text: String,
        val unit: String,
    ) {
        /** `1,20,000` and `120,000` are grouped thousands; `35,3` (as in `35,3k`) is a decimal comma. */
        val number: BigDecimal =
            if (GROUPED.matches(text)) BigDecimal(text.replace(",", "")) else BigDecimal(text.replace(',', '.'))
    }
}
