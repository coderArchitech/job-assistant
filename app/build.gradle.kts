plugins {
    id("jobassistant.android.application")
    id("jobassistant.android.compose")
    id("jobassistant.android.hilt")
}

android {
    defaultConfig {
        applicationId = "com.coderarchitech.jobassistant"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "HTTP_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "HTTP_LOGGING", "false")
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "CONFIG_HOST", "\"${providers.gradleProperty("jobassistant.configHost.dev").get()}\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "CONFIG_HOST", "\"${providers.gradleProperty("jobassistant.configHost.prod").get()}\"")
        }
    }
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.data)
    implementation(projects.work)

    implementation(projects.feature.onboarding)
    implementation(projects.feature.home)
    implementation(projects.feature.jobs)
    implementation(projects.feature.review)
    implementation(projects.feature.applications)
    implementation(projects.feature.outreach)
    implementation(projects.feature.portals)
    implementation(projects.feature.report)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

tasks.register("unitTest") {
    group = "verification"
    description = "Runs JVM unit tests for the dev debug variant."
    dependsOn("testDevDebugUnitTest")
}

tasks.register("staticChecks") {
    group = "verification"
    description = "Runs Android Lint, detekt and ktlint for the app module."
    dependsOn("lintDevDebug", "detekt", "ktlintCheck")
}
