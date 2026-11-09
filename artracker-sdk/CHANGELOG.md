# Changelog

All notable changes to `id.art.tracker:artracker-android`. Format follows [Keep a Changelog](https://keepachangelog.com/);
versions follow semantic versioning (`0.x`: the API may still change).

## [Unreleased]

### Changed

- Session replay now uploads through the backend's Session Replay API instead of `replay_chunk` items in
  `POST /api/v1/ingest`: `POST /api/v1/replay/chunk-url`, `PUT` of the gzipped rrweb events to the returned signed URL,
  then `POST /api/v1/replay/chunk-ack`. Chunks are merged into per-session batches (256 KB or 10 s), queued in their own
  `artracker_replay.db` (20 MiB / 200 batches, 8 attempts, 7 days, `seq` persisted per session) and retried idempotently.
  `replay_chunk` handling was removed from the ingest pipeline.
- **Breaking for older backends:** a backend without the replay endpoints answers `404`, so replay batches are dropped and
  no replay is shown until the backend is upgraded. Ingest events are unaffected.
- Replay uploads always use the built-in `HttpURLConnection`: a custom `ARTrackerSender` (its contract is `POST` only) and
  therefore host certificate pinning, proxy or Chucker do not cover them.

### Added

- `NetworkConfig.sender` and the experimental `ARTrackerSender` (with `SendRequest` and `SendResponse`): replaces the
  `HttpURLConnection` transport that posts batches to the ingest endpoint, for example to inspect the SDK's traffic with
  Chucker, pin certificates or go through a proxy. Status mapping is shared with the built-in transport.
- `ARTrackerOkHttpSender(OkHttpClient)`: an `ARTrackerSender` on top of your own `OkHttpClient` (OkHttp stays
  `compileOnly`, with a consumer R8 rule like the interceptor). The SDK's own requests are tagged and
  `ARTrackerOkHttpInterceptor` skips them, so an interceptor on the same client cannot feed itself.
- `@ExperimentalARTrackerApi` (`RequiresOptIn`, warning level) marks APIs whose contract is not frozen yet.
- Demo app: debug builds deliver through an OkHttp client with Chucker 4.2.0 and a *Buka Chucker* action in the *Jaringan* lab.

## [0.1.0] - 2026-11-06

First release. **Not yet published to a public repository** (only `publishToMavenLocal` was exercised) and **not yet run
on a device, emulator or against a backend**: see "Verification status" and the README.

### Added

- `ARTracker.init(context, ARTrackerConfig)` and the public API: `identify`, `track`, `screen`, `trackNavController`,
  `log`, `captureException`, `flush`, `reset`, `optOut`, `startReplay` / `stopReplay`, `diagnostics`, `isInitialized`.
  Java builder `ARTrackerConfig.Builder`. Every call is no-op-safe before `init` and never throws into the host app.
- Sessions (30 min idle timeout, 24 h hard limit), `app_foreground` / `app_background`, automatic `screen_view` for
  Activities and (with AndroidX Fragment) Fragments, `@ARTrackerScreenName`.
- Crash reporting (synchronous atomic file write, sent on next launch, previous handler always chained), ANR and native
  crash reporting from `ApplicationExitInfo` (API 30+), breadcrumbs (30), error dedup by fingerprint.
- `ARTrackerOkHttpInterceptor` (OkHttp is `compileOnly`): `http_request` logs with a scrubbed URL, no bodies, no headers.
- Offline-first transport: SQLite queue (5 MB / 5000 rows, 7 days, priority eviction), gzip batches, backoff with full
  jitter and `Retry-After`, `401` kill-switch, WorkManager flush when the app goes to background.
- Privacy: layer 1 masking in the replay walker (`maskAllInputs = true` by default, passwords always masked,
  numeric/phone/sensitive-autofill inputs masked, WebView/Surface/Texture/Video/Map blocked, custom classes, per-view and
  Compose `artrackerMask()` / `artrackerUnmask()`, `android:tag="artracker-mask"`); layer 2 attribute redaction
  (sensitive keys, email, Luhn cards, JWT, `Bearer`, URL query params).
- Session replay (opt-in, default off, sample rate 0.1): wireframe capture of View and Compose hierarchies encoded as
  rrweb events, touch recording, 8 ms main-thread budget watchdog, chunks of at most 24 KB.
- Consumer ProGuard/R8 rules, publishing to Maven (`maven-publish`, `-sources.jar`, `-javadoc.jar`), README, this file.

### Artifact

- Coordinates `id.art.tracker:artracker-android:0.1.0`, `minSdk 24`.
- Release AAR size, measured on 2026-11-06 with `./gradlew :artracker-sdk:assembleRelease`: **398621 bytes**
  (about 389 KiB), not minified (`artracker-sdk/PERF.md` section 4 has the same figure).
- Runtime dependencies in the POM: `kotlin-stdlib`, `kotlinx-coroutines-android`, `androidx.work:work-runtime-ktx`,
  `androidx.lifecycle:lifecycle-process`, `androidx.core:core-ktx`. OkHttp, Compose, Fragment and Navigation are not
  dependencies.

### Verification status

- Verified: unit tests (JVM and Robolectric, counts in the README), `:app:assembleDebug`, R8-minified
  `:app:assembleRelease` of the sample app, stand-alone R8 on the AAR without the optional libraries,
  `publishToMavenLocal` into an isolated repository (`.aar`, `.pom`, `.module`, sources and javadoc jars present).
- NOT run: anything on a device or emulator, end-to-end against the backend (`docs/e2e-manual-test.md`), performance
  (`artracker-sdk/PERF.md`; `init` <= 2 ms, replay walk p95 <= 8 ms and background CPU < 1 % are targets, not results),
  playback of replay chunks in `rrweb-player`, instrumented tests.

### Known limitations

See "Known limitations & privacy notes" in the README. Most important: the `art_live_` key is public; `artrackerUnmask()`
inside a masked container re-exposes its subtree; crash items bypass `beforeSend` and key-based redaction; popup windows
do not inherit anchor masking; replay chunk `seq` restarts per process; multi-process apps are unsupported; Compose
capture was tested on 1.7.x only; replay text of views that are not masked is not value-redacted; the envelope
`app_version` is the version at send time; `TelemetryItem` and `Diagnostics` are data classes (0.x, no binary compatibility
guarantee).

### Publishing TODO(owner)

POM `licenses`, `developers` and `scm` are placeholders; no remote repository or credentials are configured; Maven Central
needs signing on top of this.
