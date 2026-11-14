# Changelog

All notable changes to `id.art.tracker:artracker-android`. Format follows [Keep a Changelog](https://keepachangelog.com/);
versions follow semantic versioning.

## [Unreleased]

### Fixed

- **Session replay masked the whole screen of a Compose page while a text field had focus.** A focused Compose text field
  makes its host view report `onCheckIsTextEditor() == true`, so the view rules took the host for an input and, with
  `maskAllInputs = true`, painted over all of it without reading the semantics tree. The host is no longer treated as an
  input by the view rules: its fields are masked one by one through `ComposeMaskRules`, and a host whose tree cannot be
  read is still masked as a whole.

## [1.0.1] - 2026-11-12

### Added

- **Session profiling (opt-in).** `ARTrackerConfig.performance = PerformanceConfig(enabled, sampleIntervalMs, trackFrames)`,
  off by default. While the app is in the foreground it samples process CPU %, RSS memory, fps / jank / frozen frames,
  thread count, battery and thermal state every `sampleIntervalMs` (500..10_000) and sends them in batches as
  `perf.samples` items (`ItemType.PERF`), plus `perf.memory_warning` and `perf.thermal_change` events. Nothing is sampled
  in the background, and nothing is created while it is disabled. See the README section "Performance monitoring".
  `perf.memory_warning` relies on trim levels 10 and 15, which Android 14+ no longer delivers. Adding the `performance`
  parameter to `ARTrackerConfig` also changes its Kotlin default-arguments constructor: Kotlin libraries compiled against
  1.0.0 must be recompiled (Java callers and apps are unaffected).

## [1.0.0] - 2026-11-10

### Added (distribution)

- Published to GitHub Packages as `id.art.tracker:artracker-android:1.0.0` (AAR, POM, Gradle module metadata, sources and
  Javadoc jars) by `.github/workflows/publish.yml` on a `v*` tag; consumers authenticate with a `read:packages` token
  even though the repository is public. See `docs/github-packages.md` and `docs/releasing.md`.
- `scripts/verify-publication.sh` validates the publication in an isolated local Maven repository and builds throw-away
  consumer apps against it before anything is published.

### Fixed (distribution)

- The published POM / Gradle metadata depended on `kotlin-stdlib:2.2.10` (AGP 9's built-in Kotlin), which made hosts on
  Kotlin 2.0.x fail with "binary version of its metadata is 2.2.0, expected version is 2.0.0". The stdlib dependency is now
  pinned to 2.0.21, the language level the AAR is compiled for.
- The POM carries the real project url and scm instead of `TODO` placeholders. It declares no license yet: the repository
  has no `LICENSE` file.

### Removed (breaking)

- **Wireframe replay is gone; real, masked screenshots are the only replay.** Removed: `ReplayMode` (and
  `ReplayConfig.mode`), the wireframe capture (`ViewWalker`, `ComposeWalker` and their style readers, icon rasterizer),
  `Wireframe` / `WireframeDiffer`, the rrweb encoder, `ReplayChunker`, `ReplayBatcher`, and the rrweb upload format.
  `ReplayConfig(enabled, sampleRate, framesPerSecond, quality)` is the whole configuration, and the SDK initialises
  straight into screenshot recording. Migrating: drop `mode = ...` from your `ReplayConfig`.
- **The SDK no longer reads any replay setting from the server.** `GET /api/v1/replay/config`, its cache, `max_fps`
  and the switch from wireframes to screenshots are removed. The only server signal left is on upload: `403
  {"error":"screenshot_disabled"}` on `chunk-url` (without a readable body it is treated the same) means the project owner
  switched screenshot replay off. The SDK drops the chunk, deletes the queued ones and stops recording in that process
  (there is no wireframe fallback any more); it never counts as a revoked key.
- `framesPerSecond` is clamped to 0.5..10 (2 recommended); there is no 4 fps wireframe ceiling.
- **Contract change of the experimental `ARTrackerSender`:** the `GET` (empty body) request for `/api/v1/replay/config`
  is gone. A sender now only sees `POST` and `PUT` requests with a body.
- `tools/replay-viewer` (the `rrweb-player` page and `validate-chunks.mjs`) is removed: nothing produces rrweb chunks
  any more.
- Chunks that are still queued from an older SDK as wireframe (rrweb) batches are discarded when `artracker_replay.db`
  is upgraded (version 3); the per-session `seq` counters stay.

### Added (screenshot replay)

- `ReplayConfig(quality = ReplayQuality.STANDARD)`. The dashboard shows what the user saw (real fonts, icons, photos,
  colors, movement). `ReplayQuality` presets (short side of the image / JPEG quality): `LOW` 480 px / 50, `STANDARD`
  720 px / 60, `HIGH` 1080 px / 75; never upscaled.
- **Masking happens on the device, before compression**: password / sensitive / explicitly masked views, `maskAllInputs`,
  `maskAllText`, `sensitiveViewClasses`, Compose semantics (a mask-only pass) and blocked views (WebView, SurfaceView,
  TextureView, VideoView, maps) are painted over with an opaque cross-stripe pattern on the full-size bitmap, then scaled
  and encoded. A frame whose masks cannot be collected is dropped, never sent unmasked. **`FLAG_SECURE` windows are never
  captured** (their area is masked and the `View.draw` fallback is never used for them). The keyboard is drawn by another
  process: its area is a flat grey "keyboard" placeholder.
- Capture: `PixelCopy` of the Activity window on API 26+ (`View.draw` on API 24-25 and when a copy fails; no `SurfaceView`
  content there), dialogs and popups drawn on top with their dim. Frames are taken only when the screen was drawn, two
  reused bitmaps, identical frames (CRC of a 64x64 downscale) are skipped, a 16 ms main-thread watchdog halves the rate.
- Data budget: about 5 MB per minute. A rolling 60 s byte window halves the fps (down to 0.5) when exceeded and, if the
  overrun persists, steps the quality down one preset for the session. Chunks (`artracker-screen-v1`, gzipped JSON of
  frames and touches) close at 10 s or 2 MB; uploads use the three-step Session Replay API with
  `"format":"artracker-screen-v1"` in the `chunk-url` request and work on any network.

### Changed (screenshot replay)

- `ReplayStore` (DB version 3): chunk bodies are files under `noBackupFilesDir/artracker/replay/` (the old BLOB column
  hit Android's ~2 MB `CursorWindow` limit); the table keeps metadata, the file name and the body `format`. The offline
  cap is now **50 MB** / 200 chunks (was 20 MiB). `ReplayResult.ScreenshotDisabled` and `ReplayQueue.clearFormat` are
  internal.
- `FrameScheduler`: configurable fps ceiling and an outside fps cap.
- **Contract of the experimental `ARTrackerSender`:** return the body of error responses too: a `403` on a `chunk-url`
  carries `screenshot_disabled` there (without a readable body it is still treated as that, never as a revoked key).

### Changed

- Session replay now uploads through the backend's Session Replay API instead of `replay_chunk` items in
  `POST /api/v1/ingest`: `POST /api/v1/replay/chunk-url`, `PUT` of the gzipped chunk to the returned signed URL,
  then `POST /api/v1/replay/chunk-ack`. Chunks are queued in their own `artracker_replay.db` (8 attempts, 7 days, `seq`
  persisted per session) and retried idempotently. `replay_chunk` handling was removed from the ingest pipeline.
- **Breaking for older backends:** a backend without the replay endpoints answers `404`, so replay chunks are dropped and
  no replay is shown until the backend is upgraded. Ingest events are unaffected.
- Replay uploads go through `NetworkConfig.sender` when one is set, so Chucker, certificate pinning and proxies cover them
  too. **Contract change of the experimental `ARTrackerSender`:** `SendRequest` gained `method` (`POST`, or `PUT` for the
  replay upload; default `POST`) and `SendResponse` gained `body` (the start of the response, up to 16 KiB; the replay
  `chunk-url` step needs it). A custom sender must honour the method and return the body, otherwise the replay upload
  fails (retried, then dropped); ingest is unaffected. `ARTrackerOkHttpSender` already does both. Without a sender the
  built-in `HttpURLConnection` is used, as before.

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
