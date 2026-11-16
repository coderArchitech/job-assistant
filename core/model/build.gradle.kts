plugins {
    id("jobassistant.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.javax.inject)
    // Value types stored as JSON columns are @Serializable; the JSON format itself lives in :core:data.
    api(libs.kotlinx.serialization.core)

    testImplementation(libs.kotlinx.serialization.json)
}
