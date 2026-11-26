package com.coderarchitech.jobassistant.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** Task T1.10 Done when: retries on 429, 529 and 5xx; no retry on other 4xx. */
class RetryInterceptorTest {
    private val server = MockWebServer()
    private val waits = mutableListOf<Long>()
    private val sleeper = Sleeper { millis, _ -> waits += millis }

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client(
        sleeper: Sleeper = this.sleeper,
        maxRetryAfter: Duration = Duration.ofSeconds(60),
    ) = OkHttpClient
        .Builder()
        .addInterceptor(
            RetryInterceptor(
                maxRetries = 3,
                backoff = Backoff(baseMs = 1_000, maxMs = 30_000, random = Random(7)),
                sleeper = sleeper,
                maxRetryAfter = maxRetryAfter,
            ),
        ).build()

    private fun httpDate(at: Instant): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date.from(at))

    private fun get() = Request.Builder().url(server.url("/v1")).build()

    private fun respond(
        code: Int,
        vararg headers: Pair<String, String>,
    ) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(code)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .apply { if (code != 204 && code != 304) body("body-$code") }
                .build(),
        )
    }

    @Test
    fun retriesOnTooManyRequestsThenReturnsTheSuccess() {
        respond(429)
        respond(200)

        client().newCall(get()).execute().use { assertEquals(200, it.code) }

        assertEquals(2, server.requestCount)
        assertEquals(1, waits.size)
    }

    @Test
    fun retriesOnOverloaded529() {
        respond(529)
        respond(529)
        respond(200)

        client().newCall(get()).execute().use { assertEquals(200, it.code) }

        assertEquals(3, server.requestCount)
        assertEquals(2, waits.size)
    }

    @Test
    fun retriesOnEveryServerError() {
        for (code in listOf(500, 502, 503, 504)) {
            respond(code)
            respond(200)

            client().newCall(get()).execute().use { assertEquals("after $code", 200, it.code) }
        }

        assertEquals(8, server.requestCount)
    }

    @Test
    fun givesUpAfterThreeRetriesAndReturnsTheLastResponse() {
        repeat(4) { respond(503) }

        client().newCall(get()).execute().use {
            assertEquals(503, it.code)
            assertEquals("body-503", it.body.string())
        }

        assertEquals("one try and three retries", 4, server.requestCount)
        assertEquals(3, waits.size)
    }

    @Test
    fun doesNotRetryOtherClientErrors() {
        for (code in listOf(400, 401, 402, 403, 404, 405, 409, 422, 451)) {
            respond(code)

            client().newCall(get()).execute().use { assertEquals(code, it.code) }
        }

        assertEquals(9, server.requestCount)
        assertTrue("no waits for client errors", waits.isEmpty())
    }

    @Test
    fun aRequestTaggedNoRetryIsSentOnce() {
        respond(503)
        respond(200)

        val request = get().newBuilder().tag(NoRetry::class.java, NoRetry).build()
        client().newCall(request).execute().use { assertEquals(503, it.code) }

        assertEquals(1, server.requestCount)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun doesNotRetrySuccessOrRedirectResponses() {
        respond(200)
        respond(204)
        respond(304)

        for (expected in listOf(200, 204, 304)) {
            client().newCall(get()).execute().use { assertEquals(expected, it.code) }
        }

        assertEquals(3, server.requestCount)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun honorsRetryAfterSeconds() {
        respond(429, "Retry-After" to "7")
        respond(200)

        client().newCall(get()).execute().use { assertEquals(200, it.code) }

        assertEquals(listOf(7_000L), waits)
    }

    @Test
    fun honorsRetryAfterGivenAsAnHttpDate() {
        val now = Instant.now()
        respond(529, "Date" to httpDate(now), "Retry-After" to httpDate(now.plusSeconds(10)))
        respond(200)

        client().newCall(get()).execute().use { assertEquals(200, it.code) }

        assertEquals(1, waits.size)
        assertTrue("about ten seconds, was ${waits.single()}", waits.single() in 9_000L..10_000L)
    }

    @Test
    fun aRetryAfterOfZeroRetriesAtOnce() {
        respond(429, "Retry-After" to "0")
        respond(200)

        client().newCall(get()).execute().use { assertEquals(200, it.code) }

        assertEquals(listOf(0L), waits)
    }

    @Test
    fun aRetryAfterBeyondTheLimitReturnsTheResponseWithoutWaiting() {
        respond(429, "Retry-After" to "3600")

        client().newCall(get()).execute().use {
            assertEquals(429, it.code)
            assertEquals("3600", it.header("Retry-After"))
        }

        assertEquals(1, server.requestCount)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun anAbsurdRetryAfterIsTooLongToWaitFor() {
        respond(429, "Retry-After" to "9223372036854775")

        client().newCall(get()).execute().use { assertEquals(429, it.code) }

        assertEquals(1, server.requestCount)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun backoffGrowsWithJitterWhenThereIsNoRetryAfter() {
        repeat(4) { respond(500) }

        client().newCall(get()).execute().close()

        assertEquals(3, waits.size)
        // Retry n waits between half and all of 1s * 2^n: [500, 1000], [1000, 2000], [2000, 4000].
        waits.forEachIndexed { n, ms ->
            val ceiling = 1_000L shl n
            assertTrue("retry $n waited $ms", ms in (ceiling / 2)..ceiling)
        }
    }

    @Test
    fun backoffNeverExceedsItsMaximum() {
        val backoff = Backoff(baseMs = 1_000, maxMs = 30_000, random = Random(1))

        for (retry in 5..40) assertTrue("retry $retry", backoff.delayMs(retry) in 15_000L..30_000L)
        assertTrue(backoff.delayMs(Int.MAX_VALUE) in 15_000L..30_000L)
    }

    @Test
    fun resendsTheSameBodyOnEachRetry() {
        respond(503)
        respond(200)
        val body = """{"a":1}""".toRequestBody("application/json".toMediaType())
        val post =
            Request
                .Builder()
                .url(server.url("/v1"))
                .post(body)
                .build()

        client().newCall(post).execute().close()

        assertEquals("""{"a":1}""", server.takeRequest().body?.utf8())
        assertEquals("""{"a":1}""", server.takeRequest().body?.utf8())
    }

    @Test
    fun neverRetriesARequestWhoseBodyCanBeSentOnlyOnce() {
        respond(503)
        respond(200)
        val oneShot =
            object : RequestBody() {
                override fun contentType() = "text/plain".toMediaType()

                override fun isOneShot() = true

                override fun writeTo(sink: BufferedSink) {
                    sink.writeUtf8("once")
                }
            }

        client()
            .newCall(
                Request
                    .Builder()
                    .url(server.url("/v1"))
                    .post(oneShot)
                    .build(),
            ).execute()
            .use {
                assertEquals(503, it.code)
            }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun aCancelledCallStopsWaitingAndFails() {
        respond(503)
        respond(200)
        val cancelling =
            Sleeper { _, call: Call ->
                call.cancel()
                throw IOException("Canceled")
            }

        try {
            client(sleeper = cancelling).newCall(get()).execute().close()
            fail("expected the call to fail")
        } catch (expected: IOException) {
            assertFalse(expected.message.isNullOrEmpty())
        }

        assertEquals(1, server.requestCount)
    }
}
