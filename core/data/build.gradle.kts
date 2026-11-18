plugins {
    id("jobassistant.android.library")
    id("jobassistant.android.hilt")
    alias(libs.plugins.protobuf)
}

dependencies {
    api(projects.core.model)
    // Repository interfaces expose Flow.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.javalite)

    testImplementation(libs.kotlinx.coroutines.test)
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
