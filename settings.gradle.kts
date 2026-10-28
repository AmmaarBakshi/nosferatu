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

rootProject.name = "Nosferatu"

// Pure Kotlin running intelligence: filtering, classification, map matching. No Android, no map internals.
include(":engine")
// The BombayChitra adapter: reads BombayChitra's routing graph, styles and dataset releases.
include(":bombaychitra")
// The Android app: sensors, tracking service, BombayChitra map rendering and the UI.
include(":app")
