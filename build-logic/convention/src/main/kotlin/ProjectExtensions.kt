import com.android.build.api.dsl.Lint
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/** Root package; module namespaces are derived from the Gradle path (`:core:data` -> `<root>.core.data`). */
internal const val NAMESPACE_ROOT = "com.coderarchitech.jobassistant"

/** Java and Kotlin bytecode target for every module. */
internal val JAVA_TARGET = JavaVersion.VERSION_17

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

/** Namespace for an Android module, derived from its Gradle path. */
internal val Project.derivedNamespace: String
    get() = NAMESPACE_ROOT + path.replace(':', '.').replace("-", "")

internal fun Project.configureKotlinJvmTarget() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(JAVA_TARGET.toString()))
    }
}

/** Android Lint: warnings fail the build. Checks that only report newer versions are off. */
internal fun Lint.configureStrictLint() {
    warningsAsErrors = true
    abortOnError = true
    checkDependencies = false
    disable +=
        setOf(
            // Version-bump reminders; upgrades are deliberate (see libs.versions.toml).
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            // targetSdk 36 is the Play requirement chosen in the design, not an oversight.
            "OldTargetApi",
        )
}

/** Most modules are still empty shells, so a test task that finds no tests is not an error yet. */
internal fun Project.allowModulesWithoutTests() {
    tasks.withType<Test>().configureEach {
        failOnNoDiscoveredTests.set(false)
    }
}
