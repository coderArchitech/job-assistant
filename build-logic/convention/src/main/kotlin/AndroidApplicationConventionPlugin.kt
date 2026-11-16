import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android application module: SDK levels, Java target, strict lint and static analysis. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("jobassistant.static.analysis")

            extensions.configure<ApplicationExtension> {
                namespace = NAMESPACE_ROOT
                compileSdk = libs.version("compileSdk").toInt()
                defaultConfig {
                    minSdk = libs.version("minSdk").toInt()
                    targetSdk = libs.version("targetSdk").toInt()
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
        }
    }
}
