plugins {
    id("jobassistant.android.library")
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.network)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
