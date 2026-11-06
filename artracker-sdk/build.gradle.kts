plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// Single source of truth for the SDK version: it feeds BuildConfig.SDK_VERSION (User-Agent, envelope `sdk.version`)
// and the Maven publication below.
val sdkVersion = "0.1.0"

android {
    namespace = "id.art.tracker.sdk"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
            // No Javadoc jar: it needs Dokka, which is not set up (and AGP 9 built-in Kotlin has no Javadoc for
            // Kotlin sources). Maven Central requires one; see the TODO(owner) in the README publishing notes.
        }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.core.ktx)

    compileOnly(libs.okhttp)
    compileOnly(libs.androidx.fragment.ktx)
    compileOnly(libs.androidx.navigation.runtime.ktx)
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.ui)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.fragment.ktx)
    testImplementation(libs.androidx.navigation.runtime.ktx)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.androidx.compose.material3)
}

// Publishing: only mavenLocal by default (`./gradlew :artracker-sdk:publishToMavenLocal`). No remote repository and
// no credentials are configured here.
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "id.art.tracker"
                artifactId = "artracker-android"
                version = sdkVersion
                from(components["release"])

                pom {
                    name.set("ARTracker Android SDK")
                    description.set(
                        "Native Android SDK for ARTracker: events, logs, crash/ANR reporting, OkHttp network " +
                            "capture and privacy-masked session replay.",
                    )
                    // TODO(owner): replace with the real project URL, license and SCM before a public release.
                    url.set("https://github.com/tiofani03/artracker")
                    licenses {
                        license {
                            name.set("TODO(owner): license name")
                            url.set("TODO(owner): license URL")
                        }
                    }
                    developers {
                        developer {
                            id.set("TODO(owner)")
                            name.set("TODO(owner)")
                        }
                    }
                    scm {
                        url.set("https://github.com/tiofani03/artracker")
                    }
                }
            }
        }
        // Remote repository: TODO(owner) add one when a release target exists, with credentials from
        // ~/.gradle/gradle.properties or CI secrets (never committed), for example:
        //
        // repositories {
        //     maven {
        //         name = "releases"
        //         url = uri("https://TODO-owner.example/repository/releases/")
        //         credentials {
        //             username = providers.gradleProperty("artrackerRepoUser").orNull
        //             password = providers.gradleProperty("artrackerRepoPassword").orNull
        //         }
        //     }
        // }
    }
}
