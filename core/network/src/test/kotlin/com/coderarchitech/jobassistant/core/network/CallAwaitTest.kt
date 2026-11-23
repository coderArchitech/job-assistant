package com.coderarchitech.jobassistant.core.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class CallAwaitTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun call(path: String = "/x") = client.newCall(Request.Builder().url(server.url(path)).build())

    @Test
    fun aReplyIsReturnedWithoutBlockingTheCaller() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("hello")
                    .build(),
            )

            call().await().use {
                assertEquals(200, it.code)
                assertEquals("hello", it.body.string())
            }
        }

    @Test
    fun aFailedCallThrowsAnIoException() =
        runBlocking {
            server.close() // nothing is listening any more

            try {
                call().await().close()
                fail("expected an IOException")
            } catch (expected: IOException) {
                assertTrue(expected.message, expected.message != null)
            }
        }

    @Test
    fun cancellingTheCoroutineCancelsTheCall() =
        runBlocking {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("late")
                    .headersDelay(5, TimeUnit.SECONDS)
                    .build(),
            )
            val pending = call()

            val job = async(start = CoroutineStart.UNDISPATCHED) { pending.await().close() }
            withTimeout(5_000) { while (!pending.isExecuted()) kotlinx.coroutines.yield() }
            job.cancel()

            withTimeout(5_000) { while (!pending.isCanceled()) kotlinx.coroutines.yield() }
            assertTrue(pending.isCanceled())
        }
}
