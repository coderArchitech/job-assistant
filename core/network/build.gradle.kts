plugins {
    id("jobassistant.android.library")
    id("jobassistant.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    // Callers receive OkHttpClient and Json from this module.
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging.interceptor)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
