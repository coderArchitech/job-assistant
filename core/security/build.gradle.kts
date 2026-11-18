plugins {
    id("jobassistant.android.library")
    id("jobassistant.android.hilt")
    alias(libs.plugins.protobuf)
}

dependencies {
    api(projects.core.model)
    // The Vault interface exposes suspend functions; callers need coroutines anyway.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.javalite)
    implementation(libs.tink.android)

    testImplementation(libs.kotlinx.coroutines.test)
}

// The vault file is a protobuf lite message generated from src/main/proto.
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
