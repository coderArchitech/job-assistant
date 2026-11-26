package com.coderarchitech.jobassistant.agent.scoring

import java.util.Locale

/**
 * Which place names include the user, for checking a posting's region limit such as `US only` or
 * `LATAM, Europe, APAC only`. Kept small and explicit: a name not listed here is taken as not the user's,
 * because a posting that says `X only` and names somewhere else almost always means it.
 */
internal object Places {
    /** A country and every name that includes it: itself, its abbreviations, its regions and time zones. */
    private class Country(
        val name: String,
        val timeZonePrefixes: List<String>,
        val names: Set<String>,
    )

    private val GLOBAL = setOf("worldwide", "anywhere", "global", "international")
    private val ASIA_PACIFIC = setOf("asia", "apac", "asia pacific", "asia-pacific", "emea and apac")
    private val EUROPE = setOf("europe", "eu", "european union", "emea", "eea", "cet", "cest", "european timezones")
    private val AMERICAS = setOf("americas", "north america", "us timezones", "usa timezones", "est", "pst", "et", "pt")

    private val US_STATES =
        setOf(
            "california",
            "texas",
            "florida",
            "new york",
            "washington",
            "massachusetts",
            "illinois",
            "colorado",
            "georgia",
            "north carolina",
            "virginia",
            "oregon",
            "arizona",
            "pennsylvania",
            "new jersey",
            "ohio",
            "michigan",
            "minnesota",
            "utah",
            "east coast",
            "west coast",
            "san francisco",
            "seattle",
            "austin",
        )

    private val COUNTRIES =
        listOf(
            Country(
                "India",
                listOf("Asia/Kolkata", "Asia/Calcutta"),
                setOf("india", "in", "ist", "bharat") + ASIA_PACIFIC,
            ),
            Country(
                "United States",
                listOf(
                    "America/New_York",
                    "America/Chicago",
                    "America/Denver",
                    "America/Los_Angeles",
                    "America/Phoenix",
                ),
                setOf("us", "usa", "u.s.", "u.s", "u.s.a.", "united states", "united states of america", "america") +
                    AMERICAS +
                    US_STATES,
            ),
            Country("Canada", listOf("America/Toronto", "America/Vancouver"), setOf("canada", "ca") + AMERICAS),
            Country(
                "United Kingdom",
                listOf("Europe/London"),
                setOf("uk", "u.k.", "united kingdom", "great britain", "england", "gmt", "emea"),
            ),
            Country("Germany", listOf("Europe/Berlin"), setOf("germany", "de") + EUROPE),
            Country("Singapore", listOf("Asia/Singapore"), setOf("singapore", "sg") + ASIA_PACIFIC),
            Country("Australia", listOf("Australia/"), setOf("australia", "au", "anz") + ASIA_PACIFIC),
        )

    private val WORDS_TO_DROP =
        Regex("\\b(time ?zones?|only|based|residents?|candidates?|remote)\\b", RegexOption.IGNORE_CASE)

    /** The user's country from their locations (`Bengaluru, India`) or time zones (`Asia/Kolkata`), if known. */
    fun countryOf(
        locations: List<String>,
        timeZones: List<String>,
    ): String? {
        val parts = locations.flatMap { it.split(',', ';', '/') }.map { normalize(it) }
        return COUNTRIES
            .firstOrNull { country ->
                parts.any {
                    it == country.name.lowercase(Locale.ROOT) ||
                        it in country.names
                }
            }?.name
            ?: COUNTRIES
                .firstOrNull { country ->
                    timeZones.any { tz -> country.timeZonePrefixes.any(tz::startsWith) }
                }?.name
    }

    /**
     * True when a limit's place list (`LATAM, Europe, USA, Canada, APAC`) includes [country]. Worldwide
     * wording includes everyone.
     */
    fun includes(
        placeList: String,
        country: String,
    ): Boolean {
        val names = COUNTRIES.firstOrNull { it.name == country }?.names.orEmpty() + country.lowercase(Locale.ROOT)
        return placeList
            .split(',', ';', '/', '|')
            .flatMap { it.split(Regex("\\s+(?:and|or|&)\\s+")) }
            .map { normalize(it.replace(WORDS_TO_DROP, " ")) }
            .filter { it.isNotEmpty() }
            .any { it in GLOBAL || it in names }
    }

    private fun normalize(text: String): String =
        text
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ', '-', '(', ')')
}
