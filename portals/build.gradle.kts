plugins {
    id("jobassistant.android.library")
    // Adapter manifests (T3.11) are read with kotlinx.serialization.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(projects.ai)
    implementation(projects.core.security)
    implementation(projects.core.config)

    // Per-portal WebView profiles (ProfileStore), and WebViewAssetLoader in the runtime test.
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json) // The JS bridge messages.
    androidTestImplementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
}
