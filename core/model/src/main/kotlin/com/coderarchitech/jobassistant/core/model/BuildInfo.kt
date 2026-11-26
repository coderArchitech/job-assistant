package com.coderarchitech.jobassistant.core.model

/**
 * Facts about the running build: version, flavor (`dev` or `prod`), the config host URL, which is empty
 * until the config host exists (task T3.10), and the public keys its releases are signed with (D07).
 */
data class BuildInfo(
    val versionName: String,
    val flavor: String,
    val configHost: String,
    /** Compared with a config release's `minAppVersion`. */
    val versionCode: Int = 1,
    /** Base64 Ed25519 public keys, current then next; empty until the owner creates them. */
    val configKeys: List<String> = emptyList(),
) {
    /** True once a config host URL has been set for this flavor. */
    val isConfigHostSet: Boolean
        get() = configHost.isNotBlank()
}
