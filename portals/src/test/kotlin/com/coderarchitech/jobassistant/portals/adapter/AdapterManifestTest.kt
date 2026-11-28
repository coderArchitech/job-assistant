package com.coderarchitech.jobassistant.portals.adapter

import com.coderarchitech.jobassistant.portals.web.PortalSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdapterManifestTest {
    /** The sample adapter the device tests also drive, so both read one file. */
    private val sample: JsonObject =
        Json.parseToJsonElement(File("src/androidTest/assets/adapter/sample.json").readText()).jsonObject

    @Test
    fun theSampleAdapterIsValid() {
        val manifest = checkNotNull(AdapterManifest.from(sample))

        assertEquals(emptyList<String>(), manifest.validate())
        assertEquals("#user", manifest.css("username"))
        assertTrue(manifest.isOwnPage("https://portal.example/assets/adapter/job.html?id=1"))
        assertFalse(manifest.isOwnPage("https://portal.example.evil/x"))
        assertFalse(manifest.isOwnPage("http://portal.example/x"))
        assertFalse(manifest.isOwnPage("https://user@portal.example/x"))
    }

    @Test
    fun aManifestThatBreaksARuleIsRefused() {
        val cases =
            mapOf(
                "http origin" to sample.with("origins", """["http://portal.example"]"""),
                "login off the origin" to
                    sample.with(
                        "urls",
                        """{"login":"https://evil.example/login","search":"https://portal.example/s?q={query}",
                        "health":"https://portal.example/h"}""",
                    ),
                "no {query}" to
                    sample.with(
                        "urls",
                        """{"login":"https://portal.example/l","search":"https://portal.example/s",
                        "health":"https://portal.example/h"}""",
                    ),
                "undefined selector" to sample.with("health", """{"selectors":["nowhere"]}"""),
                "no health selectors" to sample.with("health", """{"selectors":[]}"""),
                "unknown step" to
                    sample.with(
                        "login",
                        """{"steps":[{"do":"eval","selector":"username"}],"loggedIn":"loggedIn"}""",
                    ),
                "login types something else" to
                    sample.with(
                        "login",
                        """{"steps":[{"do":"type","selector":"username","value":"email"}],"loggedIn":"loggedIn"}""",
                    ),
                "search without a url field" to
                    sample.with(
                        "search",
                        """{"items":".job","fields":{"id":{"selector":""},"title":{"selector":"h2"}}}""",
                    ),
                "search without items" to
                    sample.with(
                        "search",
                        """{"fields":{"id":{"selector":""},"title":{"selector":"h2"},"url":{"selector":"a"}}}""",
                    ),
                "details without a description" to sample.with("details", """{"fields":{"title":{"selector":"h1"}}}"""),
                "unknown field kind" to
                    sample.with(
                        "apply",
                        """{"fields":{"name":{"selector":"name","kind":"upload"}},
                        "submit":"send","confirmation":"thanks"}""",
                    ),
                "missing section" to JsonObject(sample - "apply"),
            )
        for ((name, json) in cases) assertNull(name, AdapterManifest.from(json))
    }

    @Test
    fun stepsThatCouldSubmitLeakOrWanderAreRefused() {
        val cases =
            mapOf(
                "{query} in the host" to
                    sample.with(
                        "urls",
                        """{"login":"https://portal.example/l","search":"https://{query}/s",
                        "health":"https://portal.example/h"}""",
                    ),
                "a form field with no selector defined" to
                    sample.with(
                        "apply",
                        """{"fields":{"email":{"selector":"emailInput"}},"submit":"send","confirmation":"thanks"}""",
                    ),
                "a login step that clicks submit" to
                    sample.with(
                        "login",
                        """{"steps":[{"do":"click","selector":"send"}],"loggedIn":"loggedIn"}""",
                    ),
                "a step that opens the form by submitting it" to
                    sample.with(
                        "apply",
                        """{"open":[{"do":"click","selector":"send"}],"fields":{},"submit":"send",
                        "confirmation":"thanks"}""",
                    ),
                "the password in a select" to
                    sample.with(
                        "login",
                        """{"steps":[{"do":"select","selector":"password","value":"password"}],
                        "loggedIn":"loggedIn"}""",
                    ),
                "reading a value attribute" to
                    sample.with(
                        "details",
                        """{"fields":{"title":{"selector":"h1"},
                        "description":{"selector":"input","attribute":"value"}}}""",
                    ),
                "not even a manifest" to JsonObject(mapOf("origins" to JsonPrimitive(3))),
            )
        for ((name, json) in cases) assertNull(name, AdapterManifest.from(json))
    }

    @Test
    fun aManifestMustStayOnItsPortalsOrigins() {
        val manifest = checkNotNull(AdapterManifest.from(sample))
        val sample = PortalSession("sample", listOf("https://portal.example", "https://login.portal.example"))

        assertTrue(manifest.isFor(sample))
        assertFalse(manifest.isFor(PortalSession("naukri", listOf("https://www.naukri.com"))))
    }

    private fun JsonObject.with(
        key: String,
        json: String,
    ) = JsonObject(this + (key to Json.parseToJsonElement(json)))
}
