package com.coderarchitech.jobassistant.agent.scoring

import java.util.Locale

/**
 * Which place names include the user, for checking a posting's region limit (`US only`,
 * `LATAM, Europe, APAC only`) or an office's country. A rejected job is worse than a missed one, so the
 * answer is "can't tell" whenever a name is not known here, and a city in the user's own country is never
 * taken as somewhere else.
 */
internal object Places {
    /**
     * A country the app can place a user in. [aliases] are unambiguous names, used to find the user's country
     * and in limits; [regions] are wider areas that include it, used only in limits; [subdivisions] are states
     * and cities, which count as the country in a limit or an office location.
     */
    private class Country(
        val name: String,
        val timeZonePrefixes: List<String>,
        val aliases: Set<String>,
        val regions: Set<String> = emptySet(),
        val subdivisions: Set<String> = emptySet(),
    ) {
        val all: Set<String> = aliases + regions + subdivisions + name.lowercase(Locale.ROOT)
    }

    private val GLOBAL = setOf("worldwide", "anywhere", "anywhere in the world", "global", "international")
    private val ASIA_PACIFIC = setOf("asia", "apac", "asia pacific", "asia-pacific", "south asia")
    private val EUROPE = setOf("europe", "eu", "european union", "emea", "eea", "cet", "cest")
    private val AMERICAS = setOf("americas", "north america", "est", "pst", "et", "pt", "cst", "mst")

    private val COUNTRIES =
        listOf(
            Country(
                "India",
                listOf("Asia/Kolkata", "Asia/Calcutta"),
                setOf("india", "bharat", "ist"),
                ASIA_PACIFIC,
                setOf(
                    "bengaluru",
                    "bangalore",
                    "mumbai",
                    "bombay",
                    "delhi",
                    "new delhi",
                    "ncr",
                    "noida",
                    "gurugram",
                    "gurgaon",
                    "hyderabad",
                    "chennai",
                    "pune",
                    "kolkata",
                    "ahmedabad",
                    "jaipur",
                    "kochi",
                    "surat",
                    "karnataka",
                    "maharashtra",
                    "tamil nadu",
                    "telangana",
                    "kerala",
                    "gujarat",
                ),
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
                setOf("us", "usa", "u.s", "u.s.a", "united states", "united states of america"),
                AMERICAS + setOf("america", "us timezones", "usa timezones"),
                setOf(
                    "california",
                    "texas",
                    "florida",
                    "new york",
                    "massachusetts",
                    "illinois",
                    "colorado",
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
                    "chicago",
                    "boston",
                    "nyc",
                    "los angeles",
                    "denver",
                    "atlanta",
                    "dallas",
                    "houston",
                    "miami",
                ),
            ),
            Country(
                "Canada",
                listOf("America/Toronto", "America/Vancouver", "America/Montreal", "America/Edmonton"),
                setOf("canada"),
                AMERICAS,
                setOf("toronto", "vancouver", "montreal", "ontario", "british columbia", "quebec", "alberta"),
            ),
            Country(
                "United Kingdom",
                listOf("Europe/London"),
                setOf("uk", "u.k", "united kingdom", "great britain", "england", "scotland", "wales"),
                EUROPE + "gmt",
                setOf("london", "manchester", "edinburgh"),
            ),
            Country("Germany", listOf("Europe/Berlin"), setOf("germany"), EUROPE, setOf("berlin", "munich", "hamburg")),
            Country("Singapore", listOf("Asia/Singapore"), setOf("singapore"), ASIA_PACIFIC),
            Country(
                "Australia",
                listOf("Australia/"),
                setOf("australia"),
                ASIA_PACIFIC + "anz",
                setOf("sydney", "melbourne"),
            ),
        )

    /** Places the app does not place users in, but recognises, so a limit naming only these can be judged. */
    private val OTHER_PLACES =
        setOf(
            "latam",
            "latin america",
            "south america",
            "central america",
            "africa",
            "middle east",
            "mena",
            "oceania",
            "france",
            "spain",
            "portugal",
            "italy",
            "netherlands",
            "ireland",
            "poland",
            "sweden",
            "norway",
            "denmark",
            "finland",
            "switzerland",
            "austria",
            "belgium",
            "czech republic",
            "romania",
            "ukraine",
            "greece",
            "mexico",
            "brazil",
            "argentina",
            "colombia",
            "chile",
            "peru",
            "costa rica",
            "saudi arabia",
            "uae",
            "united arab emirates",
            "israel",
            "turkey",
            "egypt",
            "nigeria",
            "kenya",
            "south africa",
            "japan",
            "china",
            "south korea",
            "philippines",
            "indonesia",
            "vietnam",
            "malaysia",
            "thailand",
            "pakistan",
            "bangladesh",
            "sri lanka",
            "nepal",
            "new zealand",
        )

    private val WORDS_TO_DROP =
        Regex("\\b(time ?zones?|only|based|residents?|candidates?|remote|hybrid|on-?site)\\b", RegexOption.IGNORE_CASE)

    /** The countries the user named in their locations (`Bengaluru, India`) or time zones (`Asia/Kolkata`). */
    fun countriesOf(
        locations: List<String>,
        timeZones: List<String>,
    ): Set<String> {
        val parts = locations.flatMap(::names)
        val fromLocations =
            COUNTRIES.filter { country ->
                parts.any {
                    it in country.aliases ||
                        it in country.subdivisions
                }
            }
        val fromZones =
            COUNTRIES.filter { country ->
                timeZones.any { tz -> country.timeZonePrefixes.any(tz::startsWith) }
            }
        return (fromLocations + fromZones).map { it.name }.toSet()
    }

    /**
     * Whether a limit's place list includes any of [countries]: true when it does (or says worldwide), false
     * when every name in it is a known place and none is the user's, and null when a name is not known.
     */
    fun includes(
        placeList: String,
        countries: Set<String>,
    ): Boolean? {
        val listed = names(placeList)
        val mine = COUNTRIES.filter { it.name in countries }.flatMap { it.all }.toSet()
        return when {
            listed.isEmpty() -> null
            listed.any { it in GLOBAL || it in mine } -> true
            listed.all(::isKnown) -> false
            else -> null
        }
    }

    /** The known countries a location names (`Seattle, WA` gives United States), or none. */
    fun countriesNamedIn(location: String): Set<String> {
        val parts = names(location)
        return COUNTRIES.filter { country -> parts.any { it in country.all } }.map { it.name }.toSet() +
            parts.filter { it in OTHER_PLACES }
    }

    private fun isKnown(name: String): Boolean = name in OTHER_PLACES || COUNTRIES.any { name in it.all }

    private fun names(text: String): List<String> =
        text
            .replace(WORDS_TO_DROP, " ")
            .split(',', ';', '/', '|', '(', ')')
            .flatMap { it.split(Regex("\\s+(?:and|or|&)\\s+|\\s+-\\s+")) }
            .map {
                it
                    .trim()
                    .lowercase(Locale.ROOT)
                    .replace(Regex("\\s+"), " ")
                    .trim('.', ' ', '-')
            }.filter { it.isNotEmpty() }
}
