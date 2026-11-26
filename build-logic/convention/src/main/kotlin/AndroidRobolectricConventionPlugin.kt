import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/**
 * JVM unit tests on Robolectric, run offline. Robolectric's own downloader gives up on HTTP 429 from Maven
 * Central, so Gradle fetches the Android runtime jar instead (with retries and caching) and Robolectric
 * reads it from the build directory. Works whichever order it is applied in with the Android plugin.
 */
class AndroidRobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.withPlugin("com.android.base") {
                val runtime = configurations.create("robolectricRuntime") { isTransitive = false }
                dependencies {
                    add("testImplementation", libs.library("robolectric"))
                    add("testImplementation", libs.library("androidx-test-core"))
                    add(runtime.name, libs.library("robolectric-android-all"))
                }

                val jarDir = layout.buildDirectory.dir("robolectric-jars")
                val sync =
                    tasks.register<Sync>("syncRobolectricJars") {
                        from(runtime)
                        into(jarDir)
                    }
                tasks.withType<Test>().configureEach {
                    jvmArgumentProviders += RobolectricJars(files(sync), jarDir.get().asFile)
                }
            }
        }
    }
}

/**
 * Hands Robolectric the jar directory. The jars are an input by content and relative path, and the
 * directory's absolute path is not an input, so where the checkout lives does not change the cache key.
 */
private class RobolectricJars(
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val jars: FileCollection,
    private val dir: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-Drobolectric.offline=true", "-Drobolectric.dependency.dir=${dir.absolutePath}")
}
