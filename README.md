# artracker-android-sdk
Native Android SDK for session replay — capture UI layout states, touch events, and user flows

Version `1.0.3`. The SDK sends events, logs, crashes, ANRs, HTTP calls and (opt-in) privacy-masked session replay to an
[ARTracker](https://github.com/tiofani03/artracker) backend (`POST {endpoint}/api/v1/ingest`).

> **Status: what is verified and what is NOT** (read this before adopting `1.0.3`)
>
> | Area | State |
> | --- | --- |
> | Unit tests (JVM + Robolectric) | **Verified**: 1165 SDK tests + 263 sample-app tests, 0 failures (see "Tests" below) |
> | Sample app builds (`:app:assembleDebug`, R8-minified `:app:assembleRelease`) | **Verified** |
> | Run on a device or emulator | **Partly.** Screenshot replay was run on **one physical device** (OPPO CPH2387, Android 14 / API 34) through instrumented tests (see "Tests"); Chucker delivery was checked on Android 14 too (see "Custom transport"). No emulator, no other device or API level (24-25 and the `View.draw` path included), no minified app |
> | End to end against the backend | **NOT run.** Checklist: [`docs/e2e-manual-test.md`](docs/e2e-manual-test.md) |
> | Performance | **Screenshot replay measured once** (5 min of scrolling, `STANDARD`, 2 fps, debug build): 16.91 MB uploaded against the 25 MB limit, and no jank increase measured; the jank metric is saturated on that debug build, see [`artracker-sdk/PERF.md`](artracker-sdk/PERF.md). `init` <= 2 ms and background CPU < 1 % are **NOT measured**. AAR size: 398621 bytes (release, not minified) |
> | Playback of replay chunks in the dashboard | **Not played.** Mask, scale and visual match were checked against `UiAutomation` screenshots on the device |
> | Instrumented tests | Screenshot replay: **run on the device above** (`ScreenshotFlowTest`, `ScreenshotScreensTest`, `ScreenshotBudgetTest`) *before* wireframe mode and the remote replay config were removed; after that change `app/src/androidTest` compiles but was **NOT re-run** (the touch/replay-chunk helpers now read `artracker-screen-v1` chunks). Offline crash then relaunch on a device is **NOT written/run** |
> | R8 with the consumer rules | Sample app minified build and a stand-alone R8 run without the optional libraries: **verified to succeed**. A minified app **executed** on a device, or a real AGP app without those libraries: **NOT run** (see "ProGuard / R8") |
> | Release to GitHub Packages | **Prepared, not published yet.** The artifact (AAR, POM, Gradle metadata) was validated in an isolated local Maven repository and consumed by throw-away apps (AGP 9, and AGP 8 + Kotlin 2.0.21); nothing was sent to GitHub Packages. It is published by [`.github/workflows/publish.yml`](.github/workflows/publish.yml) when a `v*` tag is pushed, see [`docs/releasing.md`](docs/releasing.md) |

## Requirements

- `minSdk 24` (the SDK itself is built with `compileSdk 35`, Java 11 target).
- A project in the ARTracker dashboard and a **public** key (`art_live_...`).
- Optional, only if you use the matching feature: OkHttp (interceptor), Jetpack Compose (Compose masking and replay of
  Compose content), AndroidX Fragment and Navigation (automatic Fragment and `NavController` screen tracking). They are
  `compileOnly` in the SDK: the SDK never forces a version on your app and degrades gracefully when they are absent.

## Installation

Coordinates: `id.art.tracker:artracker-android:1.0.3`, published to **GitHub Packages**
(`https://maven.pkg.github.com/rismaadisty/artracker-android-sdk`).

```kotlin
// build.gradle.kts of the app module
dependencies {
    implementation("id.art.tracker:artracker-android:1.0.3")
}
```

GitHub Packages needs authentication to download, **even though this repository is public**: add the registry to
`settings.gradle.kts` with a GitHub username and a token that has the `read:packages` scope (kept in
`~/.gradle/gradle.properties` or a CI secret, never committed):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
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

Step by step (creating the token, Groovy, version catalog, CI, troubleshooting): [`docs/github-packages.md`](docs/github-packages.md).
To try an unreleased build instead, publish it to your local Maven repository and add `mavenLocal()`:
`./gradlew :artracker-sdk:publishToMavenLocal` (writes to `~/.m2/repository/id/art/tracker/artracker-android/1.0.3/`).

The SDK pulls in `kotlinx-coroutines-android`, `androidx.work:work-runtime-ktx`, `androidx.lifecycle:lifecycle-process`,
`androidx.core:core-ktx` and the Kotlin stdlib. It has no Room, no kotlinx.serialization and no OkHttp dependency.
Release notes: [`artracker-sdk/CHANGELOG.md`](artracker-sdk/CHANGELOG.md).

## Quick start

Imports used by the samples below (all in the AAR; Compose, OkHttp and Navigation types come from your own app):

```kotlin
import id.art.tracker.ARTracker
import id.art.tracker.ARTrackerConfig
import id.art.tracker.LogLevel
import id.art.tracker.NetworkConfig
import id.art.tracker.PrivacyConfig
import id.art.tracker.ReplayConfig
import id.art.tracker.lifecycle.ARTrackerScreenName
import id.art.tracker.privacy.artrackerMask      // View.artrackerMask() and Modifier.artrackerMask()
import id.art.tracker.privacy.artrackerUnmask    // View.artrackerUnmask() and Modifier.artrackerUnmask()
import id.art.tracker.telemetry.okhttp.ARTrackerOkHttpInterceptor
```

From Java the extensions are static methods on the file facades `id.art.tracker.privacy.ARTrackerMask`
(`ARTrackerMask.artrackerMask(view)`) and `id.art.tracker.privacy.ARTrackerComposeMask`
(`ARTrackerComposeMask.artrackerMask(modifier)`).

Initialise once in `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ARTracker.init(
            this,
            ARTrackerConfig(
                endpoint = "https://ingest.example.com",   // base URL; the SDK appends /api/v1/ingest
                apiKey = "art_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                environment = "production",
                appVersion = BuildConfig.VERSION_NAME,
                replay = ReplayConfig(enabled = true, sampleRate = 0.1),
            ),
        )
    }
}
```

Java callers can use `new ARTrackerConfig.Builder(endpoint, apiKey).replay(...).build()`.

`init` runs a small synchronous part on the calling thread: it validates the config, builds the object graph and
starts the session, which **reads three small SharedPreferences files** (session, identity, transport config; a disk
load on first use, so StrictMode may report a disk read; not measured on a device). Opening the database, purging old
items and the first flush run in the background. A second `init` is ignored. An invalid config leaves the
SDK uninitialised and every other call a no-op; each validation error is logged once at warning level (logcat, tag
`ARTracker`) whatever `debug` is, and the key is never logged.

### `ARTrackerConfig`

| Parameter | Default | Notes |
| --- | --- | --- |
| `endpoint` | required | Must start with `http://` or `https://`. Cleartext needs your own network security config |
| `apiKey` | required | Must start with `art_live_`, 32 to 128 characters, no control characters. `art_sec_` keys are rejected |
| `environment` | `"production"` | Sent in the envelope `context.environment` |
| `appVersion` | `null` | Falls back to the app's `versionName` |
| `debug` | `false` | SDK logs to logcat (tag `ARTracker`) |
| `replay` | `ReplayConfig(enabled = false, sampleRate = 0.1, framesPerSecond = 1.0, quality = ReplayQuality.STANDARD, idleAfterMs = 30_000, dormantAfterMs = 180_000)` | `framesPerSecond` is clamped to 0.5..10.0 (2 recommended); NaN or infinity is rejected. `quality` sets the size and JPEG quality of each frame. `idleAfterMs` must be >= 5000 and `dormantAfterMs` > `idleAfterMs`, otherwise the config is invalid. See "Session replay" |
| `privacy` | `PrivacyConfig(maskAllInputs = true, maskAllText = false, sensitiveViewClasses = emptySet())` | See "Masking" |
| `network` | `NetworkConfig(captureHeaders = false, ignoreHosts = emptySet(), sampleRate = 1.0, captureOnlyErrors = false, sender = null)` | The first four are used by the OkHttp interceptor; `captureHeaders` is reserved: headers are never captured. `sender` replaces the transport, see "Custom transport" |
| `flushIntervalMs` | `10_000` | Foreground flush interval, minimum 1000 |
| `sessionTimeoutMs` | `1_800_000` | Timeout (30 min) counted from the last **user input** (touch, key, return to foreground), minimum 1000. SDK activity (events, API calls, replay uploads, reading the session id) never extends a session, and a session only rotates on the next user input; it never lasts more than 24 h. The unit is milliseconds: `sessionTimeoutMs = 1800` is 1.8 s, not 30 min. iOS: the iOS SDK does not expose this yet (its timeout is a fixed 30 min); the planned `ARTrackerConfig.sessionTimeout` will be in **seconds** (default `1800`), clamped to a 1 s minimum instead of rejected. iOS sessions do not survive a process restart and emit no `session_start` / `session_end` items |
| `performance` | `PerformanceConfig(enabled = false, sampleIntervalMs = 1_000, trackFrames = true)` | Session profiling, opt-in. `sampleIntervalMs` must be 500..10_000, otherwise the config is invalid. See "Performance monitoring" |
| `beforeSend` | `null` | `BeforeSend { item -> item }`: modify an item, or return `null` to drop it. Runs before the item is queued |

## API

Every function is `@JvmStatic`, safe on any thread, never throws into your app, and is a no-op before `init`, after an
invalid config, while the user is opted out, or while the SDK is disabled by the server (see "Offline, retries").

| Call | What it does |
| --- | --- |
| `ARTracker.identify(userId, traits = emptyMap())` | Associates later items with `userId`; records a `$identify` event with `traits`. Traits are bounded and global properties are not merged into them, see "Identifying users" |
| `ARTracker.track(name, properties = emptyMap())` | Custom event |
| `ARTracker.screen(name)` | `screen_view` for screens that are not tracked automatically |
| `ARTracker.trackNavController(navController)` | `screen_view` (type `compose`) on every destination change of a Jetpack Navigation `NavController`. Ignored without Navigation on the classpath |
| `ARTracker.log(level, message, attributes = emptyMap())` | Log item at `LogLevel.DEBUG/INFO/WARN/ERROR/FATAL` |
| `ARTracker.captureException(throwable, attributes = emptyMap())` | `error` log named `exception` with stack trace, fingerprint, breadcrumbs, current screen. At most 10 identical errors per session |
| `ARTracker.flush()` | Asks the SDK to send what is queued; returns immediately |
| `ARTracker.reset()` | Logout: call it when the user signs out. Forgets the user and starts a new anonymous session |
| `ARTracker.setGlobalProperties(properties)` | Replaces the set of attributes added to every later event, log and session item (not `perf.samples`); the item's own attribute wins on a key conflict. Kept in memory only: set them again after every `init`. Not cleared by `reset()` |
| `ARTracker.clearGlobalProperties()` | Removes them (call it on logout if they describe the user) |
| `ARTracker.optOut(enabled)` | `true`: records and sends nothing, and **deletes everything already queued**. Persisted across restarts |
| `ARTracker.captureReplayFrame(reason)` | Tells replay that something important changed on screen (a custom overlay, a state the SDK cannot see): a frame is taken now, also while the user is idle. `reason` is logged at debug level only, never sent. No effect when replay is not recording |
| `ARTracker.startReplay()` / `stopReplay()` | Force replay on (still never while opted out) or stop it; `stopReplay` stays off across new sessions until `startReplay` |
| `ARTracker.diagnostics()` | `Diagnostics(initialized, sessionId, pendingItems, lastSendResult, disabled)`. Reads the database: **never call it on the main thread** |
| `ARTracker.isInitialized()` | True after a successful `init` |

### Identifying users

`identify` ties the device to a person; the traits appear on the dashboard under **People** and in the replay **User**
tab. Call it after login, and again whenever the traits change (calling it on every app start is fine).

| Situation | What happens |
| --- | --- |
| `identify("A")` while anonymous | The current session continues and is now `A`'s. No `session_end` |
| `identify("A", sameTraits)` while `A` with the same traits | Nothing is sent. The first call in every process always sends, because the traits are kept in memory only |
| `identify("A", otherTraits)` while `A` | A new `$identify` is sent |
| `identify("B")` while `A` | The session rotates: `session_end` is stamped with `A`, then `session_start` and `$identify` with `B`. Replay follows the new session |
| `reset()` | Forgets the user and starts a new anonymous session |
| `identify` while opted out | Ignored, and nothing is remembered: the first `identify` after opting back in sends |

Trait limits, applied on the device before the redaction below:

- at most 50 keys (the first 50 in sorted key order), keys of 1 to 64 characters (others are dropped);
- `String` values are truncated to 256 characters;
- only `String`, `Number`, `Boolean` and `null` are kept. A `Map`, `Collection`, array, `Date`, enum or any other
  object is dropped (it is never `toString()`-ed).

`$identify` carries the traits only: the global properties (`setGlobalProperties`) are **not** merged into it, so
they cannot leak into the stored user profile. Traits still go through the same on-device redaction as every other
attribute (e-mail addresses and sensitive keys are masked), and your `beforeSend` still sees and may change the item. The exclusion is by item name, so a custom `track("$identify")` skips the globals too.

Automatic: sessions (`session_start` / `session_end`), `app_foreground` / `app_background`, `screen_view` for every
Activity (`@ARTrackerScreenName("Checkout")` on the class overrides the name) and for every Fragment when AndroidX
Fragment is present, JVM crashes, ANRs and native crashes (API 30+), and up to 30 breadcrumbs attached to crashes and
`captureException`.

## OkHttp interceptor

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(ARTrackerOkHttpInterceptor())   // application interceptor
    .build()
```

Records every call as a `log` item named `http_request` (level `info` below 400, `warn` for 4xx, `error` for 5xx or an
exception) with `method`, redacted `url`, `host`, `status`, `duration_ms`, `req_bytes`, `res_bytes` (from
`Content-Length`; absent for chunked or compressed responses), `protocol` and `error`. It never reads a body, never
captures headers, returns the response untouched and rethrows exceptions unchanged. Credentials and sensitive query
parameters are stripped from the URL, the fragment is removed, and calls to the ARTracker endpoint host are skipped.
Tune it with `NetworkConfig(ignoreHosts = ..., sampleRate = ..., captureOnlyErrors = ...)`. OkHttp is not a dependency
of the SDK: your app must provide it.

## Custom transport (Chucker, pinning, proxies)

By default the SDK sends its requests with `HttpURLConnection`: no extra dependency and nothing to configure. To send
them through your own HTTP stack, pass an `ARTrackerSender` in `NetworkConfig`; it carries the ingest batches and the
session replay upload (`chunk-url`, the `PUT` to the signed URL, `chunk-ack`). The API is **experimental**
(`@OptIn(ExperimentalARTrackerApi::class)`). `ARTrackerOkHttpSender` wraps an `OkHttpClient`, so whatever interceptors
that client has (Chucker, a certificate pinner, a proxy, custom timeouts) apply to the SDK's own requests too:

```kotlin
@OptIn(ExperimentalARTrackerApi::class)
fun initTracker(app: Application) {
    val client = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(ChuckerInterceptor.Builder(app).redactHeaders("X-Artracker-Key").build())
        .build()
    ARTracker.init(
        app,
        ARTrackerConfig(
            endpoint = "https://ingest.example.com",
            apiKey = "art_live_...",
            network = NetworkConfig(sender = ARTrackerOkHttpSender(client)),
        ),
    )
}
```

- Use Chucker in debug builds only: `debugImplementation("com.github.chuckerteam.chucker:library:4.2.0")` and
  `releaseImplementation("...:library-no-op:4.2.0")`, and pass `sender = null` in release (the demo app does this, see
  `NetworkInspector`).
- **Chucker version**: Chucker 4.3.0 and later depend on OkHttp 5 and ship Java 21 bytecode. Against the OkHttp 4.12 this
  SDK is built with (and a JDK 17 unit-test JVM) use Chucker **4.2.0**. Mixing OkHttp 4 and 5 also breaks
  `mockwebserver` 4.x in unit tests.
- Do not add `ARTrackerOkHttpInterceptor` to that client to see the SDK's traffic: the SDK's own requests are tagged and
  skipped by the interceptor on purpose, otherwise each delivery would be reported as an `http_request` that is delivered
  again, forever. Chucker (or any other interceptor) sees them normally.
- A custom `ARTrackerSender` must honour `SendRequest.method` (`POST`, and `PUT` for the replay upload), the headers and the
  body. It returns the HTTP status, the raw `Retry-After` header and the response body (`SendResponse.body`, up to 16 KiB:
  the replay `chunk-url` step reads the signed upload URL from it) and throws `IOException` for a network failure; the
  SDK maps the status exactly as for the built-in transport. It must not follow redirects or retry on its own, and should
  use timeouts: it runs on a background thread and a hung call stalls delivery. A sender that always POSTs, or drops the
  response body, breaks the replay upload (batches are retried, then dropped); the API key is never on the `PUT`.
- The sender is used while `ARTracker.init` ran in the current process. A background flush started by WorkManager in a
  process where `init` did not run falls back to the built-in transport, because a sender cannot be persisted, and so does
  not show up in Chucker.
- Verified on a physical device (Android 14, Chucker 4.2.0): Chucker records every delivery (`POST /api/v1/ingest`), decodes
  the `gzip` request body and shows the JSON batch, and `redactHeaders("X-Artracker-Key")` masks the key. On Android 13
  and later the notification only appears after the notification permission is granted; the Chucker screen itself can
  always be opened with `Chucker.getLaunchIntent(context)` (the demo app has a button for it).

## Masking

Privacy is enforced on the device, before a replay frame is compressed: masked regions are painted over on the
full-size bitmap, so the pixels of a masked view never reach a scaled copy or the JPEG (see the limitations below for
what is not masked). Attributes of events, logs and breadcrumbs go through a
second layer, the redactor (see "Privacy model").

Defaults:

- `maskAllInputs = true`: every `EditText` and every Compose node with editable text is masked. `maskAllText = false`.
- **Password fields are always masked and cannot be unmasked**: `TYPE_*_PASSWORD` input types,
  `PasswordTransformationMethod`, Compose `SemanticsProperties.Password`.
- Numeric and phone input types, and sensitive autofill hints (password, card number/CVV/expiry, phone, email address,
  postal address, SMS OTP) are masked, even with `maskAllInputs = false`.
- Always blocked (drawn as a labelled block, content never captured): `WebView`, `SurfaceView`, `TextureView`,
  `VideoView`, map views. `ImageView` is a neutral placeholder, except small vector icons, which are recorded; bitmaps (even tiny ones), photos and masked views never are.
- Extra classes: `PrivacyConfig(sensitiveViewClasses = setOf(CardInputView::class.java))` (matched with `isInstance`).

Views:

```kotlin
view.artrackerMask()        // this view and its whole subtree
view.artrackerUnmask()      // opt out of the default masking (never a password)
```

```xml
<LinearLayout android:tag="artracker-mask" ... />
```

Compose:

```kotlin
Text("Balance: $total", Modifier.artrackerMask())
OutlinedTextField(value, onValueChange, Modifier.artrackerUnmask())   // an ordinary field you decided is safe
BasicTextField(value, onValueChange, visualTransformation = PasswordVisualTransformation())  // always masked
```

Priority: password > explicit unmask > explicit mask or masked ancestor / custom class > default rules.

## Session replay

Opt-in: `ReplayConfig(enabled = false)` by default, with `sampleRate = 0.1` (deterministic per session id),
`framesPerSecond = 1.0` and `quality = ReplayQuality.STANDARD`. `ARTracker.startReplay()` / `stopReplay()` override the
first two at runtime. A replay is a sequence of **real screenshots of the app, masked on the device**; there is no other
mode and the SDK reads no replay setting from the server.

```kotlin
import id.art.tracker.ReplayConfig
import id.art.tracker.ReplayQuality

replay = ReplayConfig(
    enabled = true,
    sampleRate = 0.1,
    framesPerSecond = 2.0,                 // 0.5..10 (2 recommended)
    quality = ReplayQuality.STANDARD,      // LOW 480 px / q50, STANDARD 720 px / q60, HIGH 1080 px / q75
)
```

- **Format**: `artracker-screen-v1` chunks: gzipped JSON of base64 JPEG frames (with the sanitised screen name and
  viewport) and touches (`down`, `move`, `up`, in dp), closed after 10 s, on a session change, when the next frame would
  not fit (2 MB), or when recording stops.
- **Captured**: every window of the app (dialogs and bottom sheets included, see limitations) composited into one
  frame, and touch coordinates (the SDK wraps `Window.Callback` and only reads coordinates; it never consumes events).
  Keyboard keystrokes are not recorded.
- **Rage taps**: three or more taps within 1 s, each within 24 dp of the first, mark the chunk with `has_rage_click`
  in its acknowledgement (the chunk they land in; a burst can span two chunks of the same session). Multi-finger
  gestures are not counted, and the SDK sends no separate event. `has_dead_click` is always `false`.
- **Capture lifecycle (Active, Idle, Dormant)**: driven by real user input only. **Active**: a frame whenever the screen
  was drawn, at most `framesPerSecond`, and a forced keyframe every 120 s. After `idleAfterMs` (30 s) without input it is
  **Idle**: one check every 5 s (0.2 fps), no keyframe. After `dormantAfterMs` (3 min) it is **Dormant**: no timed
  capture and no flush timer, the open chunk is closed, and the draw listeners only flip a flag. Important changes are
  captured in every state, after a debounce so the UI has rendered: screen change, new window or dialog (focus change),
  an error log, keyboard shown or hidden (300 ms), an API response (500 ms; at most 1 per 5 s while Idle and 1 per 10 s
  while Dormant; requests to the SDK's own endpoint and to `ignoreHosts` do not count) and `ARTracker.captureReplayFrame`
  (at once). The first touch after Idle or Dormant takes a frame **immediately**, so what the user came back to is
  never lost, then capture returns to Active. A trigger in Dormant sends its chunk 10 s later (an error frame survives
  a process kill). Once the session has timed out (no input for `sessionTimeoutMs`) triggers capture nothing until the
  next touch, which first rotates the session and then takes its frame in the new session. Dormant never ends the session.
  Measured once on an emulator (30 min, infinite animation): 37 renders, none after dormancy, new session after the
  touch; see [`artracker-sdk/PERF.md`](artracker-sdk/PERF.md) section 6 for what was not measured (chunks, a device).
- **Cost control**: capture runs only when the screen was drawn since the last tick, with a 16 ms main-thread budget
  watchdog that halves the frame rate, a rolling data budget of about 5 MB per minute (the frame rate is halved and
  then the quality steps down), identical frames are not sent, and nothing runs in the background. Chunks are queued
  in a separate `artracker_replay.db` and uploaded through the backend's Session Replay API (`chunk-url`, `PUT`,
  `chunk-ack`), at most 5 MiB per upload. Replay is not sent to `/api/v1/ingest`, so a backend without the replay
  endpoints shows no replays until it is upgraded. Replay uploads go through your `ARTrackerSender` when you set one (so
  Chucker, certificate pinning and proxies see them), otherwise through the built-in `HttpURLConnection`.
- **If the project has screenshot replay switched off** the backend answers `403 screenshot_disabled` to `chunk-url`.
  The SDK then drops the queued chunks and stops recording for that process (the SDK is not disabled and no kill switch
  trips); the next process start tries again.
- **Privacy model**: layer 1 is the on-device mask (UI masking above, applied before the frame exists in compressed
  form); layer 2 is the attribute redactor for everything else. Replay chunks skip layer 2 because they are already
  masked; only the screen name of a frame (`android://<screen>`) is sanitised (URL scrubbing, id masking).
- **Masking is applied on the device, before compression.** Passwords, sensitive inputs, `maskAllInputs`, `maskAllText`,
  `artrackerMask()` / tags, `sensitiveViewClasses`, Compose semantics and blocked views (WebView, SurfaceView,
  TextureView, VideoView, maps) are painted over with an opaque pattern on the full-size bitmap, then scaled and encoded.
  If the masks cannot be collected for a frame, the frame is dropped. Everything else on screen **is** sent, photos
  included: review your screens before enabling it, and use `artrackerMask()` for anything else sensitive.
- **`FLAG_SECURE` windows are never captured**: their area is masked and the SDK never falls back to `View.draw` for them.
- **Keyboard**: it is drawn by another process, so it cannot be captured; its area is a grey "keyboard" placeholder.
- **Capture**: `PixelCopy` of the Activity window on API 26+; on API 24-25 (or if a copy fails) `View.draw`, which has no
  `SurfaceView` content. Dialogs and popups are drawn on top with their dim. `SurfaceView` / video / camera / map
  areas are masked, not captured. A frame is taken only when the screen was drawn, identical frames are skipped, and a
  16 ms main-thread watchdog halves the rate.
- **Data budget (economical, every network)**: about 5 MB per minute. Over budget in a rolling 60 s window the rate is
  halved (down to 0.5 fps) and, if that persists, the quality drops one preset for the session. Chunks (gzipped JSON of
  frames and touches, `artracker-screen-v1`) close at 10 s or 2 MB and upload on Wi-Fi and cellular; offline they are
  kept up to 50 MB (files under `noBackupFilesDir`).

## Performance monitoring

Opt-in: `PerformanceConfig(enabled = false)` by default. When it is off nothing is created: no thread, no sampling loop,
no `ComponentCallbacks2`.

```kotlin
ARTrackerConfig(
    endpoint = "https://ingest.example.com",
    apiKey = "art_live_...",
    performance = PerformanceConfig(enabled = true, sampleIntervalMs = 1_000, trackFrames = true),
)
```

While the app is in the foreground, one sample is taken every `sampleIntervalMs` (500 to 10 000 ms) and sent as a
`perf.samples` item (`type "perf"`). Nothing is sampled in the background.

| Sample key | Meaning | Left out when |
| --- | --- | --- |
| `t` | epoch ms, from the SDK clock | never |
| `cpu` | process CPU, % of the whole device (all cores), from `/proc/self/stat` | first sample after (re)start; `/proc` unreadable |
| `mem_mb` | resident set size from `/proc/self/statm` | `/proc` unreadable |
| `fps` | frames per second of the resumed Activity (`FrameMetrics`) | `trackFrames = false`, or fewer than 2 frames in the interval (an idle screen is not 0 fps) |
| `jank`, `frozen` | frames over 2 vsync periods, frames over 700 ms | `trackFrames = false` |
| `threads` | thread count | `/proc` unreadable |
| `battery` | 0.0 to 1.0 | unavailable |
| `thermal` | `nominal`, `fair`, `serious` or `critical` (API 29+) | below API 29 |
| `ram_free_mb` | free RAM in MB (`ActivityManager.MemoryInfo.availMem`) | unavailable |
| `storage_free_mb` | free space of the data partition in MB | unavailable |
| `net` | default network: `wifi`, `cellular`, `ethernet`, or `none` when there is no active network | unavailable, or a transport that is not one of the three (VPN, Bluetooth, ...) |

A metric that cannot be read is omitted, never sent as 0. Samples are batched: 10 per item (at most 60), and flushed when
the app goes to the background and when the session changes (old samples go out under the old session id). The item's
`attributes` also carry `v`, `platform`, `interval_ms` and `device` (`os_version`, `ram_mb`, `storage_mb`, `cores`,
`refresh_hz`).

The device context (`os_version` of at most 32 characters, `ram_mb`, `storage_mb` of the data partition, and the per-sample
`ram_free_mb`, `storage_free_mb` and `net`) travels with `perf.samples` only, never with `session.start`. All values are MB
rounded to an integer, and a value that cannot be read is omitted (an empty `os_version` too). `net` comes from a cached
`ConnectivityManager` default-network callback that is registered only while performance monitoring runs in the
foreground and is unregistered when it stops; it needs no permission beyond the `ACCESS_NETWORK_STATE` the SDK already declares.

Two events accompany the samples. `perf.memory_warning` has two sources, told apart by its `source` attribute:
`{source: "trim", level}` for trim levels 10 and 15 while in the foreground (Android 14 / API 34 and later no longer
deliver those two levels to apps), and, on Android 14 and later only, `{source: "memory_info", ram_free_mb}`, sent when
a sample finds `ActivityManager.MemoryInfo.lowMemory` turned on (once per low period, not once per sample, and at most once a minute, because the system flag can flip back and forth
while it kills cached processes).
`perf.thermal_change {from, to}` reports thermal state changes. Opting out discards the buffered samples. To measure what profiling costs, see
`artracker-sdk/PERF.md` section 4.

## Crash, ANR and offline behaviour

- **Crash**: an uncaught-exception handler is installed at `init` and always calls the previous handler (Crashlytics
  and similar). It writes the crash to a file synchronously (atomic rename, no SQLite, no network); the next launch
  turns it into a `log` item (`fatal`, name `crash`) with the highest priority, and it is never evicted. It carries
  `build_id` when the manifest sets one and it was read before the crash (the handler never waits for it).
- **ANR / native crash / low-memory kill**: API 30+, read from `ApplicationExitInfo` and sent as `fatal` `anr` /
  `native_crash`, and `warn` `low_memory_kill`. `anr` and `native_crash` carry `build_id`, except for exits that happened
  before the app was last updated (they belong to another build, and the current id would select the wrong mapping).
- **ANR (API 24–29)**: while the app is in the foreground a watchdog thread checks the main thread every second; a
  main thread that has not responded for 5 s is reported once as `error` `anr` with `source: "watchdog"`, the
  main-thread stack (16 KB), the screen and the breadcrumbs. Not while a debugger is attached; stopped in the
  background; API 30+ keeps using `ApplicationExitInfo` only. Always on, no setting.
- **Build id (for deobfuscation)**: put the id of your build in the manifest, inside `<application>`:
  `<meta-data android:name="id.art.tracker.BUILD_ID" android:value="@string/artracker_build_id" />`. The SDK reads it
  once, in the background, and adds `build_id` to `session_start` and to the crash and exit items (see above), so the
  backend can pick the R8 mapping of that build. Fill the string from Gradle, in `app/build.gradle.kts`:
  ```kotlin
  val artrackerBuildId = providers.gradleProperty("artrackerBuildId")
      .orElse(providers.exec { commandLine("git", "rev-parse", "--short=12", "HEAD") }
          .standardOutput.asText.map { "git-" + it.trim() })
  android { defaultConfig { resValue("string", "artracker_build_id", artrackerBuildId.get()) } }
  ```
  (CI can pass `-PartrackerBuildId=<value>`; a build without git or a `.git` directory must pass it, otherwise the
  Gradle configuration fails.) Why `@string` and not a literal `android:value`: Android turns a literal that looks like a
  number into a number (`4512` or `0012` becomes the integer 4512 or 12, `12e45` a float, `0x1F` the integer 31, and a
  long all-digit value such as `202611160930` may not be stored as an integer at all), so the SDK would see something
  other than what you wrote and drops a float. A string resource always arrives as the text you wrote. Use an id that
  contains a letter (a prefix such as `git-`) or a UUID. An id of up to 128 characters is sent as is; one that the
  redaction would rewrite (a long run of digits that looks like a card or phone number, for example
  `build-20261116093000` now and then) is **not sent** and a warning is logged, because a rewritten id would match no
  mapping. Absent or blank means no `build_id` field. The same value must be used when the mapping is uploaded (backend
  side, not available yet).
- **Offline**: items are persisted in SQLite (`artracker.db`) up to 5 MB or 5000 rows (replay chunks are evicted first,
  crash items never) and dropped after 7 days. Batches are at most 50 items or 128 KB, gzip-compressed. Foreground flush
  every 10 s; when the app goes to background one WorkManager job (`CONNECTED` constraint) drains the queue.
- **Retries**: exponential backoff with full jitter (2 s to 5 min, honours `Retry-After`); `400` drops the batch, `413`
  splits it, and `401` (bad key) disables the SDK for one hour.

## Event taxonomy

| Activity | `type` | `name` / `level` | Notes |
| --- | --- | --- | --- |
| `track(name, props)` | `event` | name | props become attributes |
| `identify()` | `event` | `$identify` | the bounded traits become the attributes (no global properties); a repeated identical call is not sent; a different user rotates the session |
| Activity/Fragment/Compose screen | `event` | `screen_view` | `{screen, previous, type}` |
| Foreground/background | `event` | `app_foreground` / `app_background` | |
| Cold start | `event` | `app_start` | `{type:"cold", duration_ms, first_screen}`; once per process, only when the process was started to show an Activity |
| Session | `session` | `session_start` / `session_end` | device metadata (+ `build_id` when the manifest sets one) |
| Replay | *(not an ingest item)* | `artracker-screen-v1` screenshot chunk, via the Session Replay API | at most 5 MiB gzipped per upload (chunks 2 MB) |
| JVM crash | `log` | `fatal`, name `crash` | sent on the next launch (+ `build_id` when known) |
| ANR / native crash (API 30+) | `log` | `fatal`, name `anr` / `native_crash` | from `ApplicationExitInfo` (+ `build_id` for exits of the current build) |
| ANR (API 24–29) | `log` | `error`, name `anr` | `source:"watchdog"`, reported while the app is still running (+ `build_id`) |
| Low-memory kill (API 30+) | `log` | `warn`, name `low_memory_kill` | from `ApplicationExitInfo` |
| `captureException` | `log` | `error`, name `exception` | |
| HTTP | `log` | `info`/`warn`/`error`, name `http_request` | |
| `log()` | `log` | per level | |

## ProGuard / R8

The AAR ships consumer rules (`consumer-rules.pro`); you need no rules of your own. They:

- keep the `FlushWorker` class name (WorkManager stores it) and `@ARTrackerScreenName` (read by reflection);
- add `-dontwarn` for `okhttp3`, `androidx.fragment`, `androidx.navigation` and `androidx.compose`, because those are
  optional and may be missing from your app;
- keep the classes that mention those optional libraries (`FragmentSupport`, `FragmentTracker`, `NavBinder`,
  `ComposeMaskCollector`, `ComposeMaskRules` and its file facade `ComposeMaskRulesKt`, ...) as separate classes, so R8 cannot
  inline or merge them into classes that load in an app without those libraries.

The public API (`ARTracker`, `ARTrackerConfig`, ...) is deliberately **not** kept: you call it directly, so R8 knows it
is used.

Obfuscated builds: stack traces are sent as they are (no symbolication in `1.0.3`). Screen names default to the class
simple name, so a Fragment created in code, or a Compose destination, can show up as an obfuscated name such as `a`;
use `@ARTrackerScreenName("...")`, `ARTracker.screen("...")` or a `-keepnames` rule for such classes. Activities
declared in the manifest are kept by AGP's own rules.

Validation (details and commands in [`docs/r8-consumer-rules-check.md`](docs/r8-consumer-rules-check.md)): an
R8-minified `:app:assembleRelease` of the sample app (has OkHttp, Compose, Fragment, Navigation) succeeds, and a
stand-alone R8 run on the AAR **without** those four libraries succeeds too (and fails without the consumer rules).
What is **not** run: a real AGP build of an app without them, installed and executed on a device, and the minified
sample app itself.

## Known limitations & privacy notes

Read these before shipping to production.

**Behaviour**

- The ANR watchdog (API 24–29) sees a main thread blocked for 5 s or more, not the system's ANR dialog, and an app that
  recovers before 5 s is not reported. Its items are `error`, not `fatal`, because the process does not necessarily
  die. It is not a way to measure jank: it only fires on a hang of 5 s or more.
- `app_start` measures the **cold start** only: from the process start to the first drawn frame of the first Activity.
  Warm and hot starts are not measured. A process that was started in the background (a broadcast, WorkManager, a
  push) and opened by the user later reports nothing (a first Activity created more than 1 s after `init` is not a
  start; the gap is taken from `init`, so a slow `Application.onCreate` does not hide a genuine slow start). Nothing is
  reported either when `init` runs after the first Activity was created, when the first Activity finished or paused
  before drawing and the next one is created more than 2 s later, when the process was not in the foreground at
  `init` (this also covers a launch with the screen off or under the keyguard), and when the duration is above 60 s.
  It is always on and has no setting.
- Global properties apply to items enqueued **after** the call, and a call before `init` is ignored. **Crash, ANR and
  native crash items never carry them**: a JVM crash is written when the process dies and sent by the next process, and
  ANR / native crash exits are reported by a later process, whose properties do not describe them. If you need the
  tenant or experiment on a crash, add it through `beforeSend` from your own state.

**Privacy**

- The API key `art_live_...` is a **public client key**. It ends up in your APK and can be extracted. It only allows
  writing events to your project; never embed an `art_sec_...` key (the SDK rejects it).
- `artrackerUnmask()` (View or Compose) on a node **inside** a masked container resets masking for that node's whole
  subtree and re-exposes it. Do not unmask inside a masked area unless you mean it.
- With `maskAllInputs = false`, Compose fields that hold numbers or PINs stay **clear** in replay unless they carry a
  sensitive autofill `ContentType` (Compose UI 1.8+ at runtime: password, card, phone, e-mail, postal address, one-time
  code). The keyboard type is not part of Compose semantics, so a plain numeric field without a `ContentType` is not
  recognised: set `contentType`, or use `PasswordVisualTransformation` or `Modifier.artrackerMask()`. On Compose 1.7 the
  `ContentType` rules are skipped. (Views keep masking numeric/phone inputs.)
- **Crash items are redacted in two steps.** The crash handler writes them synchronously and can only redact value
  patterns (email, card, JWT, Bearer). On the next launch they are recovered and pass `beforeSend` and the key-based
  redaction like any other item, so `beforeSend` can modify or drop a recovered crash and a breadcrumb under a key such
  as `token` is replaced. A crash that was not saved yet when the process died is lost, and `beforeSend` never sees the
  crash at the moment it happens. Do not put secrets in exception messages.
- A popup inherits masking from the masked area it is attached to: a popup or dropdown window (`PopupWindow`, the
  suggestions of an `AutoCompleteTextView`, a spinner, Compose `Popup` / `DropdownMenu`) whose top or bottom edge lies
  within 8 dp of a masked area of the window below it (or that spans the area from above to below, as a menu shifted
  over its anchor does) is masked whole, and so is a popup over a `FLAG_SECURE` window. The anchor itself cannot be
  read, so this is inferred from position: an ordinary popup that happens to touch a masked field is masked too, which
  on a dense form screen (full-width fields a few dp apart) means most menus are masked; and a popup placed farther
  from its anchor (large custom offsets) is **not** covered: mask its content with `artrackerMask()`. A popup that
  covers at least 85 % of the window height (a bottom sheet) never inherits.
- WebView content is never captured (the block is drawn, the DOM is invisible to the View hierarchy). In screenshot
  mode the WebView area is painted over, and `SurfaceView` / `TextureView` / video / map areas too.
- **Session replay sends real pixels** (photos, colors, layouts) of everything that is not masked. It is off unless
  you set `ReplayConfig(enabled = true)` (or call `ARTracker.startReplay()`); masking runs on the device before
  compression. The keyboard is never captured and `FLAG_SECURE` windows are masked entirely.
- What is redacted, exactly. Items sent through the pipeline (`track`, `log`, `captureException`, `screen`, HTTP
  records, `ApplicationExitInfo` items) pass `beforeSend`, then **by key** (attribute keys such as `password`, `token`,
  `cookie`: value replaced) and **by value pattern** (email, Luhn card, JWT, `Bearer`: in attribute values and also in the
  item `name` and `message`, so a `log` message, a `track` name or an ANR description is covered). Crash items
  join them on the next launch, when they are recovered from disk. Value patterns are pattern matching only; do not put
  personal data in names, messages or exception messages.
- Card numbers that pass the Luhn check are redacted to `**** **** **** NNNN` (the last four digits are kept, same as
  the web SDK); emails, JWTs and `Bearer` tokens are replaced entirely.
- Backups: by default Android cloud backup and device transfer copy the app's data. For ARTracker that is the
  SharedPreferences file `artracker_transport` (a copy of the public API key for the WorkManager worker, and the
  kill-switch state), the database `artracker.db` (queued, not yet sent items) and `filesDir/artracker/` (crash files
  that were not sent yet). Exclude them in your `backup_rules.xml` / `data_extraction_rules.xml` unless you want queued
  telemetry to follow the user to a new device.

**Replay**

- Replay chunk `seq` restarts from 0 on every process start (also for a resumed session): order chunks by
  `(start, seq)`, not `seq` alone.
- A dialog or popup that appears without any redraw or touch is attached to the recording on the next capture.
- Recording does not resume by itself after a `401` kill-switch expires while the app stays in foreground.
- Compose masking was tested on Compose UI 1.7.x only (it reads `ViewRootForTest.semanticsOwner`, an internal API,
  guarded by a fallback). Smoke-test your Compose version before enabling replay; when the read fails the whole Compose
  host is masked and the app is not affected.
- Playback of replay chunks in the dashboard is not verified (see the status box).
- Masks are rectangles at the view's position (scale and rotation are not applied), so a masked view that
  is scaled up during an animation can show an edge for a few frames; hardware-only content inside a dialog or popup
  (drawn with `View.draw`) may be missing; a tap on a screen that does not change can be absent from the screenshot chunk;
  a chunk can close up to 15 s (not 10 s) after it opened. API 24-25 and the `View.draw` fallback were not run on a device.

**Platform**

- **Multi-process apps are not supported**: call `init` in the main process only; the crash/ExitInfo files, the SQLite
  queue and the WorkManager job assume one process.
- On the very first `init` after install, `ApplicationExitInfo` may report exits that happened **before** the SDK was
  installed (only those of the last 7 days, up to the 16 the OS keeps); they are sent as fatal and can skew ANR rate once.
- `diagnostics()` reads the database: never call it on the main thread.
- `NetworkConfig.captureHeaders` is reserved and ignored. `sampleRate = 0` on the interceptor still records `http`
  breadcrumbs. Cancelled calls are reported as errors.
- Native-crash traces on API 31+ are protobuf tombstones and are of no use in the item; the ANR trace is kept (16 KB).
- The first `init` reads three SharedPreferences files on the calling thread (a disk load, visible to StrictMode); the
  cost was not measured on a device (see `PERF.md`), so the `init` <= 2 ms budget is unverified.
- Replay text of views that are **not masked** is not value-redacted: an email address or phone number shown in a plain
  `TextView` or Compose `Text` appears verbatim in the replay unless the view is masked (`maskAllText`, a sensitive
  class or the Compose mask modifier). The web SDK redacts that text; the Android SDK does not.
- The envelope `context.app_version` is the version at SEND time. Crash and ExitInfo items that are sent after an app
  upgrade are therefore attributed to the newer version by the backend; the item attribute `app_version` is the correct
  one.
- Every resumed Fragment is reported as a screen, including NavHost containers, dialogs and headless fragments, so
  `screen_view` can be noisier than the visible screens.
- `TelemetryItem` and `Diagnostics` are public data classes. The API is 0.x: binary compatibility between versions is
  not guaranteed.

## Demo app (playground)

The `:app` module is an interactive playground for the SDK. It never changes `:artracker-sdk`.

**Where events go** (Settings tab, restart needed; the SDK config is immutable):

| Profile | Endpoint | Key |
| --- | --- | --- |
| `SINK` (default) | in-app MockWebServer on `localhost:38457` (debug builds only) | built-in dummy key |
| `OFFLINE` | `http://sink.playground.invalid` (nothing answers; the queue fills) | built-in dummy key |
| `LOCAL` | your dev backend, e.g. `http://10.0.2.2:3000` (must be a local host) | `artracker.key` from `local.properties` |
| `BACKEND` | `artracker.endpoint` from `local.properties` | `artracker.key` |

`BACKEND` to a remote host needs a typed confirmation (`KIRIM`) in Settings and is valid for **one process start**: the next
launch falls back to `SINK`. Load and fake-PII actions are disabled while the endpoint is remote. Before a remote endpoint
starts, the SDK's local queue is wiped, because the SDK flushes leftovers right after `init`.

**Replay**: the sample records **real, masked screenshots** (`STANDARD`, 2 fps). *Pengaturan* has a quality choice. The
`SINK` profile accepts screenshot chunks; the *Replay* lab has a switch to make it answer `403 screenshot_disabled` (the
running session then stops recording), a summary of the screenshot chunks the sink received, and an export of the last
frames as JPEG files.

**Network inspection**: debug builds deliver the SDK's batches through an OkHttp client with Chucker (`NetworkInspector`). Open it from the *Lab* tab, *Jaringan* entry, with *Buka Chucker*, or from Chucker's notification (Android 13 and later need the notification permission). Release builds use Chucker's no-op artifact and the built-in transport.

**Tabs**: *Skenario* (six scripted flows with real taps), *Lab* (event and log, privacy and masking, replay, network, queue,
crash, session, identity and opt-out, and the legacy XML screen), *Inspector* (what the SDK **sent**, read from the sink after
redaction; other profiles show value-free metadata only), *Pengaturan*.

**Scenarios** (`ecommerce_checkout`, `onboarding_form`, `frustrated_taps`, `logout_switch_account`, `offline_user`,
`long_session`) drive the UI with synthetic touches dispatched through `decorView.dispatchTouchEvent`, so the SDK's touch
recorder sees them like real ones. Limits: touches are recorded only while replay runs, only in the Activity window (not
dialogs or popups), and only after a first frame of the session exists. `frustrated_taps` produces a chunk flagged `has_rage_click`.

**Tests**: `./gradlew :app:testDebugUnitTest` (JVM, MockWebServer for HTTP); `./gradlew :app:connectedDebugAndroidTest`
(needs an emulator or device). Still manual: the 401 kill-switch, offline crash then relaunch, and replay playback of screenshot chunks in the
dashboard (see [`docs/e2e-manual-test.md`](docs/e2e-manual-test.md)).

## Tests

```sh
./gradlew :artracker-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Last full run (2026-11-17): `:artracker-sdk:testDebugUnitTest` 1165 tests, 0 failures (includes the 1000-iteration
public-API fuzz test and the screenshot pipeline: masking, encoder, chunker, store migration, controller);
`:app:testDebugUnitTest` 263 tests, 0 failures; `:app:assembleDebug` and `:app:assembleRelease` succeed.

Instrumented tests for screenshot replay, run on a physical device (`adb install -r -t` of `app-debug.apk` and
`app-debug-androidTest.apk`, then `am instrument`; not through `connectedDebugAndroidTest`, which uninstalls the app):

```sh
adb shell am instrument -w -r -e class id.art.tracker.e2e.ScreenshotFlowTest   id.art.tracker.test/id.art.tracker.PlaygroundTestRunner
adb shell am instrument -w -r -e class id.art.tracker.e2e.ScreenshotScreensTest id.art.tracker.test/id.art.tracker.PlaygroundTestRunner
adb shell am instrument -w -r -e class id.art.tracker.e2e.ScreenshotBudgetTest -e durationMs 300000 id.art.tracker.test/id.art.tracker.PlaygroundTestRunner
```

`ScreenshotFlowTest` (frame vs `UiAutomation.takeScreenshot()` outside masked areas, 5/5 runs) and `ScreenshotScreensTest`
(fonts, icons, a picture, a dialog with its dim) passed; `ScreenshotBudgetTest` prints a `budget_report` line (data and
jank), see [`artracker-sdk/PERF.md`](artracker-sdk/PERF.md). The rest of the instrumented suite
(`:app:connectedDebugAndroidTest`) has not been run.

The sample-app tests do not depend on `local.properties`: they use a test `Application` that never initialises the SDK,
one Robolectric SDK level, and explicit arguments, so `:app:testDebugUnitTest` passes with or without `artracker.*`
entries.

Not covered by automated tests: anything that needs a device (see the status box), and E2E against a real backend.

## Docs

- [`artracker-sdk/ARCHITECTURE.md`](artracker-sdk/ARCHITECTURE.md): design, backend contract, decisions, and the list of
  deviations of the implementation from it.
- [`artracker-sdk/PERF.md`](artracker-sdk/PERF.md): performance measurement procedure (results NOT measured yet).
- [`docs/e2e-manual-test.md`](docs/e2e-manual-test.md): manual end-to-end checklist for the sample app.
- [`docs/github-packages.md`](docs/github-packages.md): how an app (Alfagift, Apo, ...) consumes the SDK from GitHub Packages.
- [`docs/releasing.md`](docs/releasing.md): how a release is validated and published (tag `v*`), and the one-time manual setup.
- [`artracker-sdk/CHANGELOG.md`](artracker-sdk/CHANGELOG.md)
