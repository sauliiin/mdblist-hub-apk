pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Lets modules refer to each other as `projects.core.model` instead of
// stringly-typed paths, so a renamed module fails at configuration time.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "mdblist-hub-tv"

include(
    ":app",
    ":core:model",
    ":core:network",
    ":core:database",
    ":core:data",
    ":core:ui",
    ":player",
    // Not shipped. Builds a `com.android.test` APK that drives the release
    // app on a device to record which code its startup actually runs; the
    // result is baked into the release APK by the plugin.
    ":baselineprofile",
)
