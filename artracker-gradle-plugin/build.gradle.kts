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
    // Compile-only: the host build brings its own AGP. 8.0 is the minimum supported version.
    compileOnly("com.android.tools.build:gradle-api:8.0.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("symbols") {
            id = "id.art.tracker.symbols"
            implementationClass = "id.art.tracker.gradle.ArtrackerSymbolsPlugin"
            displayName = "Artracker symbols"
            description = "Uploads the R8/ProGuard mapping.txt to Artracker after every minified Android build."
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// Functional tests run real Android builds through Gradle TestKit; they need ANDROID_HOME and network access
// (the fixture resolves AGP), so they are a separate task and are skipped without ANDROID_HOME.
val functionalTest: SourceSet by sourceSets.creating
configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
gradlePlugin.testSourceSets(functionalTest)

tasks.register<Test>("functionalTest") {
    description = "Runs the TestKit functional tests against a fixture Android app."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    // The fixture applies this plugin from source through an included build, exactly like a host app would.
    systemProperty("artracker.pluginDir", projectDir.absolutePath)
    inputs.dir("src/main")
    maxParallelForks = 1
    testLogging { events("failed", "skipped") }
}
