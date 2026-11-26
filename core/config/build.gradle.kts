plugins {
    id("jobassistant.android.library")
    id("jobassistant.android.hilt")
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.network)
    implementation(projects.core.security)
    // Ed25519 signature checks (D07); java.security has Ed25519 only from API 33.
    implementation(libs.tink.android)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
