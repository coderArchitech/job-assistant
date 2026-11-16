import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Pure Kotlin module with no Android dependency, such as `:core:model`. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("jobassistant.static.analysis")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JAVA_TARGET
                targetCompatibility = JAVA_TARGET
            }
            configureKotlinJvmTarget()
            allowModulesWithoutTests()

            dependencies {
                add("testImplementation", libs.library("junit4"))
            }

            tasks.register("unitTest") {
                group = "verification"
                description = "Runs JVM unit tests for this module."
                dependsOn("test")
            }
            tasks.register("staticChecks") {
                group = "verification"
                description = "Runs detekt and ktlint for this module."
                dependsOn("detekt", "ktlintCheck")
            }
        }
    }
}
