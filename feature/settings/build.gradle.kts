plugins {
    id("jobassistant.android.feature")
    // Compose UI and screenshot tests run on the JVM (Robolectric); Roborazzi records light and dark images.
    id("jobassistant.android.robolectric")
    alias(libs.plugins.roborazzi)
}

android {
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(libs.compose.material.icons.core)
    // The signed price table, for the price editor (T3.09).
    implementation(projects.core.config)
    // FallbackPricing, which the empty price list explains.
    implementation(projects.ai)

    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
