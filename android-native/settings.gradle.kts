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
)
