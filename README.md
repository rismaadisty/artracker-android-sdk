# artracker-android-sdk
Native Android SDK for session replay — capture UI layout states, touch events, and user flows

Version `0.1.0`. The SDK sends events, logs, crashes, ANRs, HTTP calls and (opt-in) privacy-masked session replay to an
[ARTracker](https://github.com/tiofani03/artracker) backend (`POST {endpoint}/api/v1/ingest`).

> **Status: what is verified and what is NOT** (read this before adopting `0.1.0`)
>
> | Area | State |
> | --- | --- |
> | Unit tests (JVM + Robolectric) | **Verified**: 654 SDK tests (60 classes) + 254 sample-app tests, 0 failures (see "Tests" below) |
> | Sample app builds (`:app:assembleDebug`, R8-minified `:app:assembleRelease`) | **Verified** |
> | Run on a device or emulator | **NOT run.** No emulator was available. Nothing here has executed on real Android |
> | End to end against the backend | **NOT run.** Checklist: [`docs/e2e-manual-test.md`](docs/e2e-manual-test.md) |
> | Performance (`init` <= 2 ms, replay walk p95 <= 8 ms, background CPU < 1 %) | **NOT measured.** Procedure: [`artracker-sdk/PERF.md`](artracker-sdk/PERF.md). Only the AAR size is measured (398621 bytes, release, not minified) |
> | Playback of replay chunks in `rrweb-player` | **NOT verified.** Structure only (`validate-chunks.mjs`); see [`tools/replay-viewer`](tools/replay-viewer) |
> | Instrumented tests (offline crash then relaunch, Compose walker on device) | **NOT written/run** |
| Demo app playground instrumented suite (`app/src/androidTest`) | Written and **compiles** (`:app:assembleDebugAndroidTest`); **NOT run**: no device or emulator was available |
> | R8 with the consumer rules | Sample app minified build and a stand-alone R8 run without the optional libraries: **verified to succeed**. A minified app **executed** on a device, or a real AGP app without those libraries: **NOT run** (see "ProGuard / R8") |
> | Release to a public repository | **NOT done.** Only `publishToMavenLocal` was exercised |

## Requirements

- `minSdk 24` (the SDK itself is built with `compileSdk 36`, Java 11 target).
- A project in the ARTracker dashboard and a **public** key (`art_live_...`).
- Optional, only if you use the matching feature: OkHttp (interceptor), Jetpack Compose (Compose masking and replay of
  Compose content), AndroidX Fragment and Navigation (automatic Fragment and `NavController` screen tracking). They are
  `compileOnly` in the SDK: the SDK never forces a version on your app and degrades gracefully when they are absent.

## Installation

Coordinates: `id.art.tracker:artracker-android:0.1.0`.

```kotlin
// build.gradle.kts of the app module
dependencies {
    implementation("id.art.tracker:artracker-android:0.1.0")
}
```

`0.1.0` is not published to a public repository yet. To use it, publish it to your local Maven repository from this
repo and add `mavenLocal()`:

```sh
./gradlew :artracker-sdk:publishToMavenLocal      # writes to ~/.m2/repository/id/art/tracker/artracker-android/0.1.0/
```

```kotlin
// settings.gradle.kts of the consuming project
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

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
| `replay` | `ReplayConfig(enabled = false, sampleRate = 0.1, framesPerSecond = 1.0)` | `framesPerSecond` is clamped to 0.5..4.0; NaN or infinity is rejected |
| `privacy` | `PrivacyConfig(maskAllInputs = true, maskAllText = false, sensitiveViewClasses = emptySet())` | See "Masking" |
| `network` | `NetworkConfig(captureHeaders = false, ignoreHosts = emptySet(), sampleRate = 1.0, captureOnlyErrors = false)` | Used by the OkHttp interceptor. `captureHeaders` is reserved: headers are never captured |
| `flushIntervalMs` | `10_000` | Foreground flush interval, minimum 1000 |
| `sessionTimeoutMs` | `1_800_000` | Idle timeout (30 min), minimum 1000; a session never lasts more than 24 h |
| `beforeSend` | `null` | `BeforeSend { item -> item }`: modify an item, or return `null` to drop it. Runs before the item is queued |

## API

Every function is `@JvmStatic`, safe on any thread, never throws into your app, and is a no-op before `init`, after an
invalid config, while the user is opted out, or while the SDK is disabled by the server (see "Offline, retries").

| Call | What it does |
| --- | --- |
| `ARTracker.identify(userId, traits = emptyMap())` | Associates later items with `userId`; records a `$identify` event with `traits` |
| `ARTracker.track(name, properties = emptyMap())` | Custom event |
| `ARTracker.screen(name)` | `screen_view` for screens that are not tracked automatically |
| `ARTracker.trackNavController(navController)` | `screen_view` (type `compose`) on every destination change of a Jetpack Navigation `NavController`. Ignored without Navigation on the classpath |
| `ARTracker.log(level, message, attributes = emptyMap())` | Log item at `LogLevel.DEBUG/INFO/WARN/ERROR/FATAL` |
| `ARTracker.captureException(throwable, attributes = emptyMap())` | `error` log named `exception` with stack trace, fingerprint, breadcrumbs, current screen. At most 10 identical errors per session |
| `ARTracker.flush()` | Asks the SDK to send what is queued; returns immediately |
| `ARTracker.reset()` | Logout: forgets the user and starts a new session |
| `ARTracker.optOut(enabled)` | `true`: records and sends nothing, and **deletes everything already queued**. Persisted across restarts |
| `ARTracker.startReplay()` / `stopReplay()` | Force replay on (still never while opted out) or stop it; `stopReplay` stays off across new sessions until `startReplay` |
| `ARTracker.diagnostics()` | `Diagnostics(initialized, sessionId, pendingItems, lastSendResult, disabled)`. Reads the database: **never call it on the main thread** |
| `ARTracker.isInitialized()` | True after a successful `init` |

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

## Masking

Privacy is enforced inside the replay walker: text is replaced by `*` (same length, at most 32) **before** it is
stored, so for masked nodes the original string never enters a replay snapshot (see the limitations below for what
is not masked). Attributes of events, logs and breadcrumbs go through a
second layer, the redactor (see "Privacy model").

Defaults:

- `maskAllInputs = true`: every `EditText` and every Compose node with editable text is masked. `maskAllText = false`.
- **Password fields are always masked and cannot be unmasked**: `TYPE_*_PASSWORD` input types,
  `PasswordTransformationMethod`, Compose `SemanticsProperties.Password`.
- Numeric and phone input types, and sensitive autofill hints (password, card number/CVV/expiry, phone, email address,
  postal address, SMS OTP) are masked, even with `maskAllInputs = false`.
- Always blocked (drawn as a labelled block, content never captured): `WebView`, `SurfaceView`, `TextureView`,
  `VideoView`, map views. `ImageView` is a neutral placeholder; bitmaps are never recorded.
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

Opt-in: `ReplayConfig(enabled = false)` by default, with `sampleRate = 0.1` (deterministic per session id) and
`framesPerSecond = 1.0`. `ARTracker.startReplay()` / `stopReplay()` override both at runtime.

- **Format**: a wireframe of the screen (position, size, colours, masked text, touch points) encoded as
  [rrweb](https://www.rrweb.io) events (`Meta`, `FullSnapshot`, `IncrementalSnapshot` mutations, touch interactions), not
  screenshots. The playback is not pixel-perfect: images are placeholders and custom drawing is not recorded.
- **Captured**: the View hierarchy of every window (dialogs and bottom sheets included, see limitations), Compose
  semantics (bounds, text, editable text, role, password), touch coordinates (the SDK wraps `Window.Callback` and only
  reads coordinates; it never consumes events). Keyboard keystrokes are not recorded.
- **Not captured**: bitmaps, WebView content, SurfaceView/TextureView/video/map content, original text of masked views.
- **Cost control**: capture runs only when the screen was drawn since the last tick, with an 8 ms main-thread budget
  watchdog that halves the frame rate, at most 2000 nodes and depth 50 per frame, and stops in the background. Chunks
  (`session` item `replay_chunk`, at most 24 KB) are flushed every 5 s or at 20 KB.
- **Privacy model**: layer 1 is the walker (UI masking above, applied before anything is stored); layer 2 is the
  attribute redactor for everything else. Replay chunks skip layer 2 because they are already masked; only their
  screen `href` (`android://<screen>`) is sanitised (URL scrubbing, id masking).

## Crash, ANR and offline behaviour

- **Crash**: an uncaught-exception handler is installed at `init` and always calls the previous handler (Crashlytics
  and similar). It writes the crash to a file synchronously (atomic rename, no SQLite, no network); the next launch
  turns it into a `log` item (`fatal`, name `crash`) with the highest priority, and it is never evicted.
- **ANR / native crash / low-memory kill**: API 30+, read from `ApplicationExitInfo` and sent as `fatal` `anr` /
  `native_crash`, and `warn` `low_memory_kill`.
- **Offline**: items are persisted in SQLite (`artracker.db`) up to 5 MB or 5000 rows (replay chunks are evicted first,
  crash items never) and dropped after 7 days. Batches are at most 50 items or 128 KB, gzip-compressed. Foreground flush
  every 10 s; when the app goes to background one WorkManager job (`CONNECTED` constraint) drains the queue.
- **Retries**: exponential backoff with full jitter (2 s to 5 min, honours `Retry-After`); `400` drops the batch, `413`
  splits it, and `401` (bad key) disables the SDK for one hour.

## Event taxonomy

| Activity | `type` | `name` / `level` | Notes |
| --- | --- | --- | --- |
| `track(name, props)` | `event` | name | props become attributes |
| `identify()` | `event` | `$identify` | traits become attributes |
| Activity/Fragment/Compose screen | `event` | `screen_view` | `{screen, previous, type}` |
| Foreground/background | `event` | `app_foreground` / `app_background` | |
| Session | `session` | `session_start` / `session_end` | device metadata |
| Replay | `session` | `replay_chunk` | at most 24 KB |
| JVM crash | `log` | `fatal`, name `crash` | sent on the next launch |
| ANR / native crash (API 30+) | `log` | `fatal`, name `anr` / `native_crash` | from `ApplicationExitInfo` |
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
  `ComposeWalker`, `ComposeMaskRules` and its file facade `ComposeMaskRulesKt`, ...) as separate classes, so R8 cannot
  inline or merge them into classes that load in an app without those libraries.

The public API (`ARTracker`, `ARTrackerConfig`, ...) is deliberately **not** kept: you call it directly, so R8 knows it
is used.

Obfuscated builds: stack traces are sent as they are (no symbolication in `0.1.0`). Screen names default to the class
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

**Privacy**

- The API key `art_live_...` is a **public client key**. It ends up in your APK and can be extracted. It only allows
  writing events to your project; never embed an `art_sec_...` key (the SDK rejects it).
- `artrackerUnmask()` (View or Compose) on a node **inside** a masked container resets masking for that node's whole
  subtree and re-exposes it. Do not unmask inside a masked area unless you mean it.
- With `maskAllInputs = false`, Compose fields that hold numbers or PINs stay **clear** in replay: Compose has no input
  type, so the numeric/phone/autofill rules of the View engine do not exist there. Use
  `PasswordVisualTransformation` or `Modifier.artrackerMask()` for them. (Views keep masking numeric/phone inputs.)
- **Crash items bypass `beforeSend` and key-based redaction.** They are written synchronously in the crash handler
  and recovered on the next launch; only value patterns (email, card, JWT, Bearer) are redacted in them, and the
  breadcrumbs attached to them are not key-scrubbed. Do not put secrets in exception messages.
- Popup and dropdown windows (spinners, `PopupWindow`, menus) are separate window roots and do **not** inherit the
  masking of the view they are anchored to.
- WebView content is never captured (the block is drawn, the DOM is invisible to the View hierarchy).
- What is redacted, exactly. Items sent through the pipeline (`track`, `log`, `captureException`, `screen`, HTTP
  records, `ApplicationExitInfo` items) pass `beforeSend`, then **by key** (attribute keys such as `password`, `token`,
  `cookie`: value replaced) and **by value pattern** (email, Luhn card, JWT, `Bearer`: in attribute values and also in the
  item `name` and `message`, so a `log` message, a `track` name or an ANR description is covered). Crash items are the
  exception: they skip `beforeSend` and key-based redaction, and only their message, stack trace header lines,
  screen and breadcrumbs get the value patterns. Value patterns are pattern matching only; do not put personal data in
  names, messages or exception messages.
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
- A snapshot larger than the item limit is trimmed (`oversized`); later changes can then be out of sync until the next
  full snapshot (screen change, rotation, or the 2-minute checkout).
- A dialog or popup that appears without any redraw or touch is attached to the recording on the next capture.
- Recording does not resume by itself after a `401` kill-switch expires while the app stays in foreground.
- Compose capture was tested on Compose UI 1.7.x only (it reads `ViewRootForTest.semanticsOwner`, an internal API,
  guarded by a fallback). Smoke-test your Compose version before enabling replay; when the read fails the Compose
  content is drawn as a single placeholder block and the app is not affected.
- Playback in the dashboard is not verified (see the status box).

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

**Tabs**: *Skenario* (six scripted flows with real taps), *Lab* (event and log, privacy and masking, replay, network, queue,
crash, session, identity and opt-out, and the legacy XML screen), *Inspector* (what the SDK **sent**, read from the sink after
redaction; other profiles show value-free metadata only), *Pengaturan*.

**Scenarios** (`ecommerce_checkout`, `onboarding_form`, `frustrated_taps`, `logout_switch_account`, `offline_user`,
`long_session`) drive the UI with synthetic touches dispatched through `decorView.dispatchTouchEvent`, so the SDK's touch
recorder sees them like real ones. Limits: touches are recorded only while replay runs, only in the Activity window (not
dialogs or popups), and only after a first snapshot of the session exists. The SDK has no rage-click detection.

**Tests**: `./gradlew :app:testDebugUnitTest` (JVM, MockWebServer for HTTP); `./gradlew :app:connectedDebugAndroidTest`
(needs an emulator or device). Still manual: the 401 kill-switch, offline crash then relaunch, and replay playback in the
dashboard (see [`docs/e2e-manual-test.md`](docs/e2e-manual-test.md)).

## Tests

```sh
./gradlew :artracker-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Last full run (2026-11-07): `:artracker-sdk:testDebugUnitTest` 654 tests in 60 classes, 0 failures, 0 skipped
(includes the 1000-iteration public-API fuzz test); `:app:testDebugUnitTest` 254 tests, 0 failures; `:app:assembleDebug`
and `:app:assembleRelease` succeed. The instrumented suite (`:app:connectedDebugAndroidTest`) has not been run.

The sample-app tests do not depend on `local.properties`: they use a test `Application` that never initialises the SDK,
one Robolectric SDK level, and explicit arguments, so `:app:testDebugUnitTest` passes with or without `artracker.*`
entries.

Not covered by automated tests: anything that needs a device (see the status box), and E2E against a real backend.

## Docs

- [`artracker-sdk/ARCHITECTURE.md`](artracker-sdk/ARCHITECTURE.md): design, backend contract, decisions, and the list of
  deviations of the implementation from it.
- [`artracker-sdk/PERF.md`](artracker-sdk/PERF.md): performance measurement procedure (results NOT measured yet).
- [`docs/e2e-manual-test.md`](docs/e2e-manual-test.md): manual end-to-end checklist for the sample app.
- [`tools/replay-viewer`](tools/replay-viewer): page and validator for exported replay chunks.
- [`artracker-sdk/CHANGELOG.md`](artracker-sdk/CHANGELOG.md)
