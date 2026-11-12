# Using the ARTracker Android SDK from GitHub Packages

For app teams that consume the SDK (Alfagift, Apo, ...). Maintainers: see [`releasing.md`](releasing.md).

| | |
| --- | --- |
| Group ID | `id.art.tracker` |
| Artifact ID | `artracker-android` |
| Dependency | `id.art.tracker:artracker-android:1.0.1` |
| Packaging | `aar` (Gradle module metadata + POM, with `-sources` and `-javadoc` jars) |
| Registry URL | `https://maven.pkg.github.com/rismaadisty/artracker-android-sdk` |
| Requirements | `minSdk 24`, a host on Kotlin 2.0 or newer (verified with 2.0.21 and 2.2.10), JDK 17 for the Gradle build |

## Authentication is required, even though the repository is public

GitHub Packages (Maven) does **not** allow anonymous downloads. A public repository, and a public package, only decide
*who is allowed to read*; every request to `maven.pkg.github.com` must still carry credentials. Without them Gradle
fails with `401 Unauthorized`. So every developer machine and every CI job that builds an app depending on the SDK needs
a GitHub username and a token with the `read:packages` scope. The token only reads: it cannot publish.

### 1. Create a read token

1. GitHub, Settings, Developer settings, **Personal access tokens, Tokens (classic)**, *Generate new token (classic)*.
   (Fine-grained tokens cannot be used with GitHub Packages; use a classic token.)
2. Scope: **`read:packages`** only. Add `repo` only if the SDK repository or its package is private.
3. If the package is owned by an organisation that enforces SAML SSO, click *Configure SSO* next to the token and
   authorise it.
4. For CI use a token of a shared/service account, not a personal one, and store it as a CI secret.

### 2. Give Gradle the credentials (never commit them)

On a developer machine, put them in the **user-level** `~/.gradle/gradle.properties` (not the one inside the project):

```properties
gpr.user=<your GitHub username>
gpr.key=<token with read:packages>
```

In CI, expose the same values as secret environment variables, for example `GPR_USER` and `GPR_KEY` (the snippets below
read either). Do not echo them and do not write them into a file inside the repository.

### 3. Declare the repository

`settings.gradle.kts` (the usual setup with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`):

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "ARTrackerGitHubPackages"
            url = uri("https://maven.pkg.github.com/rismaadisty/artracker-android-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GPR_USER")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GPR_KEY")).orNull
            }
            // Send the credentials, and ask this registry, only for the SDK.
            content { includeGroup("id.art.tracker") }
        }
    }
}
```

Groovy (`settings.gradle`):

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "ARTrackerGitHubPackages"
            url = uri("https://maven.pkg.github.com/rismaadisty/artracker-android-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GPR_USER")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GPR_KEY")).orNull
            }
            content { includeGroup("id.art.tracker") }
        }
    }
}
```

If the project still declares repositories in the root `build.gradle` (`allprojects { repositories { ... } }`, no
`FAIL_ON_PROJECT_REPOS`), put the same `maven { ... }` block there instead.

### 4. Declare the dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("id.art.tracker:artracker-android:1.0.1")
}
```

Or with a version catalog (`gradle/libs.versions.toml`):

```toml
[versions]
artracker = "1.0.1"

[libraries]
artracker-android = { group = "id.art.tracker", name = "artracker-android", version.ref = "artracker" }
```

```kotlin
dependencies {
    implementation(libs.artracker.android)
}
```

Sync, then continue with the [README](../README.md#quick-start) (`ARTracker.init` in `Application.onCreate()`).

## What comes with the dependency

The AAR carries its own dependencies in the POM / Gradle metadata, so you add nothing else: `kotlin-stdlib` (2.0.21),
`kotlinx-coroutines-android` 1.9.0, `androidx.work:work-runtime-ktx` 2.10.0, `androidx.lifecycle:lifecycle-process` 2.9.4
and `androidx.core:core-ktx` 1.15.0. If your app uses newer versions of those, Gradle keeps the newer ones. OkHttp,
Fragment, Navigation and Compose are **not** dependencies: they are optional and only used when your app already has them
(see the README, "Requirements"). The AAR ships its R8/ProGuard rules (`consumer-rules.pro`), so no keep rules are needed
in your app.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `Received status code 401 from server: Unauthorized` | No credentials reached the request, or the token is wrong/expired. Check `gpr.user` / `gpr.key` (or `GPR_USER` / `GPR_KEY` in CI) and that the token has `read:packages`. |
| `403 Forbidden` | The token's account cannot read the package (private repository without access, or SSO not authorised for the token). Ask the SDK maintainers for access; authorise the token for SSO. |
| `Could not find id.art.tracker:artracker-android:1.0.1` / `404 Not Found` | Wrong URL (owner/repository in the path), a version that was not published yet, or the registry is not declared where Gradle looks (for example only in `allprojects` while `FAIL_ON_PROJECT_REPOS` is on). Check the URL above and the version on the repository's *Packages* page. |
| `401` although the properties look set | A property that is missing makes `.orNull` return `null`, and Gradle then sends no credentials. Define **both** `gpr.user` and `gpr.key`, and make sure they are in the user-level `~/.gradle/gradle.properties`, not only in another project. |
| `Module was compiled with an incompatible version of Kotlin` | The host's Kotlin compiler is older than 2.0. Upgrade the host to Kotlin 2.0+. |
| Build passes locally, fails in CI | CI does not have the secrets, or the job only exposes them to protected branches. Check the secret names against the snippet. |

Never put the token in `gradle.properties` inside the repository, in `settings.gradle(.kts)`, or in CI logs. If one
leaks, revoke it in GitHub (Settings, Developer settings) and create another.
