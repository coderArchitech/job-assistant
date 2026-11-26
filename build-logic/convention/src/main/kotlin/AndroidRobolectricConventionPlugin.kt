import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * JVM unit tests on Robolectric, run offline. Robolectric's own downloader gives up on HTTP 429 from Maven
 * Central, so Gradle fetches the Android runtime jar instead (with retries and caching) and Robolectric
 * reads it from the build directory.
 */
class AndroidRobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val runtime =
                configurations.create("robolectricRuntime") {
                    isTransitive = false
                }
            dependencies {
                add("testImplementation", libs.library("robolectric"))
                add("testImplementation", libs.library("androidx-test-core"))
                add(runtime.name, libs.library("robolectric-android-all"))
            }

            val jars = layout.buildDirectory.dir("robolectric-jars")
            val sync =
                tasks.register<Sync>("syncRobolectricJars") {
                    from(runtime)
                    into(jars)
                }
            tasks.withType<Test>().configureEach {
                dependsOn(sync)
                systemProperty("robolectric.offline", "true")
                systemProperty("robolectric.dependency.dir", jars.get().asFile.absolutePath)
            }
        }
    }
}
