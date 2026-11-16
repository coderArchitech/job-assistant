plugins {
    `kotlin-dsl`
}

group = "com.coderarchitech.jobassistant.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.ktlint.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "jobassistant.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "jobassistant.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "jobassistant.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "jobassistant.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = "jobassistant.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = "jobassistant.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("staticAnalysis") {
            id = "jobassistant.static.analysis"
            implementationClass = "StaticAnalysisConventionPlugin"
        }
    }
}
