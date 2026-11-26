package com.coderarchitech.jobassistant.sources.normalize

import com.coderarchitech.jobassistant.core.model.Salary
import com.coderarchitech.jobassistant.core.model.SalaryPeriod
import java.math.BigDecimal
import java.util.Locale

/**
 * Reads a salary written by a person into a [Salary]: `$90,000 - $120,000`, `$57K - $67K`, `€60k–€75k`,
 * `$14 - $20/hour`, `$3.5k-$5k/month`, `12–18 LPA`, `₹12L - ₹18L`, `INR 12,00,000 - 18,00,000`,
 * `15-25 Lacs P.A.`, and Lever's one-sided `USD -150000 per year`.
 *
 * It returns null rather than guess:
 * - there must be a currency (a symbol or code, or an Indian unit such as `LPA`, `lakh` or `crore`, which
 *   means rupees);
 * - only an amount next to the currency or with an Indian unit counts, so `10% bonus`, `401k` or `2026`
 *   beside a salary is not read as one, and a range is two amounts joined by a dash or `to`;
 * - a daily or weekly rate is null, because a [Salary] is paid by the hour, month or year.
 *
 * One figure is both bounds, unless it is written `up to X` or `-X` (a maximum) or `from X`, `X+` or `X-`
 * (a minimum).
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
            "ca$" to "CAD",
            "au$" to "AUD",
            "c$" to "CAD",
            "a$" to "AUD",
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
    private val SYMBOLS = setOf('$', '€', '£', '₹')
    private val CODE_AFTER = Regex("^\\s*(usd|cad|aud|sgd|eur|gbp|inr)\\b", RegexOption.IGNORE_CASE)
    private val CODE_BEFORE = Regex("\\b(usd|cad|aud|sgd|eur|gbp|inr|rs\\.?)\\s*-?\\s*$", RegexOption.IGNORE_CASE)

    private val AMOUNT =
        Regex(
            "(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d+)?|\\d+(?:[.,]\\d+)?)\\s*" +
                "(k|m|mn|l|lakhs?|lacs?|lpa|cr|crores?)?(?![\\p{L}\\d])",
            RegexOption.IGNORE_CASE,
        )
    private val GROUPED = Regex("\\d{1,3}(,\\d{2,3})*,\\d{3}(\\.\\d+)?")
    private val DASHES = Regex("[\\u2010-\\u2015\\u2212\\uFFFD~]")
    private val RANGE_WORD = Regex("(?<=[\\dkKlL])\\s+(to|and)\\s+(?=\\D{0,6}\\d)", RegexOption.IGNORE_CASE)

    /** What may stand between the two amounts of a range: a dash and perhaps a currency mark. */
    private val RANGE_GAP =
        Regex(
            "^\\s*-\\s*(?:us\\$|[a-z]{1,2}\\$|usd|cad|aud|sgd|eur|gbp|inr|rs\\.?|[$€£₹])?\\s*$",
            RegexOption.IGNORE_CASE,
        )

    private val HOUR = Regex("(/|per |an |a )\\s*(hour|hr)\\b|\\bhourly\\b|/\\s*h\\b", RegexOption.IGNORE_CASE)
    private val MONTH = Regex("(/|per |a )\\s*(month|mo)\\b|\\bmonthly\\b|\\bp\\.?m\\.?$", RegexOption.IGNORE_CASE)
    private val DAY_OR_WEEK =
        Regex("(/|per |a )\\s*(day|week|wk)\\b|\\b(daily|weekly)\\b|\\bday rate\\b", RegexOption.IGNORE_CASE)
    private val UP_TO =
        Regex("\\b(up to|upto|max(imum)?|not more than)\\b|^\\D*-\\s*\\D{0,4}\\d", RegexOption.IGNORE_CASE)
    private val FROM =
        Regex(
            "\\b(from|min(imum)?|at least|starting( at)?)\\b|\\d\\s*[kKlL]?\\+(?!\\s*\\w)|\\d\\s*[kKlL]?\\s*-\\D*$",
            RegexOption.IGNORE_CASE,
        )
    private val SENTENCE_END = Regex("\\n|;|\\.\\s+(?=[A-Z])")

    private const val THOUSAND = 1_000L
    private const val MILLION = 1_000_000L
    private const val LAKH = 100_000L
    private const val CRORE = 10_000_000L

    /** How many characters before an amount are searched for its currency mark. */
    private const val NEAR = 6
    private val INDIAN_UNITS = setOf("l", "lakh", "lakhs", "lac", "lacs", "lpa", "cr", "crore", "crores")

    /** A salary field such as a source's `compensation`, or null when it names no amount and currency. */
    @Suppress("ReturnCount") // Each early exit is a reason the text is no salary.
    fun parse(text: String?): Salary? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (DAY_OR_WEEK.containsMatchIn(raw)) return null
        val line = raw.replace(DASHES, "-").replace(RANGE_WORD, " - ")
        val amounts = AMOUNT.findAll(line).map { Amount(it, line) }.toList()
        val indianUnit = amounts.any { it.unit in INDIAN_UNITS }
        val currency = currencyOf(line.lowercase(Locale.ROOT)) ?: if (indianUnit) "INR" else return null
        val picked = pick(amounts, line) ?: return null
        val values = resolve(picked)
        if (values.any { it <= 0 }) return null
        val period =
            when {
                HOUR.containsMatchIn(line) -> SalaryPeriod.HOUR
                MONTH.containsMatchIn(line) -> SalaryPeriod.MONTH
                else -> SalaryPeriod.YEAR
            }
        return toSalary(values, line, currency, period)
    }

    /**
     * A salary stated in a posting's description: the first line or sentence that talks about pay and
     * parses. Text about anything else with money in it (a loan size, a funding round) is ignored. A full
     * stop ends a sentence only before a space and a capital, so `$3.5k` and `Rs. 12 LPA` stay whole.
     */
    fun findIn(description: String?): Salary? =
        description
            ?.split(SENTENCE_END)
            ?.asSequence()
            ?.filter { PAY_WORDS.containsMatchIn(it) }
            ?.firstNotNullOfOrNull { parse(it) }

    /**
     * The first amount that is money, and the one joined to it by a dash when there is one: the amount before
     * it (`12-18 LPA`) or after it (`$90,000 - $120,000`).
     */
    private fun pick(
        amounts: List<Amount>,
        line: String,
    ): List<Amount>? {
        val first = amounts.indexOfFirst { it.isMoney }
        if (first < 0) return null
        val joined = { a: Amount, b: Amount -> RANGE_GAP.matches(line.substring(a.end, b.start)) }
        val before = amounts.getOrNull(first - 1)
        val after = amounts.getOrNull(first + 1)
        return when {
            before != null && joined(before, amounts[first]) -> listOf(before, amounts[first])
            after != null && joined(amounts[first], after) -> listOf(amounts[first], after)
            else -> listOf(amounts[first])
        }
    }

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
        val shared = amounts.size == 2 && units[0].isEmpty() && units[1].isNotEmpty()
        if (shared && amounts[0].number <= amounts[1].number) units[0] = units[1]
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

    private class Amount(
        match: MatchResult,
        line: String,
    ) {
        val start = match.range.first
        val end = match.range.last + 1
        val unit = match.groupValues[2].lowercase(Locale.ROOT)

        /** `1,20,000` and `120,000` are grouped thousands; `35,3` (as in `35,3k`) is a decimal comma. */
        val number: BigDecimal =
            match.groupValues[1].let { text ->
                if (GROUPED.matches(text)) BigDecimal(text.replace(",", "")) else BigDecimal(text.replace(',', '.'))
            }

        /** A currency mark right before or a code right after it, or an Indian unit; never a percentage. */
        val isMoney: Boolean =
            run {
                val before = line.substring(maxOf(0, start - NEAR), start)
                val after = line.substring(end)
                val percent = after.trimStart().startsWith("%")
                val marked =
                    before.any { it in SYMBOLS } ||
                        CODE_BEFORE.containsMatchIn(before) ||
                        CODE_AFTER.containsMatchIn(after)
                !percent && (marked || unit in INDIAN_UNITS)
            }
    }
}
