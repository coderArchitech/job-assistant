import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.dependencies

/**
 * A `:feature:*` module: Android library with Compose, Hilt and ViewModels.
 *
 * Enforces the module rule from docs/TECHNICAL_DESIGN.md: a feature module never depends on
 * another feature module. Shared code goes down into `:core:*` or the domain modules instead.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("jobassistant.android.library")
            pluginManager.apply("jobassistant.android.compose")
            pluginManager.apply("jobassistant.android.hilt")

            dependencies {
                add("implementation", project(":core:ui"))
                add("implementation", project(":core:model"))
                add("implementation", project(":core:data"))
                add("implementation", project(":agent"))
                add("implementation", libs.library("androidx-lifecycle-runtime-compose"))
                add("implementation", libs.library("androidx-lifecycle-viewmodel-compose"))
                add("implementation", libs.library("androidx-hilt-lifecycle-viewmodel-compose"))
                add("testImplementation", libs.library("kotlinx-coroutines-test"))
            }

            val featurePath = path
            configurations.configureEach {
                dependencies.withType(ProjectDependency::class.java).configureEach {
                    if (path.startsWith(":feature:") && path != featurePath) {
                        throw GradleException(
                            "$featurePath must not depend on $path: feature modules never depend on each " +
                                "other. Move the shared code to a :core:* or domain module.",
                        )
                    }
                }
            }
        }
    }
}
