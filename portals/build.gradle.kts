plugins {
    id("jobassistant.android.library")
}

dependencies {
    implementation(projects.ai)
    implementation(projects.core.security)
    implementation(projects.core.config)

    // The runtime test serves its pages from the test APK through WebViewAssetLoader.
    androidTestImplementation(libs.androidx.webkit)
}
