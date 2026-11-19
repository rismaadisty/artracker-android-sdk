// Profile `current`: AGP 9 has built-in Kotlin, so no Kotlin plugin is applied. See build-legacy.gradle.kts.
plugins {
    id("com.android.application")
}

// full (default): the SDK is a dependency and src/withSdk is compiled. none: no SDK, src/withoutSdk is compiled instead.
val sdkMode = providers.gradleProperty("sdkMode").orElse("full").get()
require(sdkMode == "full" || sdkMode == "none") { "-PsdkMode must be full or none, got '$sdkMode'" }

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
    sourceSets.getByName("main").java.srcDir(if (sdkMode == "full") "src/withSdk/java" else "src/withoutSdk/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Exactly what a consumer writes. -PsdkMode=none drops it (baseline build for scripts/measure-size.sh).
    if (sdkMode == "full") {
        implementation("id.art.tracker:artracker-android:${providers.gradleProperty("artrackerVersion").get()}")
    }
}
