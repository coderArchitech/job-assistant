package com.coderarchitech.jobassistant.core.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets at most [maxPerHost] requests to one host be in progress at a time, so job boards are never hit
 * harder than the design allows (2 concurrent requests per host). Extra requests wait here. Unlike
 * OkHttp's own `maxRequestsPerHost`, this also covers blocking `execute()` calls, which skip the
 * dispatcher.
 *
 * A request keeps its permit until its response body is closed, so a slow download counts, and retries
 * and their waits (which happen inside the call) keep it too. A waiting request gives up with an
 * [IOException] when its call is cancelled or its call timeout passes. A request that follows a redirect
 * to another host is counted against the first host only.
 */
internal class HostLimitInterceptor(
    private val maxPerHost: Int = DEFAULT_MAX_PER_HOST,
) : Interceptor {
    private val permits = ConcurrentHashMap<String, Semaphore>()

    init {
        require(maxPerHost > 0) { "maxPerHost must be positive" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val host =
            chain
                .request()
                .url.host
                .lowercase()
                .trimEnd('.')
        val semaphore = permits.getOrPut(host) { Semaphore(maxPerHost, true) }
        acquire(semaphore, chain)

        val released = AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) semaphore.release() }
        // From here the permit belongs to the response body; until then a failure must give it back.
        var handedOver = false
        try {
            val response = chain.proceed(chain.request())
            handedOver = true
            return response
                .newBuilder()
                .body(ReleasingBody(response.body, release))
                .build()
        } finally {
            if (!handedOver) release()
        }
    }

    private fun acquire(
        semaphore: Semaphore,
        chain: Interceptor.Chain,
    ) {
        try {
            while (!semaphore.tryAcquire(SLICE_MS, TimeUnit.MILLISECONDS)) {
                if (chain.call().isCanceled()) throw IOException("Canceled")
                chain.call().timeout().throwIfReached()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting for a turn on the host").also { it.initCause(e) }
        }
    }

    /** Gives the permit back when the body is closed, which also happens when it is fully read. */
    private class ReleasingBody(
        private val delegate: ResponseBody,
        private val release: () -> Unit,
    ) : ResponseBody() {
        private val source =
            object : ForwardingSource(delegate.source()) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        release()
                    }
                }

                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long =
                    try {
                        super.read(sink, byteCount)
                    } catch (e: IOException) {
                        release()
                        throw e
                    }
            }.let { it as Source }.buffer()

        override fun contentType() = delegate.contentType()

        override fun contentLength() = delegate.contentLength()

        override fun source() = source
    }

    internal companion object {
        /** The design's limit for job sources. */
        const val DEFAULT_MAX_PER_HOST = 2
        private const val SLICE_MS = 100L
    }
}
