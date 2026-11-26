plugins {
    id("jobassistant.android.library")
}

dependencies {
    implementation(projects.ai)
    implementation(projects.core.security)
    implementation(projects.core.config)

    // Per-portal WebView profiles (ProfileStore), and WebViewAssetLoader in the runtime test.
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
