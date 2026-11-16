import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/** detekt and ktlint with the shared configuration in `config/detekt` and `.editorconfig`. */
class StaticAnalysisConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            extensions.configure<DetektExtension> {
                buildUponDefaultConfig = true
                parallel = true
                config.setFrom(rootDir.resolve("config/detekt/detekt.yml"))
            }
            // detekt 1.23.8 runs on the Kotlin compiler it was built with; keep Gradle from
            // upgrading it to the project's Kotlin version.
            val detektKotlin = libs.version("detekt-kotlin")
            configurations.matching { it.name == "detekt" }.configureEach {
                resolutionStrategy.eachDependency {
                    if (requested.group == "org.jetbrains.kotlin") useVersion(detektKotlin)
                }
            }

            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            extensions.configure<KtlintExtension> {
                android.set(true)
                filter {
                    exclude { it.file.path.contains("/build/") }
                }
            }
        }
    }
}
