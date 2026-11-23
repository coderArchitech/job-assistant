package com.coderarchitech.jobassistant.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/** The per-group clients: host allowlists, HTTPS only, retry wiring, and header logging with secrets removed. */
class HttpClientFactoryTest {
    private val server = MockWebServer()
    private val logs = mutableListOf<String>()
    private val waits = mutableListOf<Long>()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun factory(
        httpLogging: Boolean = false,
        allowCleartext: Boolean = false,
        configHost: String = "",
    ) = HttpClientFactory(
        base = OkHttpClient(),
        configHost = configHost,
        config = NetworkConfig(httpLogging = httpLogging),
        logger = { logs += it },
        retry = RetryInterceptor(backoff = Backoff(random = Random(3)), sleeper = { ms, _ -> waits += ms }),
        allowCleartext = allowCleartext,
    )

    private fun request(url: String) = Request.Builder().url(url).build()

    private fun assertBlocked(
        client: OkHttpClient,
        url: String,
    ) {
        try {
            client.newCall(request(url)).execute().close()
            fail("expected $url to be blocked")
        } catch (expected: BlockedRequestException) {
            // The request never left the app.
        }
    }

    @Test
    fun everyClientRefusesPlainHttp() {
        val factory = factory()
        for (group in NetworkGroup.entries) {
            assertBlocked(factory.create(group), "http://api.anthropic.com/v1/messages")
        }
        assertEquals("nothing was sent", 0, server.requestCount)
    }

    @Test
    fun theAiClientReachesOnlyTheTwoProviders() {
        val client = factory().create(NetworkGroup.AI)

        assertBlocked(client, "https://example.com/")
        assertBlocked(client, "https://api.anthropic.com.evil.example/v1")
        assertBlocked(client, "https://evil-api.openai.com/v1")
        assertBlocked(client, "https://www.linkedin.com/")
    }

    @Test
    fun theGoogleClientReachesGoogleApisOnly() {
        val policy = HostPolicy.forGroup(NetworkGroup.GOOGLE, "")

        assertTrue(policy.allows("gmail.googleapis.com"))
        assertTrue(policy.allows("oauth2.googleapis.com"))
        assertTrue(policy.allows("accounts.google.com"))
        assertFalse(policy.allows("googleapis.com.evil.example"))
        assertFalse(policy.allows("notgoogleapis.com"))
        assertFalse(policy.allows("example.com"))
    }

    @Test
    fun theConfigClientReachesOnlyTheConfigHost() {
        val policy = HostPolicy.forGroup(NetworkGroup.CONFIG, "https://config.example.com/adapters")

        assertTrue(policy.allows("config.example.com"))
        assertFalse(policy.allows("example.com"))
        assertFalse(policy.allows("evil.config.example.com.attacker.net"))
    }

    @Test
    fun theConfigClientReachesNothingUntilAConfigHostIsSet() {
        val client = factory(configHost = "").create(NetworkGroup.CONFIG)

        assertBlocked(client, "https://example.com/")
        assertFalse(HostPolicy.forGroup(NetworkGroup.CONFIG, "").allows("localhost"))
    }

    @Test
    fun jobSourcesMayReachAnyHttpsHostExceptLinkedIn() {
        val policy = HostPolicy.forGroup(NetworkGroup.JOB_SOURCES, "")

        assertTrue(policy.allows("boards-api.greenhouse.io"))
        assertTrue(policy.allows("api.lever.co"))
        assertFalse(policy.allows("linkedin.com"))
        assertFalse(policy.allows("www.linkedin.com"))
        assertFalse(policy.allows("API.LINKEDIN.COM"))
        assertTrue("a look-alike is a different site", policy.allows("notlinkedin.com"))
    }

    @Test
    fun aTrailingDotDoesNotDodgeAHostRule() {
        val sources = HostPolicy.forGroup(NetworkGroup.JOB_SOURCES, "")
        val ai = HostPolicy.forGroup(NetworkGroup.AI, "")

        assertFalse(sources.allows("www.linkedin.com."))
        assertFalse(sources.allows("linkedin.com."))
        assertTrue(ai.allows("api.anthropic.com."))
        assertFalse(ai.allows("example.com."))
    }

    @Test
    fun jobSourcesCannotReachTheLocalNetwork() {
        val policy = HostPolicy.forGroup(NetworkGroup.JOB_SOURCES, "")

        for (host in listOf(
            "localhost",
            "127.0.0.1",
            "192.168.1.1",
            "10.0.0.5",
            "169.254.169.254",
            "[::1]",
            "::1",
            "printer.local",
            "db.internal",
            "a.localhost",
        )) {
            assertFalse("$host must be blocked", policy.allows(host))
        }
        assertTrue(policy.allows("boards.greenhouse.io"))
        assertTrue("a name that merely contains digits is fine", policy.allows("job123.example.com"))
    }

    @Test
    fun theConfigHostMayBeAUrlOrABareHostName() {
        assertTrue(HostPolicy.forGroup(NetworkGroup.CONFIG, "https://cfg.example.com/x").allows("cfg.example.com"))
        assertTrue(HostPolicy.forGroup(NetworkGroup.CONFIG, "cfg.example.com").allows("cfg.example.com"))
        assertTrue(HostPolicy.forGroup(NetworkGroup.CONFIG, "  cfg.example.com  ").allows("cfg.example.com"))
        assertFalse(HostPolicy.forGroup(NetworkGroup.CONFIG, "cfg.example.com").allows("example.com"))
    }

    @Test
    fun aRedirectToAnotherHostIsBlockedBeforeItIsFollowed() {
        val other = MockWebServer().apply { start() }
        try {
            // Allowed host is the first server's; the redirect points at the second server on a different name.
            val firstHost = server.url("/").host
            val otherHost = if (firstHost == "localhost") "127.0.0.1" else "localhost"
            val otherUrl =
                other
                    .url("/")
                    .newBuilder()
                    .host(otherHost)
                    .build()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", otherUrl.toString())
                    .build(),
            )
            val policy = HostPolicy(exactHosts = setOf(firstHost))
            val client =
                OkHttpClient
                    .Builder()
                    .addNetworkInterceptor(HostPolicyInterceptor(policy, allowCleartext = true))
                    .build()

            try {
                client.newCall(request(server.url("/").toString())).execute().close()
                fail("expected the redirect to be blocked")
            } catch (expected: BlockedRequestException) {
                // Followed no further.
            }
            assertEquals(0, other.requestCount)
        } finally {
            other.close()
        }
    }

    @Test
    fun theFactoryClientsRetryServerErrors() {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("ok")
                .build(),
        )
        val client = factory(allowCleartext = true).create(NetworkGroup.JOB_SOURCES)

        client.newCall(request(server.url("/").toString())).execute().use { assertEquals("ok", it.body.string()) }

        assertEquals(2, server.requestCount)
        assertEquals(1, waits.size)
    }

    @Test
    fun theFactoryClientsDoNotRetryABadKey() {
        server.enqueue(MockResponse.Builder().code(401).build())
        val client = factory(allowCleartext = true).create(NetworkGroup.JOB_SOURCES)

        client.newCall(request(server.url("/").toString())).execute().use { assertEquals(401, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun theClientsShareOneConnectionPoolAndDispatcher() {
        val factory = factory()
        val ai = factory.create(NetworkGroup.AI)
        val google = factory.create(NetworkGroup.GOOGLE)

        assertTrue(ai.connectionPool === google.connectionPool)
        assertTrue(ai.dispatcher === google.dispatcher)
    }

    @Test
    fun httpLoggingHidesSecretsAndNeverLogsBodies() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Set-Cookie", "session=SECRET-SESSION")
                .body("""{"answer":"BODY-TEXT-IN-RESPONSE"}""")
                .build(),
        )
        val client = factory(httpLogging = true, allowCleartext = true).create(NetworkGroup.JOB_SOURCES)
        val url = server.url("/v1?key=SECRET-QUERY&apikey=SECRET-APIKEY&page=2").toString()

        client
            .newCall(
                Request
                    .Builder()
                    .url(url)
                    .header("Authorization", "Bearer SECRET-BEARER")
                    .header("x-api-key", "SECRET-API-KEY")
                    .header("Cookie", "sid=SECRET-COOKIE")
                    .post(okhttp3.RequestBody.create(null, """{"prompt":"BODY-TEXT-IN-REQUEST"}"""))
                    .build(),
            ).execute()
            .use { it.body.string() }

        val log = logs.joinToString("\n")
        assertTrue("headers are logged", log.contains("x-api-key"))
        assertTrue("non-secret query params stay readable", log.contains("page=2"))
        for (secret in listOf(
            "SECRET-BEARER",
            "SECRET-API-KEY",
            "SECRET-COOKIE",
            "SECRET-SESSION",
            "SECRET-QUERY",
            "SECRET-APIKEY",
        )) {
            assertFalse("$secret must not be logged", log.contains(secret))
        }
        assertFalse("request bodies are never logged", log.contains("BODY-TEXT-IN-REQUEST"))
        assertFalse("response bodies are never logged", log.contains("BODY-TEXT-IN-RESPONSE"))
    }

    @Test
    fun httpLoggingIsOffUnlessTheBuildAsksForIt() {
        server.enqueue(MockResponse.Builder().code(200).build())
        val client = factory(httpLogging = false, allowCleartext = true).create(NetworkGroup.JOB_SOURCES)

        client.newCall(request(server.url("/").toString())).execute().close()

        assertTrue(logs.isEmpty())
    }

    @Test
    fun sharedJsonIgnoresUnknownFieldsAndMissingNullables() {
        @Serializable
        data class Reply(
            val text: String,
            val usage: Int? = null,
        )

        val parsed = NetworkJson.decodeFromString<Reply>("""{"text":"hi","new_field":{"a":1}}""")

        assertEquals(Reply("hi", null), parsed)
        assertEquals("""{"text":"hi"}""", NetworkJson.encodeToString(Reply("hi")))
    }
}
