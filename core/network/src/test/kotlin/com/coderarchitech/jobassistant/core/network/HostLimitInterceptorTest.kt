package com.coderarchitech.jobassistant.core.network

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The design's limit for job sources: at most two concurrent requests per host. */
class HostLimitInterceptorTest {
    private val server = MockWebServer()
    private val inFlight = AtomicInteger(0)
    private val highWater = AtomicInteger(0)
    private val pool = Executors.newFixedThreadPool(8)

    @Before
    fun start() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val now = inFlight.incrementAndGet()
                    highWater.accumulateAndGet(now, ::maxOf)
                    Thread.sleep(SERVER_DELAY_MS)
                    inFlight.decrementAndGet()
                    return MockResponse
                        .Builder()
                        .code(200)
                        .body("ok")
                        .build()
                }
            }
        server.start()
    }

    @After
    fun stop() {
        pool.shutdownNow()
        server.close()
    }

    private fun limited(max: Int = 2) = OkHttpClient.Builder().addInterceptor(HostLimitInterceptor(max)).build()

    private fun callTimes(
        client: OkHttpClient,
        count: Int,
        host: String = server.url("/").host,
    ) {
        val url =
            server
                .url("/x")
                .newBuilder()
                .host(host)
                .build()
        val done = CountDownLatch(count)
        repeat(count) {
            pool.execute {
                runCatching { client.newCall(Request.Builder().url(url).build()).execute().close() }
                done.countDown()
            }
        }
        assertTrue("all calls finished", done.await(20, TimeUnit.SECONDS))
    }

    @Test
    fun noMoreThanTwoRequestsToOneHostRunAtOnce() {
        callTimes(limited(), count = 8)

        assertEquals(8, server.requestCount)
        assertEquals("the server never saw more than two at once", 2, highWater.get())
    }

    @Test
    fun theLimitIsPerHost() {
        val client = limited()
        val other = if (server.url("/").host == "localhost") "127.0.0.1" else "localhost"
        val done = CountDownLatch(2)
        pool.execute {
            callTimes(client, 4)
            done.countDown()
        }
        pool.execute {
            callTimes(client, 4, host = other)
            done.countDown()
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))

        assertEquals(8, server.requestCount)
        assertTrue("two hosts may each have two in flight: ${highWater.get()}", highWater.get() in 3..4)
    }

    @Test
    fun theFactoryLimitsJobSourcesToTwoPerHost() {
        val factory =
            HttpClientFactory(OkHttpClient(), "", NetworkConfig(httpLogging = false), { }, allowCleartext = true)

        callTimes(factory.create(NetworkGroup.JOB_SOURCES), count = 8)
        assertEquals(2, highWater.get())
    }

    @Test
    fun aCancelledCallStopsWaitingForAPermit() {
        val client = limited(max = 1)
        val holding = CountDownLatch(1)
        pool.execute {
            holding.countDown()
            runCatching { client.newCall(Request.Builder().url(server.url("/slow")).build()).execute().close() }
        }
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        Thread.sleep(100) // let the first call take the only permit

        val waiting = client.newCall(Request.Builder().url(server.url("/wait")).build())
        val started = System.nanoTime()
        pool.execute {
            Thread.sleep(150)
            waiting.cancel()
        }
        val failure = runCatching { waiting.execute().close() }.exceptionOrNull()

        assertTrue("failed with an IOException: $failure", failure is IOException)
        assertTrue(
            "gave up well before the server answered",
            (System.nanoTime() - started) / 1_000_000 < SERVER_DELAY_MS * 4,
        )
    }

    @Test
    fun aPermitIsHeldUntilTheResponseBodyIsClosed() {
        val client = limited(max = 1)
        val first = client.newCall(Request.Builder().url(server.url("/one")).build()).execute()

        val gotIn = CountDownLatch(1)
        pool.execute {
            runCatching { client.newCall(Request.Builder().url(server.url("/two")).build()).execute().close() }
            gotIn.countDown()
        }

        assertTrue("the second call waits while the first body is open", !gotIn.await(500, TimeUnit.MILLISECONDS))
        first.close()
        assertTrue("and goes once it is closed", gotIn.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun aWaitingCallGivesUpWhenItsCallTimeoutPasses() {
        val limiter = HostLimitInterceptor(1)
        val holderClient = OkHttpClient.Builder().addInterceptor(limiter).build()
        val waiterClient = holderClient.newBuilder().callTimeout(400, TimeUnit.MILLISECONDS).build()
        val holder = holderClient.newCall(Request.Builder().url(server.url("/hold")).build()).execute()
        try {
            val started = System.nanoTime()
            var failure: IOException? = null
            try {
                waiterClient.newCall(Request.Builder().url(server.url("/wait")).build()).execute().close()
            } catch (e: IOException) {
                failure = e
            }

            assertTrue("timed out with an IOException: $failure", failure != null)
            assertTrue("within about the call timeout", (System.nanoTime() - started) / 1_000_000 < 3_000)
        } finally {
            holder.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun theLimitMustBePositive() {
        HostLimitInterceptor(0)
    }

    private companion object {
        const val SERVER_DELAY_MS = 150L
    }
}
