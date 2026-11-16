package com.coderarchitech.jobassistant.spike

import android.util.Log

/** T1.01 spike: runs the client twice against a local mock server inside the shrunk app and logs the result. */
object SpikeRunner {
    fun runAndLog() {
        Thread {
            val server = SpikeMockServer.start()
            try {
                val url = SpikeMockServer.baseUrl(server)
                val t0 = System.nanoTime()
                val first = SpikeEntry.run(url)
                val t1 = System.nanoTime()
                val second = SpikeEntry.run(url)
                val t2 = System.nanoTime()
                val ok = SpikeEntry.VARIANT == "none" || (first.startsWith("models=1 score=82") && second.startsWith("models=1 score=82"))
                Log.i(
                    "SPIKE",
                    "result=${if (ok) "PASS" else "FAIL"} variant=${SpikeEntry.VARIANT} sdk=${android.os.Build.VERSION.SDK_INT} " +
                        "firstMs=${(t1 - t0) / 1_000_000} warmMs=${(t2 - t1) / 1_000_000} $first",
                )
            } catch (e: Throwable) {
                Log.e("SPIKE", "result=FAIL variant=${SpikeEntry.VARIANT} error=${e.javaClass.name}: ${e.message}", e)
            } finally {
                server.close()
            }
        }.start()
    }
}
