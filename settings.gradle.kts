pluginManagement {
    // The symbols plugin (artracker-gradle-plugin) is built from source when a build applies id("id.art.tracker.symbols").
    includeBuild("artracker-gradle-plugin")
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

rootProject.name = "artracker-android"
include(":app")
include(":artracker-sdk")
