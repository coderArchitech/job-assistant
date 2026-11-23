package com.coderarchitech.jobassistant.core.network

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import kotlin.random.Random

/** Waits between retries. A seam so tests do not really sleep. */
internal fun interface Sleeper {
    /** Sleeps [millis], and throws [IOException] if [call] is cancelled meanwhile. */
    @Throws(IOException::class)
    fun sleep(
        millis: Long,
        call: Call,
    )
}

/** Sleeps in short slices, so cancelling the call ends a long wait quickly. */
internal object ThreadSleeper : Sleeper {
    private const val SLICE_MS = 200L

    override fun sleep(
        millis: Long,
        call: Call,
    ) {
        var remaining = millis
        while (remaining > 0) {
            if (call.isCanceled()) throw IOException("Canceled")
            val slice = minOf(SLICE_MS, remaining)
            Thread.sleep(slice)
            remaining -= slice
        }
        if (call.isCanceled()) throw IOException("Canceled")
    }
}

/** Exponential backoff with jitter: the wait doubles each attempt up to [maxMs], then lands in its upper half. */
internal class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 30_000,
    private val random: Random = Random.Default,
) {
    /** The wait before retry number [retry] (0 for the first retry). */
    fun delayMs(retry: Int): Long {
        val ceiling = minOf(maxMs, baseMs shl retry.coerceAtMost(MAX_SHIFT))
        val half = ceiling / 2
        return half + random.nextLong(half + 1)
    }

    private companion object {
        const val MAX_SHIFT = 20
    }
}

/**
 * Retries HTTP 429, 529 and 5xx up to [maxRetries] times, as the design requires. Any other status is
 * returned as it is, so other 4xx errors (a bad key, a bad request) fail at once. A `Retry-After` header
 * is honored; one longer than [maxRetryAfter] ends the retries and returns the response, so the caller can
 * tell the user how long to wait instead of this thread sleeping for minutes. After the last retry the last
 * response is returned unchanged.
 *
 * Transport failures (no connection, timeouts) are not retried here: the caller reports a network error.
 * A request whose body can be sent only once is never retried.
 */
internal class RetryInterceptor(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val backoff: Backoff = Backoff(),
    private val sleeper: Sleeper = ThreadSleeper,
    private val maxRetryAfter: Duration = Duration.ofSeconds(DEFAULT_MAX_RETRY_AFTER_SECONDS),
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var retry = 0
        while (true) {
            val response = chain.proceed(request)
            val canRetry = retry < maxRetries && isRetryable(response.code) && request.body?.isOneShot() != true
            if (!canRetry) return response

            val wait = waitMs(response, retry) ?: return response
            response.close()
            sleeper.sleep(wait, chain.call())
            retry++
        }
    }

    /** Null when the server asked for a wait longer than we are willing to sit through. */
    private fun waitMs(
        response: Response,
        retry: Int,
    ): Long? {
        val retryAfter = retryAfterMs(response) ?: return backoff.delayMs(retry)
        return retryAfter.takeIf { it <= maxRetryAfter.toMillis() }
    }

    /** `Retry-After` is either a number of seconds or an HTTP date; null when absent or unreadable. */
    private fun retryAfterMs(response: Response): Long? {
        val seconds = response.header("Retry-After")?.trim()?.toLongOrNull()
        val date = response.headers.getDate("Retry-After")
        val ms =
            when {
                seconds != null -> seconds.coerceAtMost(MAX_SECONDS) * MILLIS_PER_SECOND
                date != null -> date.time - (response.headers.getDate("Date")?.time ?: System.currentTimeMillis())
                else -> null
            }
        return ms?.coerceAtLeast(0)
    }

    private fun isRetryable(code: Int) =
        code == HTTP_TOO_MANY_REQUESTS || code == HTTP_OVERLOADED || code in SERVER_ERRORS

    private companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_MAX_RETRY_AFTER_SECONDS = 60L
        const val MILLIS_PER_SECOND = 1_000L

        /** Keeps `seconds * 1000` from overflowing on a absurd header value. */
        const val MAX_SECONDS = Long.MAX_VALUE / MILLIS_PER_SECOND
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_OVERLOADED = 529
        val SERVER_ERRORS = 500..599
    }
}
