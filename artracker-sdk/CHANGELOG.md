# Changelog

All notable changes to `id.art.tracker:artracker-android`. Format follows [Keep a Changelog](https://keepachangelog.com/);
versions follow semantic versioning (`0.x`: the API may still change).

## [Unreleased]

### Added (screenshot replay)

- **Screenshot replay mode** (opt-in): `ReplayConfig(mode = ReplayMode.SCREENSHOT, quality = ReplayQuality.STANDARD)`.
  The dashboard shows what the user saw (real fonts, icons, photos, colors, movement) instead of a rebuilt wireframe.
  `ReplayMode.WIREFRAME` (the default) is the previous behaviour; nothing changes unless you opt in. `framesPerSecond` is
  clamped to 0.5..4 for wireframes and 0.5..10 for screenshots (2 recommended). `ReplayQuality` presets (short side of the
  image / JPEG quality): `LOW` 480 px / 50, `STANDARD` 720 px / 60, `HIGH` 1080 px / 75; never upscaled.
- **The project owner controls it from the server**: `GET /api/v1/replay/config` answers `{screenshot_enabled, max_fps}`
  (cached, refreshed at every replay start). Until the first answer a session records wireframes, then switches. A
  `403 {"error":"screenshot_disabled"}` on `chunk-url` (or a "disabled" answer) switches the running session back to
  wireframes, deletes the queued screenshot chunks and forces a full snapshot. `max_fps` caps the capture rate.
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
  frames and touches) close at 10 s or 2 MB; uploads use the same three-step API with `"format":"artracker-screen-v1"`
  in the `chunk-url` request and work on any network.

### Changed (screenshot replay)

- `ReplayStore` (DB version 2): batch bodies are files under `noBackupFilesDir/artracker/replay/` (the old BLOB column
  hit Android's ~2 MB `CursorWindow` limit); the table keeps metadata, the file name and the body `format`. Existing
  BLOB rows are written out to files on upgrade. The offline cap is now **50 MB** / 200 batches (was 20 MiB).
  `ReplayResult.ScreenshotDisabled` and `ReplayQueue.clearFormat` are internal.
- `FrameScheduler`: configurable fps ceiling and an outside fps cap.

### Fixed (wireframe replay)

- `RrwebEncoder` mutations sent a whole subtree as one nested `adds` entry, so later adds could not find their parent
  (`Node ... not found` in the player). Now one `adds` entry per node, pre-order, parent first, with element nodes sent
  without their children, the text node as its own entry and `nextId` only on the subtree root.
- Text uses `font-family:Roboto,<generic>` instead of the bare generic family.
- `overflow:hidden` is written only for nodes that clip (`clipToOutline`, scroll containers, Compose clip); other boxes
  no longer cut off titles and icons measured larger than their box.

### Changed

- Session replay now uploads through the backend's Session Replay API instead of `replay_chunk` items in
  `POST /api/v1/ingest`: `POST /api/v1/replay/chunk-url`, `PUT` of the gzipped rrweb events to the returned signed URL,
  then `POST /api/v1/replay/chunk-ack`. Chunks are merged into per-session batches (256 KB or 10 s), queued in their own
  `artracker_replay.db` (20 MiB / 200 batches, 8 attempts, 7 days, `seq` persisted per session) and retried idempotently.
  `replay_chunk` handling was removed from the ingest pipeline.
- **Breaking for older backends:** a backend without the replay endpoints answers `404`, so replay batches are dropped and
  no replay is shown until the backend is upgraded. Ingest events are unaffected.
- Replay uploads go through `NetworkConfig.sender` when one is set, so Chucker, certificate pinning and proxies cover them
  too. **Contract change of the experimental `ARTrackerSender`:** `SendRequest` gained `method` (`POST`, or `PUT` for the
  replay upload; default `POST`) and `SendResponse` gained `body` (the start of the response, up to 16 KiB; the replay
  `chunk-url` step needs it). A custom sender must honour the method and return the body, otherwise the replay upload
  fails (retried, then dropped); ingest is unaffected. `ARTrackerOkHttpSender` already does both. Without a sender the
  built-in `HttpURLConnection` is used, as before.

### Added

- Session replay now carries the app's own look, not only boxes and text: view backgrounds (`ColorDrawable`,
  `GradientDrawable` with gradient, radius and stroke, Material shape drawables), Compose fills, gradients, rounded
  shapes, borders, shadows and clips, elevation, font weight / italic / alignment / multi-line wrapping, and layout nodes
  without semantics (a `Box` with `Modifier.background`). The rrweb snapshot also ships a small default `<style>`
  (sans-serif, single-line text) so a chunk renders sensibly in any player.
- **Privacy change:** small **vector icons** (`VectorDrawable`, `VectorDrawableCompat`, Compose `VectorPainter` /
  `ColorPainter`, intrinsic size up to 64 dp) are now rasterized at their own size and sent as WebP/PNG data URIs (at
  most 6 KB each; cached per source, variant and pixel content; at most 24 new encodes per frame). **Bitmaps are never
  rasterized, however small** (an image loader hands a 40 dp avatar over as a <= 64 px bitmap), so photos, thumbnails,
  `BitmapPainter`, third-party painters (Coil/Glide) and every masked node keep the neutral placeholder, and
  `contentDescription` is still never captured. Apps whose icons are PNGs therefore still see placeholders.
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
