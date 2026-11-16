plugins {
    id("jobassistant.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // api: the @IoDispatcher qualifier is meta-annotated with javax.inject.Qualifier.
    api(libs.javax.inject)
    // Value types stored as JSON columns are @Serializable; the JSON format itself lives in :core:data.
    api(libs.kotlinx.serialization.core)

    testImplementation(libs.kotlinx.serialization.json)
}
