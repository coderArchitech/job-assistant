pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        // Plugin markers live on the Plugin Portal; asking it first avoids failed lookups on Maven Central.
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "job-assistant"

include(":app")

include(":core:ui")
include(":core:model")
include(":core:data")
include(":core:security")
include(":core:network")
include(":core:config")

include(":ai")
include(":sources")
include(":portals")
include(":otp")
include(":email")
include(":agent")
include(":work")

include(":feature:onboarding")
include(":feature:home")
include(":feature:jobs")
include(":feature:review")
include(":feature:applications")
include(":feature:outreach")
include(":feature:portals")
include(":feature:report")
include(":feature:settings")
