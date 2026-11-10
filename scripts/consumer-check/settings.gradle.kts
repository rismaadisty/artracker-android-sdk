// Throw-away consumer used by scripts/verify-publication.sh. It stands in for a host app such as Alfagift or Apo:
// it resolves id.art.tracker:artracker-android ONLY from the Maven repository passed with -PartrackerRepo, with the
// same coordinates a real consumer declares, and compiles a call into the public API against the published AAR.
//
// Profiles (-PconsumerProfile=...):
//   current (default): AGP 9.0.1 with its built-in Kotlin, the same toolchain as the SDK build.
//   legacy:            AGP 8.13.0 + Kotlin Gradle plugin 2.0.21, the toolchain the SDK pins its language level for.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // pluginManagement is evaluated before the rest of this script, so the profile is read inline.
    val agp = if (providers.gradleProperty("consumerProfile").orNull == "legacy") "8.13.0" else "9.0.1"
    plugins {
        id("com.android.application") version agp
        id("org.jetbrains.kotlin.android") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The published artifact comes from the repository under test and from nowhere else.
        val artrackerRepo = providers.gradleProperty("artrackerRepo").orNull
            ?: error("Pass -PartrackerRepo=<maven repository directory> (see scripts/verify-publication.sh)")
        maven {
            url = uri(artrackerRepo)
            content { includeGroup("id.art.tracker") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "consumer-check"
include(":app")
if (providers.gradleProperty("consumerProfile").orNull == "legacy") {
    project(":app").buildFileName = "build-legacy.gradle.kts"
}
