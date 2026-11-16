package com.coderarchitech.jobassistant.spike

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the spike client inside the R8-shrunk app on a device and reports first-call and warm-call times. */
@RunWith(AndroidJUnit4::class)
class SdkSpikeDeviceTest {
    @Test
    fun clientWorksAfterR8AndReportsTimings() {
        val server = SpikeMockServer.start()
        try {
            val url = SpikeMockServer.baseUrl(server)
            val t0 = System.nanoTime()
            val first = SpikeEntry.run(url)
            val t1 = System.nanoTime()
            val second = SpikeEntry.run(url)
            val t2 = System.nanoTime()
            val result =
                "variant=${SpikeEntry.VARIANT} sdk=${android.os.Build.VERSION.SDK_INT} " +
                    "firstMs=${(t1 - t0) / 1_000_000} warmMs=${(t2 - t1) / 1_000_000} $first"
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("SPIKE", result) })
            if (SpikeEntry.VARIANT != "none") {
                assertTrue(first, first.startsWith("models=1 score=82"))
                assertTrue(second, second.startsWith("models=1 score=82"))
            }
        } finally {
            server.close()
        }
    }
}
