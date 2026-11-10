// Profile `legacy`: AGP 8 + Kotlin Gradle plugin 2.0.21. Selected by settings.gradle.kts.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.art.tracker.consumercheck"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.art.tracker.consumercheck"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            // R8 with the consumer rules packaged in the AAR, in an app that has none of the optional libraries.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Exactly what a consumer writes.
    implementation("id.art.tracker:artracker-android:${providers.gradleProperty("artrackerVersion").get()}")
}
