package com.coderarchitech.jobassistant.core.model

/**
 * Facts about the running build: version, flavor (`dev` or `prod`) and the config host URL,
 * which is empty until the config host exists (task T3.10).
 */
data class BuildInfo(
    val versionName: String,
    val flavor: String,
    val configHost: String,
) {
    /** True once a config host URL has been set for this flavor. */
    val isConfigHostSet: Boolean
        get() = configHost.isNotBlank()
}
