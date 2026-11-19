plugins {
    id("jobassistant.android.library")
    id("jobassistant.android.hilt")
    alias(libs.plugins.protobuf)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    // Repository interfaces expose Flow.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.javalite)
    // Room: entities, DAOs and the exported schema (task T1.03).
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // JSON columns (lists, maps and value types) go through kotlinx.serialization.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    // In-memory database tests run on Robolectric's SQLite.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// The schema JSON for every version is committed; migration tests (T1.04) read it.
room {
    schemaDirectory("$projectDir/schemas")
}

// Robolectric's own downloader gives up on HTTP 429 from Maven Central. Gradle fetches the
// Android runtime jar instead (with retries and caching), and Robolectric runs offline from it.
val robolectricRuntime by configurations.creating {
    isTransitive = false
}

dependencies {
    robolectricRuntime(libs.robolectric.android.all)
}

val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val syncRobolectricJars by tasks.registering(Sync::class) {
    from(robolectricRuntime)
    into(robolectricJars)
}

tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.absolutePath)
}

// Settings are stored as protobuf lite messages generated from src/main/proto.
protobuf {
    protoc {
        artifact =
            libs.protobuf.protoc
                .get()
                .toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
