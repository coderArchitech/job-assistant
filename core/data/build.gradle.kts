plugins {
    id("jobassistant.android.library")
    id("jobassistant.android.hilt")
    alias(libs.plugins.protobuf)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.serialization)
    id("jobassistant.android.robolectric")
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
    implementation(libs.timber)

    testImplementation(libs.kotlinx.coroutines.test)
    // In-memory database tests run on Robolectric's SQLite.
    // MigrationTestHelper (task T1.04) runs on Robolectric too, so migration tests run on every push.
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.runner)
}

// MigrationTestHelper reads the exported schema JSON as assets; the Room plugin only adds them to
// the instrumented-test assets, so the JVM tests get them here.
android {
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
}

// The schema JSON for every version is committed; the migration tests read it.
room {
    schemaDirectory("$projectDir/schemas")
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
