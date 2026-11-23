plugins {
    id("jobassistant.android.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // api: LlmRequest names AiTask, and OutputSchema exposes JsonObject and KSerializer.
    api(projects.core.model)
    api(libs.kotlinx.serialization.json)
    implementation(projects.core.network)
    // The prompt guard scans requests for vault values and the last OTP (T1.15).
    implementation(projects.core.security)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
