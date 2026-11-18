plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.2.0"
}

group = "id.art.tracker"
version = "1.0.3"

kotlin {
    jvmToolchain(17)
    explicitApi()
    compilerOptions {
        // gradleApi() carries Kotlin 2.2 metadata (Gradle 9.1), so the compiler must be 2.2; the language/API level stays
        // 2.0 so the plugin also runs on the Kotlin stdlib that older Gradle versions (8.x) ship.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
