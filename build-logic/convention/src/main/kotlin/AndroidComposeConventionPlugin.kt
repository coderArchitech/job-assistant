import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType

/** Jetpack Compose for an Android application or library module. Apply after the base plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.findByType<ApplicationExtension>()?.buildFeatures?.compose = true
            extensions.findByType<LibraryExtension>()?.buildFeatures?.compose = true

            dependencies {
                val bom = platform(libs.library("compose-bom"))
                add("implementation", bom)
                add("androidTestImplementation", bom)
                add("implementation", libs.library("compose-ui"))
                add("implementation", libs.library("compose-material3"))
                add("implementation", libs.library("compose-ui-tooling-preview"))
                add("debugImplementation", libs.library("compose-ui-tooling"))
                add("debugImplementation", libs.library("compose-ui-test-manifest"))
            }
        }
    }
}
