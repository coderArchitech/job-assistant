plugins {
    id("jobassistant.android.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    // Sources receive an OkHttpClient and the shared Json from :core:network.
    api(projects.core.network)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
