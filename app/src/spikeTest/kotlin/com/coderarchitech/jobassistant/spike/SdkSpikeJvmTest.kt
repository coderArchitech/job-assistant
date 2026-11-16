package com.coderarchitech.jobassistant.spike

import org.junit.Assert.assertTrue
import org.junit.Test

class SdkSpikeJvmTest {
    @Test
    fun clientParsesTheMockedReplies() {
        val server = SpikeMockServer.start()
        try {
            val summary = SpikeEntry.run(SpikeMockServer.baseUrl(server))
            println("SPIKE_JVM variant=${SpikeEntry.VARIANT} $summary")
            if (SpikeEntry.VARIANT != "none") assertTrue(summary, summary.startsWith("models=1 score=82"))
        } finally {
            server.close()
        }
    }
}
