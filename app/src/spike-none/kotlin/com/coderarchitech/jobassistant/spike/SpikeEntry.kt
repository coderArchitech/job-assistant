package com.coderarchitech.jobassistant.spike

/** T1.01 spike baseline: no AI client at all. */
object SpikeEntry {
    const val VARIANT = "none"
    const val BASE_PATH = ""

    fun run(baseUrl: String): String = "models=0 score=0 reason=$baseUrl"
}
