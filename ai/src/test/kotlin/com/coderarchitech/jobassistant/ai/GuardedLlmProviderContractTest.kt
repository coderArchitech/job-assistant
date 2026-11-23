package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.core.security.scan.ScanResult
import com.coderarchitech.jobassistant.core.security.scan.SecretScanner

/** The guard must not change what any provider outcome means: with a clean scan it passes everything through. */
class GuardedLlmProviderContractTest : LlmProviderContract() {
    private val cleanScanner =
        object : SecretScanner {
            override suspend fun scan(texts: List<String>): ScanResult = ScanResult.Clean
        }

    override fun provider(scenario: Scenario): LlmProvider = GuardedLlmProvider(FakeLlmProvider(scenario), cleanScanner)
}
