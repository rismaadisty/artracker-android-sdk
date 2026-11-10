# Releasing the SDK to GitHub Packages

Maintainers only. Consumers: see [`github-packages.md`](github-packages.md).

Coordinates: `id.art.tracker:artracker-android:<version>`. Registry:
`https://maven.pkg.github.com/rismaadisty/artracker-android-sdk` (the build derives `owner/repo` from `GITHUB_REPOSITORY`
in CI, so a transferred or renamed repository needs no code change; the default and the documentation URLs would need
updating).

## How a release works

```
bump sdkVersion + CHANGELOG  ->  scripts/verify-publication.sh  ->  merge  ->  git tag vX.Y.Z  ->  git push origin vX.Y.Z
                                                                                        |
                                       .github/workflows/publish.yml  (only trigger: a tag matching v*)
                                       verify:  tag == v<sdkVersion>, unit tests, local-Maven validation (+ consumer apps)
                                       publish: publishReleasePublicationToGitHubPackagesRepository   (needs verify)
```

* The version has one source of truth: `val sdkVersion` in `artracker-sdk/build.gradle.kts` (also `BuildConfig.SDK_VERSION`,
  hence the `User-Agent` and the envelope's `sdk.version`). The tag must be `v` + that value, otherwise the workflow stops
  before building.
* The workflow uses only the built-in `GITHUB_TOKEN` (`packages: write` in the publish job, read-only elsewhere). There is
  **no secret to create** and no credential in the repository.
* GitHub Packages is immutable per version: publishing `1.0.0` twice fails with `409`. A bad release is fixed with a new
  patch version (`1.0.1`); do not delete and reuse a number.

## Validate locally first (no remote involved)

```sh
scripts/verify-publication.sh --legacy-consumer
```

It publishes into an isolated repository (`build/verify-m2`, your `~/.m2` is untouched) and checks:

1. the files: `.aar`, `.pom`, `.module`, `-sources.jar`, `-javadoc.jar`;
2. the POM: group, artifact, version, `aar` packaging, project url / scm, and that the dependencies are **exactly** the five
   runtime ones (`kotlin-stdlib`, `kotlinx-coroutines-android`, `work-runtime-ktx`, `lifecycle-process`, `core-ktx`), with
   no `compileOnly` library (OkHttp, Fragment, Navigation, Compose) leaking in and `kotlin-stdlib` still on 2.0.x;
3. the Gradle module metadata against the POM;
4. the AAR: manifest, `classes.jar`, packaged consumer ProGuard rules, `BuildConfig` carrying the same version;
5. throw-away consumer apps in `scripts/consumer-check` that resolve the artifact **only** from that repository, compile a
   call into the public API and build a minified release (R8 + consumer rules): AGP 9, and with `--legacy-consumer` also
   AGP 8.13 + Kotlin 2.0.21. Needs the Android SDK (`ANDROID_HOME` or `local.properties`) and network for the toolchains.

If you add or remove an `implementation` dependency of the SDK, update the expected set in the script together with it.
The `kotlin-stdlib` pin (`coreLibrariesVersion = "2.0.21"` in `artracker-sdk/build.gradle.kts`) is deliberate: AGP 9's
built-in Kotlin would otherwise publish `kotlin-stdlib:2.2.x` and break hosts on Kotlin 2.0.x.

## Cutting a release

1. Set `sdkVersion` in `artracker-sdk/build.gradle.kts`; in `artracker-sdk/CHANGELOG.md` replace the heading date with
   the release date; update the version in `README.md` and `docs/github-packages.md` (search for the old number).
2. `scripts/verify-publication.sh --legacy-consumer` must end with `All checks passed`.
3. Merge to `main`, then tag **the merged commit**:
   ```sh
   git tag -a v1.0.0 -m "ARTracker Android SDK 1.0.0"
   git push origin v1.0.0
   ```
4. Watch *Actions, Publish to GitHub Packages*. When it is green the package appears under the repository's *Packages*.
5. Check from a real consumer (a project configured as in `github-packages.md`) that `id.art.tracker:artracker-android:<version>` resolves.

## One-time manual setup

* **LICENSE**: the repository has none, and the POM declares no `<licenses>`. Decide the license, commit the file and add
  the block to the publication in `artracker-sdk/build.gradle.kts` before external teams depend on the SDK (the `TODO(owner)`
  comment marks the place).
* **Actions**: Actions must be enabled for the repository. If an organisation policy restricts the default `GITHUB_TOKEN`
  or forbids third-party actions, allow `actions/checkout`, `actions/setup-java`, `actions/upload-artifact` and
  `gradle/actions` (the workflow uses major-version tags).
* **First publish creates the package.** It is linked to this repository and inherits its visibility. If the repository is
  private, give each consumer team access under *Package settings, Manage access*. If it is public, anyone can read it,
  but still only with a token (see `github-packages.md`).
* **Consumer tokens**: each consumer team (Alfagift, Apo, ...) creates a `read:packages` classic token for its developers
  and CI, as described in `github-packages.md`. Nothing for them to set up on this side.
* **Optional protection**: add a tag ruleset for `v*` (Settings, Rules) so only maintainers can create release tags, and an
  *Environment* with required reviewers on the publish job if a second approval is wanted before every publish.
