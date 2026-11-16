import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Android library module: namespace from the Gradle path, SDK levels, strict lint, static analysis,
 * and the `unitTest` and `staticChecks` aggregate tasks.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("jobassistant.static.analysis")

            extensions.configure<LibraryExtension> {
                namespace = derivedNamespace
                compileSdk = libs.version("compileSdk").toInt()
                defaultConfig {
                    minSdk = libs.version("minSdk").toInt()
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = JAVA_TARGET
                    targetCompatibility = JAVA_TARGET
                }
                testOptions {
                    unitTests.isIncludeAndroidResources = true
                }
                lint { configureStrictLint() }
            }
            configureKotlinJvmTarget()
            allowModulesWithoutTests()

            dependencies {
                add("testImplementation", libs.library("junit4"))
            }

            tasks.register("unitTest") {
                group = "verification"
                description = "Runs JVM unit tests for this module."
                dependsOn("testDebugUnitTest")
            }
            tasks.register("staticChecks") {
                group = "verification"
                description = "Runs Android Lint, detekt and ktlint for this module."
                dependsOn("lintDebug", "detekt", "ktlintCheck")
            }
        }
    }
}
