package com.coderarchitech.jobassistant.sources.normalize

import java.text.Normalizer
import java.util.Locale

/** Company names as people write them, and as a key for matching the same company across sources. */
object CompanyNames {
    /** Legal-form words that say nothing about which company it is. Matched as whole words at the end. */
    private val SUFFIXES =
        setOf(
            "inc",
            "incorporated",
            "llc",
            "llp",
            "lp",
            "ltd",
            "limited",
            "pvt",
            "private",
            "corp",
            "corporation",
            "co",
            "company",
            "gmbh",
            "ag",
            "sa",
            "sas",
            "bv",
            "nv",
            "plc",
            "pte",
            "pty",
            "srl",
            "oy",
            "ab",
            "as",
        )
    private val SPACES = Regex("\\s+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{M}+")

    /** The name trimmed with runs of spaces collapsed, for display. */
    fun clean(name: String): String = name.trim().replace(SPACES, " ")

    /**
     * A key that is the same for `Stripe, Inc.`, `stripe inc` and `STRIPE`, or `Acme Technologies Pvt. Ltd.`
     * and `Acme Technologies`: lower case, accents and punctuation removed, `&` read as `and`, a leading
     * `The` and trailing legal forms dropped. A name made only of legal-form words keeps them.
     */
    fun key(name: String): String {
        val folded =
            Normalizer
                // Dropping full stops first turns `S.A.` into `sa` and `Pvt.` into `pvt`.
                .normalize(name.lowercase(Locale.ROOT).replace(".", "").replace("&", " and "), Normalizer.Form.NFD)
                .replace(MARKS, "")
        val words = folded.split(NOT_WORD).filter { it.isNotEmpty() }.toMutableList()
        if (words.size > 1 && words.first() == "the") words.removeAt(0)
        while (words.size > 1 && words.last() in SUFFIXES) words.removeAt(words.lastIndex)
        return words.joinToString(" ")
    }
}
