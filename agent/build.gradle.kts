plugins {
    id("jobassistant.android.library")
}

dependencies {
    implementation(projects.ai)
    implementation(projects.sources)
    implementation(projects.portals)
    implementation(projects.email)
    implementation(projects.otp)
    implementation(projects.core.data)

    testImplementation(libs.kotlinx.coroutines.test)
}
