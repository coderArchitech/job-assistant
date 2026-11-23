package com.coderarchitech.jobassistant.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Runs this call without blocking the caller's thread. Cancelling the coroutine cancels the call, so a
 * stopped run frees its connection at once. Fails with an [IOException] like [Call.execute]; the caller
 * closes the returned [Response].
 */
suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    // If the coroutine was cancelled meanwhile nobody will read the response; close it.
                    continuation.resume(response) { response.close() }
                }

                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            },
        )
    }
