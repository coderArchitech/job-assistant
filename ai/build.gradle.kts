plugins {
    id("jobassistant.android.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // api: LlmRequest names AiTask, and OutputSchema exposes JsonObject and KSerializer.
    api(projects.core.model)
    api(libs.kotlinx.serialization.json)
    implementation(projects.core.network)

    testImplementation(libs.kotlinx.coroutines.test)
}
