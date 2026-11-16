plugins {
    id("jobassistant.android.application")
    id("jobassistant.android.compose")
    id("jobassistant.android.hilt")
}

// ---- T1.01 spike (spike branch only): -Pspike=none|anthropic|openai|thin ----
val spikeVariant = providers.gradleProperty("spike").getOrElse("none")
if (spikeVariant == "thin") apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

/** Config host URL for a flavor from gradle.properties, quoted for `buildConfigField`. */
fun configHost(flavor: String): String = "\"${providers.gradleProperty("jobassistant.configHost.$flavor").get()}\""

/**
 * Release signing with the Play upload key (task T0.07). Each value comes from an environment
 * variable (CI) or from a Gradle property in the developer's own ~/.gradle/gradle.properties.
 * Never from this repository. Without all four values, release builds are left unsigned.
 */
fun uploadValue(
    envName: String,
    propertyName: String,
): String? =
    providers
        .environmentVariable("JOBASSISTANT_UPLOAD_$envName")
        .orElse(providers.gradleProperty("jobassistant.upload.$propertyName"))
        .orNull
        ?.takeIf { it.isNotBlank() }

val uploadStoreFile = uploadValue("STORE_FILE", "storeFile")
val uploadStorePassword = uploadValue("STORE_PASSWORD", "storePassword")
val uploadKeyAlias = uploadValue("KEY_ALIAS", "keyAlias")
val uploadKeyPassword = uploadValue("KEY_PASSWORD", "keyPassword")
val uploadValues = listOf(uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword)
if (uploadValues.any { it != null } && uploadValues.any { it == null }) {
    throw GradleException(
        "Release signing needs all four upload-key values (store file, store password, key alias, " +
            "key password). Only some are set.",
    )
}

android {
    defaultConfig {
        applicationId = "com.coderarchitech.jobassistant"
        // Play needs a higher versionCode for every upload; the release workflow passes its run number.
        versionCode = providers.environmentVariable("JOBASSISTANT_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = file(uploadStoreFile)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
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
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "CONFIG_HOST", configHost("dev"))
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "CONFIG_HOST", configHost("prod"))
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

// Robolectric's own downloader gives up on HTTP 429 from Maven Central. Gradle fetches the
// Android runtime jar instead (with retries and caching), and Robolectric runs offline from it.
val robolectricRuntime by configurations.creating {
    isTransitive = false
}

dependencies {
    robolectricRuntime(libs.robolectric.android.all)
}

val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val syncRobolectricJars by tasks.registering(Sync::class) {
    from(robolectricRuntime)
    into(robolectricJars)
}

tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.absolutePath)
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

// ---- T1.01 spike (spike branch only) ----
android {
    buildTypes {
        getByName("release") { proguardFiles("spike-proguard-rules.pro") }
        create("spike") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    sourceSets {
        getByName("main").kotlin.srcDirs("src/spike-$spikeVariant/kotlin", "src/spikeShared/kotlin", "src/spikeRunner/kotlin")
        getByName("test").kotlin.srcDirs("src/spikeTest/kotlin")
    }
}

dependencies {
    when (spikeVariant) {
        "anthropic" -> implementation("com.anthropic:anthropic-java:3.3.0")
        "openai" -> implementation("com.openai:openai-java:4.94.0")
        "thin" -> {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
    }
    // Every variant uses the same OkHttp, as the app will.
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    // The on-device run starts the mock server inside the app itself (spike only).
    implementation("com.squareup.okhttp3:mockwebserver3:5.3.2")
}
