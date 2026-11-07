# Demo App Interactive Playground Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the `:app` demo into an interactive playground in which every public capability of `:artracker-sdk` can be triggered, observed on the device and covered by automated tests, without any event reaching a production backend unless the user explicitly confirms it.

**Architecture:** A new package `id.art.tracker.playground` sits next to the existing `id.art.tracker.sample` code. Four seams make the SDK observable and safe without touching it: (1) a persisted `PlaygroundSettings` profile read by `SampleApplication` before `ARTracker.init` (config is immutable, so changes need a process restart); (2) an `EndpointGuard` + `Bootstrap` that default every launch to an **in-app MockWebServer sink** and only use the real backend after a one-shot, typed confirmation; (3) an Inspector that shows what the SDK actually **sent** (read from the sink, i.e. after redaction) and never a pre-redaction value; (4) an `SdkStatusRepository` that polls `ARTracker.diagnostics()` off the main thread. On top sit a Material3 shell (4 tabs), eight capability labs and a scenario runner that drives realistic flows, including synthetic taps dispatched through the same `Window.Callback` path that the SDK's `TouchRecorder` wraps.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM 2024.09.00), Navigation Compose 2.8.4, OkHttp 4.12.0, MockWebServer 4.12.0 (debug runtime + all local tests), JUnit 4 + Robolectric 4.14.1 (JVM), AndroidX Test (instrumented).

**Spec:** No written spec exists. This plan implements sections 5–9 of the analysis report delivered in the conversation of 2026-11-07, as revised by the review in the same conversation (revision 2, below). Every SDK behaviour the plan relies on was checked against the SDK source; the source references are in "SDK facts this plan depends on".

## Revision 2 (what changed and why)

| # | Change | Reason found in the SDK source |
| --- | --- | --- |
| R1 | Inspector no longer uses `beforeSend` to display values. Its rich view reads the **post-redaction payloads the SDK sends** to an in-app sink. A `beforeSend` tap remains only as a metadata-only fallback (no values, no messages, no user ids; names and keys pass an allow-list). | `EventPipeline.prepare` runs `beforeSend` first and the `AttributeRedactor` after it, and `replay_chunk` items skip redaction entirely. Anything shown from `beforeSend` is raw. |
| R2 | Default endpoint profile is `SINK`. Profiles are `SINK`, `OFFLINE`, `LOCAL`, `BACKEND`. The real key and endpoint from `local.properties` are used only for `LOCAL` (key) and a confirmed `BACKEND`. | Today `SampleApplication` sends to `BuildConfig.ARTRACKER_ENDPOINT` on every launch (the checked-in `local.properties` points at a live host). |
| R3 | `BACKEND` needs a **one-shot grant** (120 s, issued only after typing `KIRIM` in Settings, consumed at process start). A remote host in `LOCAL` is rejected. Load and fake-PII actions are disabled while the endpoint is remote. | A persisted "BACKEND" setting would otherwise reactivate silently on every relaunch. |
| R4 | The SDK queue files are wiped **before `init`** whenever the process moves from a non-remote to a remote endpoint. | `ARTrackerCore.start()` flushes leftover items right after init (`leftoverItems → pipeline.flushNow()`), so fake PII, bursts or OFFLINE-queued items would otherwise go to production the moment BACKEND is enabled. |
| R5 | Synthetic touch is dispatched through `activity.window.decorView.dispatchTouchEvent` with raw **screen** coordinates, preflight-checked, and warm-up gated. Its limits are explicit (activity window only, replay must be running, a first snapshot must exist). | `TouchRecorder` wraps `window.callback` only while replay runs and only for the resumed Activity's window; `ReplayController.onTouch` divides raw coordinates by density and drops a touch when no base snapshot of the same session exists. |
| R6 | Network lab and every local test use **MockWebServer** (in-app target server on `localhost:38458`, in-app sink on `localhost:38457`, test-owned `MockIngest`). httpbin.org is removed everywhere. | Review request; also closes the "external dependency" gap of revision 1. |
| R7 | Task 0 neutralises the production endpoint before running the existing app. | The unmodified app sends to `local.properties`' endpoint. |

## Assumptions (confirm before Task 1)

- Audience: SDK developers and QA, not a sales demo, so the UI may be technical.
- UI language: Indonesian, every user-facing string in `app/src/main/res/values/strings.xml`.
- The sink, the target server and the replay-chunk export exist in **debug builds only** (`debugImplementation(libs.mockwebserver)`, classes in `app/src/debug`, stubs in `app/src/release`). A release build of the demo has no sink and falls back to `OFFLINE`.
- Allowed new test/runtime dependencies in `:app`: `mockwebserver` (debug + tests), `ui-test-junit4` (tests), `org-json` (JVM tests, already in the catalog). Nothing else.
- Scope: Tasks 0–15. Cut lines that leave a shippable app: after Task 6 (observable demo), after Task 11 (all labs), after Task 13 (scenarios).

## SDK facts this plan depends on (verified in `:artracker-sdk` source)

- `EventPipeline.prepare`: `beforeSend` → (`replay_chunk` skips) `AttributeRedactor` on name, message and attributes → serialise. A throwing `beforeSend` keeps the original item.
- `IngestClient`: `HttpURLConnection` POST to `{endpoint}/api/v1/ingest`, headers `X-Artracker-Key`, `Content-Encoding: gzip`, fixed-length body; 2xx accepted, 401/403 kill-switch, 413 split, 429/503 `Retry-After` (delta-seconds, capped 1 h), other 4xx dropped, IOException retried. Cleartext only works for hosts in `app/src/debug/res/xml/network_security_config.xml` (`localhost`, `10.0.2.2`).
- `PayloadEncoder` envelope: `{sent_at, sdk, context, items:[…]}`; `Flusher.lastResult` values: `accepted`, `network_error`, `retry_after`, `unauthorized`, `disabled`, `http_<n>_dropped`, `queue_error`, `encode_failed_dropped`.
- `ARTrackerCore.start`/`maintainQueueAndSaveTransportConfig`: leftover queue items trigger an immediate flush at init; the endpoint and key are also saved to the `artracker_transport` prefs for the WorkManager worker and refreshed on every init.
- `ARTracker.optOut(true)` deletes everything queued; crash files in `filesDir/artracker/` are recovered at the next init.
- `ReplayController`: `TouchRecorder.wrap(window)` only inside `wrapTouch(activity)`, called from `begin()` and `onActivityResumed` **while recording**; `stop()` unwraps; `onTouch(kind, rawX, rawY, density)` emits `(rawX/density, rawY/density)` only if a base snapshot of the same session exists; `TouchRecorder` reports DOWN, UP/CANCEL and MOVE (MOVE throttled to one per 50 ms of `eventTime`) from `dispatchTouchEvent`, after the delegate handled it, never consuming; rrweb encoding: DOWN = incremental `source 2, type 7`, UP = `source 2, type 9`, MOVE = `source 6`.
- Only the Activity's own window is wrapped; touches in dialog/popup windows are not recorded.
- `ARTrackerOkHttpInterceptor` drops calls whose host **and effective port** equal the SDK endpoint's; URLs are scrubbed by `UrlScrubber`.
- `ErrorDeduper`: 10 reports per fingerprint per session; `captureException` dedupes before emitting.

## Global Constraints

- **`artracker-sdk/**` is never modified.** Verify at the end of every task: `git diff feature/android-sdk -- artracker-sdk` prints nothing.
- Work on branch `feature/demo-playground`, created from `feature/android-sdk` in Task 0.
- `minSdk 24`, `compileSdk 36`, Java 11 (as in `app/build.gradle.kts`).
- Compose BOM stays `2024.09.00`; `ComposeWalker` was only tested on Compose UI 1.7.x (README, "Replay").
- `ARTracker.diagnostics()` reads the database: **never call it on the main thread**.
- `ARTracker.init` runs once per process; a second call is ignored. **Multi-process is not supported:** `init` must run in the main process only.
- Config validation (SDK rules): `flushIntervalMs >= 1000`, `sessionTimeoutMs >= 1000`, `replay.sampleRate` and `network.sampleRate` within `0.0..1.0`, `replay.framesPerSecond` finite (clamped to `0.5..4.0`), API key `art_live_` prefix, 32–128 chars.
- **Endpoint safety:**
  - Default profile is `SINK`. `SINK` and `OFFLINE` always use `EndpointGuard.DUMMY_KEY` (not a secret). The real `BuildConfig.ARTRACKER_KEY` is used only for `LOCAL` and for a granted `BACKEND`.
  - An endpoint is `REMOTE` unless its host (parsed with `java.net.URI`) is `localhost`, `127.0.0.1`, `10.0.2.2` or ends with `.invalid`; a malformed URL or a non-http(s) scheme is `REMOTE` (fail closed).
  - A `REMOTE` endpoint is used only when a one-shot grant (TTL 120 000 ms, issued after typing `KIRIM`, consumed at process start) is present **and** the real key is non-blank; otherwise the app starts on `SINK` and shows why. A grant never survives a process start.
  - Before `init`, when the resolved endpoint is `REMOTE` and the previous run was not, the SDK queue files are wiped (`QueueWiper`).
  - While the endpoint is `REMOTE`, actions of kind `LOAD` and `FAKE_PII` are disabled (not merely confirmed); `CRASH` keeps its confirmation dialog.
- **Inspector data safety:** nothing shown comes from a pre-redaction source. No control reveals raw values. `beforeSend` is used only by `MetadataTap` (type, level, timestamp, 8-char session prefix, names/keys that match `^[A-Za-z0-9_.$:/-]{1,64}$`, value *kinds* only; message and user id never). Items are never written to disk or logcat, except the explicit debug-only replay-chunk export (masked chunks only).
- `beforeSend` must be fast, must not block and must return the item unchanged. Crash items bypass it. Redaction runs after it.
- **Local-first tests:** every JVM and instrumented test that needs HTTP uses MockWebServer on a loopback address. No test and no lab depends on an external host (`httpbin.org` is removed).
- **Synthetic touch rules:** events are dispatched on the main thread to `activity.window.decorView` (the framework's own entry into `Window.Callback`), carry `SOURCE_TOUCHSCREEN`, raw screen coordinates (window position added) and strictly increasing `eventTime`; taps target only composables registered in the activity window.
- The API key is never logged, displayed or written to the inspector. The UI only says whether a real key is present.
- Destructive actions (crash, ANR, opt-out) require a confirmation dialog.
- The five existing tests in `SampleAppTest` keep passing unchanged. `SampleApp.config(endpoint, key, debug)` and `SampleApp.initTracker(app, endpoint, key, debug)` keep their current behaviour when the new trailing parameters are omitted.
- New behaviour is driven by tests first; each task ends with a commit. Commit messages follow the repo style (`feat(sample): ...`, `test(sample): ...`) and end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Test commands: JVM `./gradlew :app:testDebugUnitTest`; instrumented `./gradlew :app:connectedDebugAndroidTest` (needs an emulator or device).

## Review Focus

1. **A remote endpoint becomes active without a fresh confirmation** (persisted BACKEND, expired or reused grant, blank real key, `LOCAL` typed with a remote host, a lookalike host such as `localhost.evil.com` or `http://localhost@evil.com`): expected to fall back to `SINK` with a visible reason. Pinned in Tasks 1, 2 and 6.
2. **Leftover queue flushed to production at init** (OFFLINE bursts, hostile payloads or a fake crash queued earlier): expected to be wiped before `init`, and the wiper's file names must match the real SDK files. Pinned in Tasks 2 and 14.
3. **A raw value reaches the Inspector** (typed password, email, card, JWT, Bearer, an email used as an event name, a 24 KB replay chunk): expected never shown pre-redaction; memory bounded. Pinned in Tasks 3 and 14.
4. **A synthetic tap that is silently not recorded** (replay stopped or unsampled, tap before the first snapshot, tap after `reset()` before a new snapshot, target inside a dialog window): expected an explicit preflight warning or a failed step, never a silent pass. Pinned in Tasks 12, 13 and 14.
5. **Invalid saved settings or a busy sink port** (`sampleRate = 1.5`, `flushIntervalMs = 500`, corrupted prefs, port 38457 in use): expected to fall back to defaults / `OFFLINE` with a notice, and the app still opens. Pinned in Tasks 1, 2 and 3.

## File Structure

All paths below are under `app/src/main/java/id/art/tracker/` unless prefixed (tests mirror them under `app/src/test/java/...`; debug-only code and its tests under `app/src/debug/java/...` and `app/src/testDebug/java/...`; release stubs under `app/src/release/java/...`; instrumented tests under `app/src/androidTest/java/...`).

| Path | Responsibility |
| --- | --- |
| `playground/settings/PlaygroundSettings.kt`, `EndpointGuard.kt` | Settings model, validation, presets, `toConfig`; endpoint classification and constants |
| `playground/settings/SettingsStore.kt`, `SettingsEditor.kt` | Persistence; pure draft/validate/preset/confirmation state for the Settings screen |
| `playground/boot/BackendGrantStore.kt`, `Bootstrap.kt`, `QueueWiper.kt`, `LocalServers.kt` | One-shot backend grant, endpoint/key resolution, pre-init queue wipe, interface to the debug-only servers |
| `playground/ProcessInfo.kt`, `ProcessRestarter.kt`, `RestartActivity.kt` | Main-process guard and in-app restart |
| `playground/inspector/` | `InspectedItem`, `EnvelopeParser`, `MetadataTap`, `EventRing`, `Inspector`, `InspectorFilter` |
| `debug/.../playground/local/` | `InAppSink`, `HttpTargetServer`, `LocalServersProvider` (debug implementation) |
| `release/.../playground/local/LocalServersProvider.kt` | Release stub (nothing available) |
| `playground/status/` | `SdkControls`, `SdkStatusRepository`, `SessionHistory` |
| `playground/ui/` | Theme, host/shell, `StatusStrip`, `ActionCard`, `ConfirmDialog`, `Feedback`, `Routes`, `InspectorScreen`, `SettingsScreen` |
| `playground/labs/` | The eight labs, `LabGuard`, pure helpers |
| `playground/scenario/` | `Scenario`, `ScenarioRunner`, `TapRegistry`, `SyntheticTouch`, `ActivityHolder`, `ScenarioFields`, `definitions/`, `shop/` |
| `sample/` (existing) | `SampleApp`, `SampleApplication`, `SampleHttp`, `legacy/` stay; `sample/screens/` is deleted in Task 15 |

---

### Task 0: Baseline on a device

**Files:**
- Create: `docs/demo-baseline.md`

**Interfaces:**
- Produces: a recorded baseline of what the current app does on a real emulator (README says it was never run on a device), taken **without ever reaching the production endpoint**.

- [ ] **Step 1: Create the branch**

Run: `git checkout -b feature/demo-playground`
Expected: `Switched to a new branch 'feature/demo-playground'`

- [ ] **Step 2: Run the existing automated suite**

Run: `./gradlew :artracker-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, 0 failures. If anything fails, stop and report; do not start Task 1.

- [ ] **Step 3: Neutralise the production endpoint for the baseline (the current app sends to `local.properties`' endpoint on every launch)**

Run: `cp local.properties local.properties.bak && sed -i '' 's#^artracker.endpoint=.*#artracker.endpoint=http://sink.playground.invalid#' local.properties && grep '^artracker.endpoint' local.properties`
Expected: prints `artracker.endpoint=http://sink.playground.invalid`. The file is git-ignored; it is restored in Step 6. Never run any baseline step with the production endpoint.

- [ ] **Step 4: Check a device exists**

Run: `adb devices`
Expected: at least one device in state `device`. If none: **restore the file (Step 6) and ask the user** (Tasks 9 and 14 need one; Tasks 1–8 do not).

- [ ] **Step 5: Run the manual checklist sections 3, 6 and 7 of `docs/e2e-manual-test.md` against the black-hole endpoint**

Run: `./gradlew :app:installDebug`, then perform the steps that do not need a backend response (clicks, screens, replay start/stop, opt-out, `Diagnostics` panel showing `network_error`), using `adb logcat -s ARTracker ARTrackerSample`.
Expected: each step noted pass/fail in `docs/demo-baseline.md` with the logcat line that proves it; steps that need a backend are marked "deferred to Task 14/15".

- [ ] **Step 6: Restore the local properties and commit**

```bash
mv local.properties.bak local.properties
git add docs/demo-baseline.md
git commit -m "docs(sample): record demo app baseline on a device"
```
Expected: `git status --short` shows no change to `local.properties` (ignored) and `grep '^artracker.endpoint' local.properties` shows the original value again.

---

### Task 1: Settings model, endpoint guard, validation, presets and store

**Files:**
- Create: `playground/settings/EndpointGuard.kt`, `playground/settings/PlaygroundSettings.kt`, `playground/settings/SettingsStore.kt`
- Test: `playground/settings/EndpointGuardTest.kt`, `PlaygroundSettingsTest.kt`, `SettingsStoreTest.kt` (Robolectric, `@Config(sdk = [34])`, one SDK level like `SampleAppTest`)

**Interfaces:**
- Produces:
```kotlin
enum class EndpointProfile { SINK, OFFLINE, LOCAL, BACKEND }
enum class EndpointClass { SAFE_LOCAL, REMOTE }
object EndpointGuard {
    const val SINK_PORT = 38457
    const val TARGET_PORT = 38458
    const val SINK_ENDPOINT = "http://localhost:38457"
    const val OFFLINE_ENDPOINT = "http://sink.playground.invalid"
    val DUMMY_KEY: String                       // "art_live_" + 43 filler characters, valid for ARTrackerConfig
    fun classify(url: String): EndpointClass    // see Global Constraints; never throws
    fun hostOf(url: String): String             // for display; "?" when unparseable
}
data class PlaygroundSettings(
    val endpointProfile: EndpointProfile = EndpointProfile.SINK,
    val localEndpoint: String = "http://10.0.2.2:3000",
    val environment: String = "sample",
    val replayEnabled: Boolean = true,
    val replaySampleRate: Double = 1.0,
    val replayFps: Double = 1.0,
    val maskAllInputs: Boolean = true,
    val maskAllText: Boolean = false,
    val networkSampleRate: Double = 1.0,
    val captureOnlyErrors: Boolean = false,
    val ignoreHosts: Set<String> = emptySet(),
    val flushIntervalMs: Long = 10_000,
    val sessionTimeoutMs: Long = 1_800_000,
) {
    fun validate(): List<String>
    /** BACKEND -> [backendEndpoint]; the other profiles ignore it. */
    fun endpointFor(backendEndpoint: String): String
    fun isRemote(backendEndpoint: String): Boolean        // classify(endpointFor(..)) == REMOTE
    /** [endpoint] is already resolved by Bootstrap. */
    fun toConfig(endpoint: String, apiKey: String, debug: Boolean, beforeSend: BeforeSend? = null): ARTrackerConfig
    companion object { val DEFAULT: PlaygroundSettings }
}
enum class SettingsPreset { DEFAULT, PRIVACY_STRICT, DEBUG_FAST, LOW_BANDWIDTH; fun apply(base: PlaygroundSettings): PlaygroundSettings }
interface SettingsStore { fun load(): PlaygroundSettings; fun save(settings: PlaygroundSettings) }
class SharedPrefsSettingsStore(context: Context) : SettingsStore   // prefs file "playground_settings"
```

- [ ] **Step 1: Write the failing tests**

`EndpointGuardTest`:
- `safeLocalHosts`: `http://localhost:38457`, `http://LOCALHOST`, `http://127.0.0.1:3000`, `http://10.0.2.2:3000`, `http://sink.playground.invalid`, `http://a.b.invalid/path` → `SAFE_LOCAL`.
- `remoteHostsAndLookalikes`: `https://artracker.exlm.my.id/`, `https://203.0.113.1`, `http://localhost.evil.com`, `http://10.0.2.2.evil.com`, `http://localhost@evil.com`, `http://evil.com#@localhost`, `ftp://localhost`, `localhost:3000` (no scheme), `""`, `"not a url"` → `REMOTE` (Review Focus 1).
- `constantsAreConsistent`: `SINK_ENDPOINT == "http://localhost:$SINK_PORT"`; `classify(SINK_ENDPOINT)` and `classify(OFFLINE_ENDPOINT)` are `SAFE_LOCAL`; `DUMMY_KEY` starts with `art_live_`, length within 32..128, and `DEFAULT.toConfig(SINK_ENDPOINT, DUMMY_KEY, false)` has `validate()`-clean values (build a config and assert the SDK accepts it later in Task 2's init test).
- `hostOfReturnsOnlyTheHost`: `hostOf("https://u:p@h.example:8443/x?k=v") == "h.example"` (no credentials, port or query ever displayed); `hostOf("bad") == "?"`.

`PlaygroundSettingsTest`:
- `defaultProfileIsSink`: `DEFAULT.endpointProfile == EndpointProfile.SINK`.
- `defaultConfigMatchesLegacyValues`: `DEFAULT.toConfig("http://localhost:1", KEY, debug = false)` has `environment == "sample"`, `replay.enabled`, `replay.sampleRate == 1.0`, `replay.framesPerSecond == 1.0`, `privacy.maskAllInputs`, `flushIntervalMs == 10_000`, `sessionTimeoutMs == 1_800_000`, `endpoint == "http://localhost:1"`.
- `validateRejectsEachBoundary`: `flushIntervalMs = 999` → 1 error, `1000` → none; same for `sessionTimeoutMs`; `replaySampleRate = 1.0001` and `-0.1` → error, `0.0` and `1.0` ok; `networkSampleRate` likewise; `replayFps = Double.NaN` → error, `0.1` → no error (the SDK clamps it).
- `localProfileRejectsARemoteHost`: `endpointProfile = LOCAL, localEndpoint = "https://artracker.exlm.my.id"` → at least one error mentioning the profile Backend; `localEndpoint = "http://10.0.2.2:3000"` → no error.
- `endpointForPicksTheProfile`: SINK → `SINK_ENDPOINT`, OFFLINE → `OFFLINE_ENDPOINT`, LOCAL → `localEndpoint`, BACKEND → the argument; `isRemote` true only for BACKEND with a remote argument.
- `presetsAreValidAndDistinct`: every `SettingsPreset.apply(DEFAULT).validate()` is empty and keeps `endpointProfile == SINK` (a preset never changes the endpoint profile); `PRIVACY_STRICT` has `maskAllText && maskAllInputs`; `DEBUG_FAST` has `flushIntervalMs == 1_000` and `sessionTimeoutMs == 10_000`; `LOW_BANDWIDTH` has `replaySampleRate == 0.1`, `captureOnlyErrors`, `flushIntervalMs == 30_000`.

`SettingsStoreTest`:
- `roundTripsEveryField` (non-default value for each field, `ignoreHosts = setOf("a.com","b.com")`, `endpointProfile = OFFLINE`).
- `emptyStoreLoadsDefault` (profile `SINK`).
- `corruptValuesFallBackToDefault`: write `"replaySampleRate"` as a String and an unknown `endpointProfile` name directly into the prefs; `load()` returns `DEFAULT` and does not throw.
- `loadNeverReturnsInvalidSettings`: stored `flushIntervalMs = 5` → `load()` returns `DEFAULT` (Review Focus 5).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.settings.*"`
Expected: FAIL (classes not defined).

- [ ] **Step 3: Implement** `EndpointGuard`, `PlaygroundSettings`, `SettingsPreset`, `SettingsStore`, `SharedPrefsSettingsStore`

`classify` parses with `java.net.URI` inside `runCatching`, requires scheme `http`/`https` and a non-null host, lowercases the host, and compares it to the three literal hosts and the `.invalid` suffix. `validate()` mirrors the SDK rules in Global Constraints plus the LOCAL rule (messages in Indonesian, one per violated rule). `load()` wraps reading in `runCatching` and returns `DEFAULT` on any failure or when `validate()` is non-empty.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.settings.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/art/tracker/playground/settings app/src/test/java/id/art/tracker/playground/settings
git commit -m "feat(sample): add playground settings with an endpoint guard, validation and presets"
```

---

### Task 2: Safe bootstrap, backend grant, queue wipe, main-process guard, in-app restart

**Files:**
- Modify: `sample/SampleApp.kt`, `sample/SampleApplication.kt`, `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts` (`debugImplementation(libs.mockwebserver)`, `testImplementation(libs.org.json)`)
- Create: `playground/boot/BackendGrantStore.kt`, `Bootstrap.kt`, `QueueWiper.kt`, `LocalServers.kt`, `playground/ProcessInfo.kt`, `ProcessRestarter.kt`, `RestartActivity.kt`, `app/src/debug/java/id/art/tracker/playground/local/LocalServersProvider.kt` (temporary: nothing available), `app/src/release/java/id/art/tracker/playground/local/LocalServersProvider.kt` (final: nothing available)
- Test: `playground/boot/BackendGrantStoreTest.kt`, `BootstrapTest.kt`, `QueueWiperTest.kt`, `playground/ProcessInfoTest.kt`; modify `sample/SampleAppTest.kt` (add tests only)

**Interfaces:**
- Consumes: `PlaygroundSettings`, `EndpointGuard`, `EndpointProfile`, `SharedPrefsSettingsStore` (Task 1).
- Produces:
```kotlin
interface BackendGrantStore { fun issue(nowMs: Long); fun consume(nowMs: Long): Boolean }   // one-shot; valid while nowMs - issuedAt <= 120_000
class SharedPrefsBackendGrantStore(context: Context) : BackendGrantStore                  // uses commit()
interface RunMarkerStore { var lastRunRemote: Boolean }                                    // SharedPrefs implementation in the same file
data class EndpointBadge(val profile: EndpointProfile, val host: String, val remote: Boolean)
data class Resolved(val endpoint: String, val apiKey: String, val badge: EndpointBadge, val notice: String?)
object Bootstrap {
    fun resolve(settings: PlaygroundSettings, buildEndpoint: String, buildKey: String,
                grantConsumed: Boolean, sinkAvailable: Boolean): Resolved
    fun shouldWipe(resolvedRemote: Boolean, lastRunRemote: Boolean): Boolean   // resolvedRemote && !lastRunRemote
}
object QueueWiper { fun wipe(databasesDir: File, filesDir: File): Int }        // returns number of entries deleted
interface LocalServers {
    val sinkAvailable: Boolean
    fun startSink(): Boolean
    fun startTargets(): Boolean
    val targetBaseUrl: String?
    fun exportReplayChunks(dir: File): File?
}
object LocalServersProvider { val instance: LocalServers }     // same FQN in src/debug and src/release
fun isMainProcess(processName: String?, packageName: String): Boolean
object ProcessRestarter { fun restart(context: Context) }
// SampleApp (trailing parameters only; null settings = legacy behaviour, endpoint passed through untouched)
fun initTracker(app: Application, endpoint: String, key: String, debug: Boolean,
                settings: PlaygroundSettings? = null, beforeSend: BeforeSend? = null): Boolean
fun config(endpoint: String, key: String, debug: Boolean,
           settings: PlaygroundSettings? = null, beforeSend: BeforeSend? = null): ARTrackerConfig
```

- [ ] **Step 1: Write the failing tests**

`BackendGrantStoreTest` (fake clock values, Robolectric prefs): `consumeWithoutIssueIsFalse`; `issueThenConsumeIsTrueOnlyOnce`; `consumeAtExactlyTtlIsTrue` (issue at 1 000, consume at 121 000); `consumeAfterTtlIsFalseAndClearsTheGrant` (121 001, then a later consume at 121 002 is also false); `issueTwiceKeepsOnlyTheLatest`.
`BootstrapTest` (Review Focus 1 and 5):
- `sinkWhenAvailableUsesTheSinkAndTheDummyKey`: profile SINK, `sinkAvailable = true`, `buildKey = REAL` → `endpoint == SINK_ENDPOINT`, `apiKey == DUMMY_KEY`, `badge.remote == false`, `notice == null`.
- `sinkUnavailableFallsBackToOfflineWithNotice` (release build, or port busy).
- `offlineUsesTheBlackHoleAndTheDummyKey`.
- `localUsesTheRealKeyWhenPresentElseDummy`; endpoint is `settings.localEndpoint`.
- `backendWithoutGrantFallsBackToSinkWithNotice`: `buildEndpoint = "https://artracker.exlm.my.id/"`, `grantConsumed = false` → endpoint `SINK_ENDPOINT`, `apiKey == DUMMY_KEY`, notice non-blank, `badge.remote == false`.
- `backendWithGrantButBlankKeyFallsBackToSink`.
- `backendWithGrantAndKeyIsTheOnlyPathToTheRealKeyAndRemoteEndpoint`: endpoint `https://artracker.exlm.my.id/`, `apiKey == REAL`, `badge.remote == true`, `badge.host == "artracker.exlm.my.id"`.
- `backendWithALocalBuildEndpointNeedsNoGrant`: `buildEndpoint = "http://10.0.2.2:3000"`, `grantConsumed = false` → uses it, `remote == false`.
- `realKeyNeverAppearsForSinkOrOffline`: iterate profiles SINK/OFFLINE with every flag combination → `apiKey == DUMMY_KEY`.
- `shouldWipeOnlyWhenMovingIntoRemote`: `(true,false)` true; `(true,true)`, `(false,false)`, `(false,true)` false.
`QueueWiperTest` (JUnit `TemporaryFolder`): create `databases/artracker.db`, `artracker.db-wal`, `artracker.db-shm`, `artracker.db-journal`, `files/artracker/crash/x.json`, plus unrelated `databases/other.db` and `files/keep.txt`; `wipe` returns 5, the five SDK entries are gone, the two unrelated files remain; wiping an empty directory returns 0 and does not throw; a missing directory does not throw.
`ProcessInfoTest`: `isMainProcess("id.art.tracker", "id.art.tracker")` true; `"id.art.tracker:restart"` false; `null` false (fail closed); `"id.art.tracker.other"` false.
`SampleAppTest` additions (same single Robolectric level, same blank-key technique): `initTrackerWithBlankKeyStillReturnsFalseWithSettings`; `configWithoutSettingsPassesTheEndpointThrough` (`config("https://b", KEY, false).endpoint == "https://b"`); `configWithSettingsUsesTheResolvedEndpointArgument`; `dummyKeyAndSinkEndpointPassSdkValidation`: `ARTracker.init` is not called, but `SampleApp.config(SINK_ENDPOINT, DUMMY_KEY, false, DEFAULT)` is built and its `endpoint`/`apiKey` equal the inputs (the SDK's own `validate()` is internal; Step 6 proves acceptance on a device).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.boot.*" --tests "id.art.tracker.playground.ProcessInfoTest" --tests "id.art.tracker.sample.SampleAppTest"`
Expected: FAIL (compile errors: new symbols missing).

- [ ] **Step 3: Implement**

- `SampleApp.config` with `settings == null` returns exactly today's configuration; with settings it delegates to `settings.toConfig(endpoint, key, debug, beforeSend)` (the caller passes the already-resolved endpoint).
- `Bootstrap.resolve` implements the rules in the tests; `notice` strings are Indonesian and never contain the key.
- `SharedPrefsBackendGrantStore.consume` reads and clears the grant with `commit()` in one step.
- `LocalServersProvider` (debug) is a temporary object whose `sinkAvailable = true`, `startSink() = false`, `startTargets() = false`, `targetBaseUrl = null`, `exportReplayChunks = null`; Task 3 and Task 10 replace the bodies. The release variant is final: `sinkAvailable = false`, everything else the same.
- `ProcessInfo.currentProcessName(app)`: `Application.getProcessName()` on API 28+, else `ActivityManager.runningAppProcesses` matched on `Process.myPid()`; null when unknown.
- `SampleApplication.onCreate`: return unless `isMainProcess(...)`. Otherwise: `settings = SharedPrefsSettingsStore.load()`; if the profile is SINK, `up = LocalServersProvider.instance.startSink()`; `grant = BackendGrantStore.consume(now)`; `resolved = Bootstrap.resolve(settings, BuildConfig.ARTRACKER_ENDPOINT, BuildConfig.ARTRACKER_KEY, grant, up)`; if `Bootstrap.shouldWipe(resolved.badge.remote, RunMarkerStore.lastRunRemote)` then `QueueWiper.wipe(getDatabasePath("x").parentFile, filesDir)`; set `RunMarkerStore.lastRunRemote = resolved.badge.remote`; keep `resolved` in an in-memory holder for the UI; call `SampleApp.initTracker(this, resolved.endpoint, resolved.apiKey, BuildConfig.DEBUG, settings, beforeSend = null)` (Task 3 supplies the beforeSend).
- `RestartActivity` (manifest `android:process=":restart"`, `exported="false"`, no UI): starts the launcher intent with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK`, kills the pid passed in an extra, finishes. `ProcessRestarter.restart` starts it with `Process.myPid()`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (existing 5 + new tests).

- [ ] **Step 5: Verify the release build still builds without MockWebServer**

Run: `./gradlew :app:assembleRelease && ./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -c mockwebserver`
Expected: `BUILD SUCCESSFUL`; the count is `0`.

- [ ] **Step 6: Verify on a device (no production traffic possible: the default profile is the black-hole fallback until Task 3)**

Run: `./gradlew :app:installDebug`, launch, then `adb logcat -d -s ARTracker | grep -c "invalid configuration"` and trigger `ProcessRestarter.restart` via `adb shell am start -n id.art.tracker/.playground.RestartActivity --ei pid <pid>`.
Expected: `0` invalid-configuration lines (the dummy key and the fallback endpoint pass SDK validation); the app relaunches in a new pid; exactly one init per launch, none from the `:restart` process.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src
git commit -m "feat(sample): resolve the endpoint safely at boot with a one-shot backend grant and a pre-init queue wipe"
```

---

### Task 3: Event inspector (post-redaction sink + metadata-only tap)

**Files:**
- Create: `playground/inspector/InspectedItem.kt`, `EnvelopeParser.kt`, `MetadataTap.kt`, `EventRing.kt`, `Inspector.kt`, `InspectorFilter.kt`; `app/src/debug/java/id/art/tracker/playground/local/InAppSink.kt`
- Modify: `app/src/debug/.../LocalServersProvider.kt` (real `startSink`, `exportReplayChunks`), `sample/SampleApplication.kt` (pass `Inspector.beforeSendFor(profile)`)
- Test: `playground/inspector/EnvelopeParserTest.kt`, `MetadataTapTest.kt`, `EventRingTest.kt`, `InspectorFilterTest.kt`; `app/src/testDebug/java/id/art/tracker/playground/local/InAppSinkTest.kt`

**Interfaces:**
- Consumes: `EndpointProfile`, `EndpointGuard.SINK_PORT` (Task 1), `LocalServers` (Task 2).
- Produces:
```kotlin
enum class ItemSource { SENT_PAYLOAD, METADATA_ONLY }
data class InspectedItem(val id: String, val type: String, val name: String, val level: String?, val message: String?,
    val timestampMs: Long, val sessionId: String, val userId: String?, val attributes: Map<String, String>,
    val truncated: Boolean, val source: ItemSource)
object EnvelopeParser { fun parse(gunzippedJson: String): List<InspectedItem> }   // never throws; invalid JSON -> emptyList; attribute values clipped to 200 chars; replay_chunk -> summary only
object MetadataTap { val beforeSend: BeforeSend; fun toInspected(item: TelemetryItem): InspectedItem }   // returns the SAME item instance from beforeSend
class EventRing(private val capacity: Int = 200) {
    val items: StateFlow<List<InspectedItem>>      // newest first
    val totalSeen: StateFlow<Long>
    fun add(item: InspectedItem); fun addAll(items: List<InspectedItem>); fun clear()   // never throw, thread-safe
}
object Inspector { val ring: EventRing; fun beforeSendFor(profile: EndpointProfile): BeforeSend? }   // null for SINK (the sink shows everything), MetadataTap.beforeSend otherwise
data class InspectorFilter(val types: Set<String> = emptySet(), val query: String = "") { fun matches(item: InspectedItem): Boolean }
class InAppSink(requestedPort: Int = EndpointGuard.SINK_PORT, private val ring: EventRing = Inspector.ring) {   // src/debug, MockWebServer based
    fun start(): Boolean                 // binds InetAddress.getByName("localhost") only; false if the port is busy; never throws
    val port: Int
    fun shutdown()
    fun exportReplayChunks(dir: File): File?   // last 50 replay_chunk attribute objects, intact, as replay-chunks.json
}
```
Sink behaviour: `POST /api/v1/ingest` → gunzip with a 4 MB uncompressed cap → `EnvelopeParser.parse` → `ring.addAll` → `202 {"status":"accepted","queued":n}`; corrupt gzip → `400`; over the cap → `413` (nothing parsed); any other method or path → `404`. The `X-Artracker-Key` value is never read into any stored structure.

- [ ] **Step 1: Write the failing tests**

`EnvelopeParserTest`: a two-item envelope (`{"sent_at":..,"sdk":{..},"context":{..},"items":[..]}`) yields two `SENT_PAYLOAD` items with the right `type`, `name`, `level`, `sessionId`, `userId`, `timestampMs` (ISO-8601 parsed) and string-valued attributes; `{"k":"v"}` is clipped at 200 chars (`truncated == true`); `replay_chunk` with a 10 000-element `events` array → `attributes["events"] == "10000 events"`, other keys limited to `seq,start,end,full_snapshot,platform`; invalid JSON, a truncated document, an empty string, and an `items` field that is not an array → `emptyList()`, no throw; a 50 000-item array is parsed but the result is capped at 500 items (the backend's own per-batch hard limit, so a hostile body cannot exhaust memory).
`MetadataTapTest` (Review Focus 3):
- `neverStoresAnyValue`: item with `attributes = {"password":"hunter2","email":"qa@example.invalid","note":"hello"}`, `message = "pwd hunter2"`, `userId = "u-1"` → the `InspectedItem.toString()` contains none of `hunter2`, `qa@example.invalid`, `hello`; `message == null`; `userId == null`; `attributes["password"] == "string(7)"`, `attributes["note"] == "string(5)"`.
- `valueKindsAreDescribed`: number → `"number"`, boolean → `"boolean"`, null → `"null"`, map → `"map"`, list → `"list"`.
- `unsafeNamesAreReplaced`: name `qa@example.invalid` or a 200-char name → `"<nama disamarkan>"`; `checkout_clicked`, `$identify`, `screen_view` pass; an unsafe attribute key becomes `"<kunci disamarkan>"` (suffixed by index when repeated, so no collisions).
- `sessionIdIsTruncatedToEightChars`.
- `beforeSendReturnsTheSameInstanceAndNeverThrows`: `MetadataTap.beforeSend.process(item) === item`; hostile attribute values (`Double.NaN`, nested maps, a lone surrogate, an object whose `toString()` throws) do not throw and the same instance is returned.
`EventRingTest`: `capacityIsEnforcedNewestFirst` (250 adds → size 200, first is the newest, `totalSeen == 250`); `concurrentAddsStayBounded` (8 threads × 1 000 adds → size 200, `totalSeen == 8000`, no exception); `clearEmptiesTheListButKeepsTotalSeen`.
`InspectorFilterTest`: matches by type and by case-insensitive query over `name`, `message` and attribute keys/values; the empty filter matches everything.
`InAppSinkTest` (`src/testDebug`, plain JUnit + the same `HttpURLConnection` calls the SDK makes, port `0` so it never collides):
- `acceptsAGzipEnvelopeAndShowsItsItems`: POST two items with headers `X-Artracker-Key`, `Content-Encoding: gzip`, fixed-length body → `202`, body `{"status":"accepted","queued":2}`, the ring holds two `SENT_PAYLOAD` items.
- `bindsToLoopbackOnly`: the server's bound address `isLoopbackAddress` (never `0.0.0.0`, so other devices on the network cannot reach it).
- `startReturnsFalseWhenThePortIsBusy`: a `ServerSocket` already holds the port → `start() == false`, no throw (Review Focus 5).
- `wrongMethodOrPathIs404`; `corruptGzipIs400AndDoesNotThrow`; `bodyOverTheCapIs413AndIsNotParsed` (5 MB of zeros gzipped).
- `neverStoresTheApiKey`: after a request, no string in the ring or in the exported file contains the key sent in the header.
- `replayChunksAreExportedIntactAndCappedAtFifty`: send 60 `replay_chunk` items → `exportReplayChunks` returns a file whose JSON array has exactly 50 entries (the newest), each with its full `events` array; the ring holds summaries only.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.inspector.*" --tests "id.art.tracker.playground.local.*"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`EnvelopeParser` uses `org.json` (available on Android; `testImplementation(libs.org.json)` from Task 2 supplies a real one on the JVM, as in `:artracker-sdk`). `InAppSink` uses `MockWebServer` with a custom `Dispatcher`, started on `InetAddress.getByName("localhost")` and the requested port (wrapped in `runCatching`); the replay-chunk vault is a bounded `ArrayDeque<String>` of 50 chunk-attribute JSON strings. `LocalServersProvider` (debug) wires `startSink()` to a single `InAppSink` instance (idempotent) and `exportReplayChunks` to it. `SampleApplication` now passes `Inspector.beforeSendFor(settings.endpointProfile)`; for SINK this is `null`, so the rich view comes only from the sink and nothing is shown twice.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (the five `SampleAppTest` tests are unaffected: they use `TestApplication`).

- [ ] **Step 5: Verify on a device**

Run: `./gradlew :app:installDebug`, launch, press the old Diagnostics "Track event" and "Flush", then `adb logcat -d -s ARTracker`.
Expected: no `network_error` for the SDK (the SDK posts to `localhost:38457` and gets `202`); `ARTracker.diagnostics().lastSendResult == accepted` (visible in the old Diagnostics text).

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(sample): add an inspector fed by a loopback sink (post-redaction) with a metadata-only fallback"
```

---

### Task 4: Control state, status polling and session history

**Files:**
- Create: `playground/status/SdkControls.kt`, `SdkStatusRepository.kt`, `SessionHistory.kt`
- Test: `playground/status/SdkControlsTest.kt`, `SdkStatusRepositoryTest.kt`, `SessionHistoryTest.kt`

**Interfaces:**
- Consumes: `PlaygroundSettings` (Task 1), `Diagnostics` (SDK, public).
- Produces:
```kotlin
interface ControlStore { var optedOut: Boolean }                         // persisted (the SDK persists opt-out too)
class SharedPrefsControlStore(context: Context) : ControlStore
class SdkControls(private val store: ControlStore, settings: PlaygroundSettings,
                  private val sdk: SdkSwitches = SdkSwitches.Real) {
    val optedOut: Boolean
    val replayOn: Boolean                         // in memory; starts as settings.replayEnabled on every process start
    fun setOptOut(enabled: Boolean)               // calls sdk.optOut and persists
    fun setReplay(on: Boolean)                    // calls sdk.startReplay / stopReplay
}
interface SdkSwitches { fun optOut(e: Boolean); fun startReplay(); fun stopReplay(); object Real : SdkSwitches }
data class SdkStatus(val initialized: Boolean, val sessionId: String?, val pending: Int, val lastSend: String?,
                     val disabled: Boolean, val optedOutIntent: Boolean, val replayIntent: Boolean) {
    val disabledByOtherThanOptOut: Boolean        // disabled && !optedOutIntent (kill-switch suspected)
}
class SdkStatusRepository(private val source: () -> Diagnostics = ARTracker::diagnostics, private val controls: SdkControls) {
    fun poll(): SdkStatus                          // blocking; callers use Dispatchers.IO
}
class SessionHistory(private val max: Int = 10) { val ids: List<String>; fun observe(sessionId: String?) }
```

- [ ] **Step 1: Write the failing tests**

- `SdkControlsTest.optOutSurvivesRelaunch`: set opt-out on `SdkControls` A (fake store + fake `SdkSwitches`), build `SdkControls` B over the same store → `B.optedOut == true`.
- `replayIntentStartsFromSettingsOnEveryStart`: A calls `setReplay(false)`; B built with `replayEnabled = true` → `B.replayOn == true` (fixes the "tombol bilang Start replay padahal sudah merekam" bug); with `replayEnabled = false` → false.
- `togglesCallTheSdkOnce`: fake `SdkSwitches` records `optOut(true)`, `stopReplay()`, `startReplay()` exactly once each.
- `SdkStatusRepositoryTest`: fake `source` returns `Diagnostics(true,"s1",4,"accepted",false)` → status mirrors it; `disabled=true` with `optedOutIntent=false` → `disabledByOtherThanOptOut`; with `optedOutIntent=true` → false; `Diagnostics(initialized=false, ...)` → `initialized=false` (the strip shows "SDK tidak aktif").
- `SessionHistoryTest`: observing `a, a, b, null, b, c` yields `["a","b","c"]`; capped at `max` keeping newest.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.status.*"`
Expected: FAIL.

- [ ] **Step 3: Implement** the three files

`SdkSwitches.Real` forwards to `ARTracker`. No coroutines inside `SdkStatusRepository` (keeps tests free of `kotlinx-coroutines-test`); the UI loop in Task 5 calls `poll()` inside `withContext(Dispatchers.IO)` every 1 000 ms.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.status.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat(sample): add persisted SDK control state and diagnostics polling"
```

---

### Task 5: Design system and navigation shell

**Files:**
- Modify: `app/build.gradle.kts` (add `testImplementation(platform(libs.androidx.compose.bom))`, `testImplementation(libs.androidx.compose.ui.test.junit4)`; `ui-test-manifest` is already `debugImplementation`), `MainActivity.kt`, `res/values/strings.xml`, `res/values/colors.xml`, `ui/theme/Theme.kt` (keep the file, replace palette)
- Create: `playground/ui/Routes.kt`, `PlaygroundTheme.kt`, `PlaygroundHost.kt`, `StatusStrip.kt`, `ActionCard.kt`, `ConfirmDialog.kt`, `Feedback.kt`
- Test: `playground/ui/ActionCardTest.kt`, `playground/ui/StatusStripTest.kt` (Robolectric + `createComposeRule()`, the same setup `:artracker-sdk` already uses for Compose tests)

**Interfaces:**
- Consumes: `SdkStatus` (Task 4), `EndpointBadge` (Task 2).
- Produces:
```kotlin
sealed class Route(val path: String) { Skenario, Lab, Inspector, Pengaturan, plus lab detail routes "lab/{id}" }
@Composable fun ActionCard(title: String, description: String, modifier: Modifier = Modifier,
    destructive: Boolean = false, confirmText: String? = null, disabledReason: String? = null,
    onRun: () -> String?)                                   // returned text becomes a snackbar; disabledReason != null greys the card and shows the reason
class Feedback { fun show(message: String) }; val LocalFeedback: ProvidableCompositionLocal<Feedback>
@Composable fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit)
@Composable fun StatusStrip(status: SdkStatus?, badge: EndpointBadge, notice: String?, modifier: Modifier = Modifier)
@Composable fun PlaygroundHost()      // NavigationBar (4 tabs) + TopAppBar with back + StatusStrip + SnackbarHost
```
The old `login`, `payment`, `feed`, `diagnostics` screens stay reachable from the Lab tab under "Layar lama" until Task 15, **except** the three HTTP buttons of the old Diagnostics screen, which are removed in this task (they call httpbin.org; the Network lab of Task 10 replaces them with local targets).

- [ ] **Step 1: Write the failing tests**

- `ActionCardTest.tapRunsActionShowsFeedbackAndCountsPresses`: tap twice → `onRun` called twice, counter text "2×", feedback received the returned message.
- `destructiveCardNeedsConfirmation`: tap → `onRun` not called, dialog visible; press "Batal" → still not called; tap again + "Lanjut" → called once.
- `disabledCardNeverRunsAndShowsTheReason`: `disabledReason = "dinonaktifkan pada backend nyata"` → tap does nothing, the reason text is displayed.
- `nullResultShowsNoFeedback`.
- `StatusStripTest.showsInactiveWhenNotInitialized`: `SdkStatus(initialized=false,...)` → node with text "SDK tidak aktif"; `disabledByOtherThanOptOut` → text containing "dinonaktifkan server"; `null` → "memuat".
- `StatusStripTest.showsASinkBadgeAndARemoteBanner`: `EndpointBadge(SINK, "localhost", remote = false)` → text "SINK" and no node with "PRODUKSI"; `EndpointBadge(BACKEND, "artracker.exlm.my.id", remote = true)` → a banner containing "PRODUKSI" and the host; `notice` non-null → the notice text is displayed.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.ui.*"`
Expected: FAIL.

- [ ] **Step 3: Implement** the components

The status loop lives in `PlaygroundHost`: `LaunchedEffect(Unit) { while (isActive) { status = withContext(Dispatchers.IO) { repo.poll() }; delay(1_000) } }`. The badge and notice come from the in-memory `Resolved` kept by `SampleApplication` (Task 2). `MainActivity` now calls `PlaygroundHost()` inside `PlaygroundTheme` (light/dark, semantic colours for accepted / retry / error, a red banner colour for REMOTE). `ARTracker.trackNavController` stays wired exactly as in the current `SampleNavHost`. All strings come from `strings.xml`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Run on a device**

Run: `./gradlew :app:installDebug`, open the app.
Expected: four tabs, back button on detail screens, the status strip updates every second and shows the SINK badge, old screens still open, `grep -rn "httpbin" app/src/main` no longer matches `DiagnosticsScreen.kt`.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src
git commit -m "feat(sample): add playground theme, navigation shell, endpoint-aware status strip and action cards"
```

---

### Task 6: Inspector screen and Settings screen (with the backend confirmation flow)

**Files:**
- Create: `playground/settings/SettingsEditor.kt`, `playground/ui/InspectorScreen.kt`, `playground/ui/SettingsScreen.kt`
- Modify: `playground/ui/PlaygroundHost.kt` (wire the two tabs)
- Test: `playground/settings/SettingsEditorTest.kt`, `playground/ui/InspectorScreenTest.kt`

**Interfaces:**
- Consumes: `Inspector`, `InspectorFilter`, `ItemSource` (Task 3), `PlaygroundSettings`, `SettingsPreset`, `SettingsStore`, `EndpointGuard` (Task 1), `BackendGrantStore`, `Resolved` (Task 2), `ProcessRestarter` (Task 2), `ConfirmDialog` (Task 5).
- Produces:
```kotlin
class SettingsEditor(initial: PlaygroundSettings, private val buildEndpoint: String, private val realKeyPresent: Boolean) {
    var draft: PlaygroundSettings; private set
    val errors: List<String>                       // draft.validate() + "kunci API belum diatur" when BACKEND is chosen without a real key
    val canApply: Boolean                          // errors.isEmpty() && draft != initial
    val requiresBackendConfirmation: Boolean       // draft.endpointProfile == BACKEND && draft.isRemote(buildEndpoint)
    val confirmationHost: String                   // EndpointGuard.hostOf(buildEndpoint), shown in the dialog
    fun confirmationAccepted(typed: String): Boolean   // typed.trim() == "KIRIM" (case-sensitive, nothing else)
    fun update(transform: (PlaygroundSettings) -> PlaygroundSettings)
    fun applyPreset(preset: SettingsPreset)
}
```

- [ ] **Step 1: Write the failing tests**

`SettingsEditorTest`: `canApplyIsFalseWhenUnchanged`; `invalidDraftBlocksApply` (`flushIntervalMs = 10` → `canApply == false`, `errors.size == 1`); `presetReplacesTheDraftButNotTheProfile`; `sinkAndOfflineAndLocalNeverRequireConfirmation`; `backendWithARemoteBuildEndpointRequiresConfirmation`; `backendWithALocalBuildEndpointDoesNot`; `backendWithoutARealKeyCannotBeApplied`; `localWithARemoteHostCannotBeApplied`; `typedPhraseIsExactAndCaseSensitive` (`"KIRIM"` true, `" KIRIM "` true, `"kirim"`, `"KIRIM!"`, `"KIRIMKAN"`, `""` false).
`InspectorScreenTest` (Compose): feed two items into a local `EventRing` (one `SENT_PAYLOAD`, one `METADATA_ONLY`); both names are listed; the `METADATA_ONLY` row shows "nilai tidak ditampilkan"; the banner texts match the source ("payload yang dikirim SDK, setelah redaksi" vs "mode metadata"); typing "login" in the search field keeps only the matching row; "Hapus" empties the list; **no node anywhere offers to reveal raw values** (assert no node with text containing "tampilkan nilai" or "raw").

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.settings.SettingsEditorTest" --tests "id.art.tracker.playground.ui.InspectorScreenTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Settings screen: preset chips, endpoint profile selector (SINK default and labelled "Aman (lokal)"; BACKEND labelled "Produksi (berisiko)"), numeric fields with inline errors, "Kunci API: terpasang / belum diatur" (never the value), a text under BACKEND "Backend aktif hanya untuk satu kali start proses ini; peluncuran berikutnya kembali ke SINK". "Terapkan & restart": if `requiresBackendConfirmation`, show a type-to-confirm dialog that displays `confirmationHost` and the phrase `KIRIM`; only on `confirmationAccepted` call `BackendGrantStore.issue(now)`; then `SettingsStore.save`, then `ProcessRestarter.restart`. Inspector screen: list of `InspectedItem` (type chip, name, relative time), expandable attributes, type filter + search, clear, total-seen counter, "Segarkan" button and an automatic `ARTracker.flush()` every 2 s while the screen is visible (items appear only after the SDK sent them), and the banner for the active source.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Run on a device and apply a preset, then try the backend path against an unroutable remote host**

Run: install; Settings → preset "Privasi ketat" → "Terapkan & restart"; then temporarily set `artracker.endpoint=https://203.0.113.1` (TEST-NET-3, unroutable) and a dummy `artracker.key` in `local.properties`, rebuild, choose BACKEND, cancel the dialog, then confirm once.
Expected: after the preset the app restarts and the Inspector shows `session_start` from the sink; cancelling the dialog keeps the SINK badge; confirming starts one process with the red PRODUKSI banner and host `203.0.113.1`; killing and relaunching the app returns to the SINK badge with a notice (one-shot grant). Restore `local.properties` afterwards.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(sample): add inspector and settings screens with a typed backend confirmation"
```

---

### Task 7: Event lab and Identity lab

**Files:**
- Create: `playground/labs/LabGuard.kt`, `PropertyBuilder.kt`, `HostilePayloads.kt`, `Personas.kt`, `EventLab.kt`, `IdentityLab.kt`
- Modify: `playground/ui/Routes.kt`, `PlaygroundHost.kt` (register lab entries; a `LabRegistry` list drives the Lab tab)
- Test: `playground/labs/LabGuardTest.kt`, `PropertyBuilderTest.kt`, `HostilePayloadsTest.kt`

**Interfaces:**
- Consumes: `EndpointBadge` (Task 2), `ActionCard(disabledReason)` (Task 5).
- Produces:
```kotlin
enum class LabActionKind { NORMAL, FAKE_PII, LOAD, CRASH }
object LabGuard { fun disabledReason(kind: LabActionKind, remote: Boolean): String? }   // remote && (FAKE_PII || LOAD) -> reason; otherwise null
enum class PropertyType { STRING, NUMBER, BOOLEAN, NULL }
data class PropertyRow(val key: String, val type: PropertyType, val raw: String)
sealed interface BuildResult { data class Ok(val map: Map<String, Any?>) : BuildResult; data class Error(val row: Int, val message: String) : BuildResult }
object PropertyBuilder { fun build(rows: List<PropertyRow>): BuildResult }
data class NamedPayload(val name: String, val properties: Map<String, Any?>)
object HostilePayloads { val all: List<NamedPayload> }
data class Persona(val userId: String, val label: String, val traits: Map<String, Any?>)
object Personas { val all: List<Persona> }               // exactly 3
```
Event Lab calls `ARTracker.track`, `screen`, `log` (all five `LogLevel`s) and `captureException`; Identity Lab calls `identify` and `reset`. Hostile-payload and redaction cards use `LabActionKind.FAKE_PII` and the card's `disabledReason`.

- [ ] **Step 1: Write the failing tests**

- `LabGuardTest`: `(FAKE_PII, remote = true)` and `(LOAD, true)` return non-blank reasons; `(NORMAL, true)`, `(CRASH, true)`, and every kind with `remote = false` return `null`.
- `PropertyBuilderTest`: `numberRowParsesIntAndDouble` (`"42"` → `42L`, `"3.5"` → `3.5`); `badNumberReportsRowIndex` (`PropertyRow("a",NUMBER,"x")` at index 1 → `Error(1, ...)`); `blankKeyIsAnError`; `duplicateKeyIsAnError`; `nullRowProducesNullValue`; `booleanAcceptsOnlyTrueFalse`.
- `HostilePayloadsTest`: contains payloads named `deep_nesting_40`, `huge_string_10000`, `unicode_and_lone_surrogate`, `sensitive_keys`, `sensitive_values`; `deep_nesting_40` really nests 40 `Map` levels; `huge_string_10000` has a 10 000-char value; `sensitive_values` includes an email on the `.invalid` domain, the Luhn-valid test card `4111111111111111`, a JWT-shaped string and a `Bearer FAKE...` token (so the redactor is exercised) and **every sensitive value is recognisably fake** (the email domain ends in `.invalid`, the bearer/JWT contain `FAKE`); `sensitive_keys` has keys `password` and `token`.
- `Personas.all.size == 3`, all `userId`s distinct and non-blank.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.labs.*"`
Expected: FAIL.

- [ ] **Step 3: Implement** the pure helpers, then the two lab screens

Every action is an `ActionCard` returning the feedback text; after a `track` the feedback names the event only (never its values). Cards of kind `FAKE_PII` pass `LabGuard.disabledReason(FAKE_PII, badge.remote)` as `disabledReason`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat(sample): add event and identity labs with a remote-endpoint action guard"
```

---

### Task 8: Privacy lab

**Files:**
- Create: `playground/labs/PrivacyCases.kt`, `PrivacyLab.kt`, `RedactionForm.kt`
- Modify: `res/layout/` (add `lab_privacy_legacy.xml` for View cases), `sample/legacy/LegacyActivity.kt` stays; register the lab in `LabRegistry`
- Test: `playground/labs/PrivacyCasesTest.kt`

**Interfaces:**
- Consumes: `LabGuard`, `HostilePayloads` (Task 7), `LocalServersProvider.exportReplayChunks` (Tasks 2–3).
- Produces:
```kotlin
enum class Expectation { MASKED, VISIBLE, BLOCKED, LEAKS_KNOWN_LIMITATION }
data class PrivacyCase(val id: String, val title: String, val description: String,
                       val expectation: Expectation, val limitationRef: String? = null)
object PrivacyCases { val all: List<PrivacyCase> }
```
Cases (ids): `compose_password`, `compose_email_default`, `compose_numeric_default`, `compose_mask_modifier`, `compose_unmask_modifier`, `compose_unmask_inside_masked`, `compose_plain_text_email`, `view_password`, `view_numeric`, `view_tag_mask`, `view_autofill_hint_card`, `webview_blocked`, `imageview_placeholder`, `popup_window`, `dropdown_menu`, `max_all_text_toggle`. The lab also contains a `RedactionForm` that sends `track` with the `HostilePayloads` sensitive payloads, and an "Ekspor chunk replay" card (visible only when `LocalServersProvider.instance.sinkAvailable`).

- [ ] **Step 1: Write the failing test**

`PrivacyCasesTest`: ids unique and exactly the list above; every case has non-blank title and description; every `LEAKS_KNOWN_LIMITATION` case has a non-null `limitationRef` that is a substring of the README "Known limitations" section text (read `File("../README.md")`, the unit-test working directory is the module) so the lab can never claim a limitation the README does not list; `popup_window`, `compose_unmask_inside_masked` and `compose_plain_text_email` are `LEAKS_KNOWN_LIMITATION`; `webview_blocked` is `BLOCKED`; `compose_password` is `MASKED`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.labs.PrivacyCasesTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** `PrivacyCases`, then the lab UI

Each case renders the live widget, a badge with its `Expectation`, and the description; `LEAKS_KNOWN_LIMITATION` badges show the limitation text. The `WebView` case loads inline HTML (`loadData`), no network. A banner shows the effective `maskAllInputs` / `maskAllText` from the persisted settings and a shortcut to Settings (they need a restart). Login and Payment content from the old screens moves in here (`compose_password`, `compose_mask_modifier`). "Ekspor chunk replay" calls `exportReplayChunks(getExternalFilesDir(null))` and reports the file path (it contains masked chunks only).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Run on a device and validate with the replay viewer tooling (all local)**

Run: install; open the lab, type `hunter2-secret` in the password case, wait 6 s, press the export card; then `adb pull /sdcard/Android/data/id.art.tracker/files/replay-chunks.json /tmp/` and `node tools/replay-viewer/validate-chunks.mjs /tmp/replay-chunks.json --forbid hunter2-secret`.
Expected: the validator exits 0 (the file is the chunks the SDK actually sent, read from the sink; no backend, no JWT needed).

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(sample): add privacy lab with documented expectations and known limitations"
```

---

### Task 9: Replay lab

**Files:**
- Create: `playground/labs/TreeSpec.kt`, `ReplayLab.kt`, `GestureArena.kt`
- Modify: `LabRegistry`; the old `FeedScreen` content moves into the lab
- Test: `playground/labs/TreeSpecTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class TreeSpec(val depth: Int, val width: Int) { fun totalNodes(): Int }   // nodes of a complete tree: sum of width^i for i in 0..depth
object TreeSpecs { val DEEP: TreeSpec; val WIDE: TreeSpec; val NORMAL: TreeSpec }
@Composable fun GeneratedTree(spec: TreeSpec)                  // renders one Text per node inside nested Columns/Rows
```
`DEEP` exceeds the SDK depth limit of 50 (`TreeSpec(depth = 60, width = 1)`), `WIDE` exceeds 2000 nodes (`TreeSpec(depth = 3, width = 14)`), `NORMAL` stays far under both.

- [ ] **Step 1: Write the failing test**

`TreeSpecTest`: `TreeSpec(2,3).totalNodes() == 13`; `TreeSpecs.DEEP.depth > 50`; `TreeSpecs.WIDE.totalNodes() > 2000`; `TreeSpecs.NORMAL.totalNodes() < 200 && NORMAL.depth < 50`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.labs.TreeSpecTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** `TreeSpec`, `GeneratedTree`, then the lab sections

Sections: heavy `LazyColumn` (500 rows with varied content), horizontal `HorizontalPager`, `GestureArena` (tap, double tap, long press, drag, fling using `detectTapGestures` / `detectDragGestures`), animation (infinite `animateFloat` toggle, off by default), dialog, bottom sheet, dropdown menu, popup, the three generated trees behind buttons, and a "rotasi" instruction card. The Replay controls (`start`/`stop`) use `SdkControls` so the label always matches the real intent. A text "Perkiraan batas SDK: 2000 node, kedalaman 50" is shown next to the tree buttons, and a note that taps inside the dialog, bottom sheet and popup are in other windows and are **not** recorded as touches (SDK wraps the Activity window only).

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Run on a device**

Run: install; open the lab, tap `WIDE` and `DEEP`, scroll the list, then check `adb logcat -d -s ARTracker` and the app for ANR/crash.
Expected: no crash, no ANR; the Inspector (SINK) lists `replay_chunk` items.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(sample): add replay lab with gestures, overlays and oversized trees"
```

---

### Task 10: Network lab and Queue lab (local target server)

**Files:**
- Create: `playground/labs/HttpTargets.kt`, `BurstRunner.kt`, `BurstPolicy.kt`, `NetworkLab.kt`, `QueueLab.kt`; `app/src/debug/java/id/art/tracker/playground/local/HttpTargetServer.kt`
- Modify: `app/src/debug/.../LocalServersProvider.kt` (real `startTargets`, `targetBaseUrl`), `sample/SampleHttp.kt` (add `fun get(url: String, client: OkHttpClient = this.client)` only; behaviour unchanged), `SampleApplication` (start the target server in debug), `LabRegistry`
- Test: `playground/labs/BurstRunnerTest.kt`, `BurstPolicyTest.kt`, `HttpTargetsTest.kt`; `app/src/testDebug/java/id/art/tracker/playground/local/HttpTargetServerTest.kt`

**Interfaces:**
- Consumes: `LabGuard`, `LabActionKind` (Task 7), `SdkStatus` (Task 4), `EndpointGuard.TARGET_PORT` (Task 1), `LocalServers` (Task 2).
- Produces:
```kotlin
data class HttpTarget(val label: String, val path: String)
object HttpTargets { val all: List<HttpTarget>; fun urlOf(base: String, target: HttpTarget): String }   // paths: /status/200, /status/404, /status/500, /delay/20 (timeout), /delay/1 (slow)
enum class BurstDecision { ALLOW, CONFIRM, BLOCK }
object BurstPolicy { const val CONFIRM_ABOVE = 1_000; const val MAX = 10_000
    fun decide(count: Int, remote: Boolean): BurstDecision     // remote -> BLOCK; local and count > CONFIRM_ABOVE -> CONFIRM; else ALLOW
    fun clamp(count: Int): Int }
class BurstRunner(private val emit: (Int) -> Unit) {
    suspend fun run(count: Int, onProgress: (done: Int) -> Unit)    // cooperative: checks isActive every item, yields every 100
}
class HttpTargetServer(requestedPort: Int = EndpointGuard.TARGET_PORT) {     // src/debug, MockWebServer based, loopback only
    fun start(): Boolean; val baseUrl: String; fun shutdown()
}
```
Target-server behaviour: `GET /status/{code}` → that status with a short body; `GET /delay/{seconds}` → 200 with the headers delayed by that many seconds (`MockResponse.setHeadersDelay`); anything else → 404. It listens on `localhost` only. Its port differs from the sink's, so the SDK's interceptor **does** record these calls (it skips only the SDK endpoint's host and port).

- [ ] **Step 1: Write the failing tests**

- `BurstPolicyTest`: `decide(5000, remote = true) == BLOCK`; `decide(1_000, false) == ALLOW`, `decide(1_001, false) == CONFIRM`; `decide(50, true) == BLOCK` (any load on a remote endpoint is blocked, not merely confirmed); `clamp(-5) == 0`, `clamp(20_000) == 10_000`.
- `BurstRunnerTest` (`runBlocking`): emits exactly `N` values `0..N-1` in order; `onProgress` is monotonic and ends at `N`; cancelling the job after 50 emissions stops within one yield window (`emitted < N`).
- `HttpTargetsTest`: the five labels exist; `urlOf("http://localhost:38458", target)` produces `http://localhost:38458/status/404` etc.; the timeout target's path is `/delay/20`; **no target or constant in the app source mentions `httpbin`** (read `app/src/main` recursively in the test and assert no match).
- `HttpTargetServerTest` (`src/testDebug`, port `0`, plain `HttpURLConnection`): `/status/404` → 404; `/status/500` → 500; `/delay/1` → 200 and the response took ≥ 1 000 ms; an unknown path → 404; the bound address `isLoopbackAddress`; `start()` returns false (no throw) when the port is busy.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.labs.Burst*" --tests "id.art.tracker.playground.labs.HttpTargetsTest" --tests "id.art.tracker.playground.local.HttpTargetServerTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** the helpers and the two labs

Network lab: one `ActionCard` per `HttpTarget`; the base URL comes from `LocalServersProvider.instance.targetBaseUrl` (in a release build the lab shows "hanya tersedia di build debug"); last result and the interceptor's expectation ("404 → level warn"); the effective `NetworkConfig` (`ignoreHosts`, `networkSampleRate`, `captureOnlyErrors`) read from settings with a shortcut to Settings. Queue lab: burst buttons 100 / 1 000 / 5 000 (the SDK queue evicts at 5 000 rows) using `BurstRunner { i -> ARTracker.track("burst_event", mapOf("i" to i)) }` on `Dispatchers.Default`, with kind `LOAD` (disabled on a remote endpoint via `LabGuard`) and a confirmation dialog when `BurstPolicy.decide` is `CONFIRM`; live `pending` and `lastSend` from `SdkStatus`; a Flush card; a hint that the SINK accepts everything, so to watch `pending` grow the profile must be OFFLINE.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Run on a device with the OFFLINE profile**

Run: Settings → profile OFFLINE → apply; Queue lab → burst 1 000; then Network lab → 404 target.
Expected: `pending` climbs, `lastSend` becomes `network_error`, UI stays responsive; the Network lab 404 shows "HTTP 404" with no external network (airplane mode with Wi-Fi off works as long as loopback is up).

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(sample): add network and queue labs on a local MockWebServer target"
```

---

### Task 11: Crash lab and Session lab

**Files:**
- Create: `playground/labs/CrashNote.kt`, `FingerprintCounter.kt`, `CrashLab.kt`, `SessionLab.kt`
- Modify: `LabRegistry`, `playground/ui/PlaygroundHost.kt` (show the pending crash note once after relaunch, and feed `SessionHistory`)
- Test: `playground/labs/CrashNoteTest.kt`, `FingerprintCounterTest.kt`

**Interfaces:**
- Consumes: `InspectedItem` (Task 3), `SessionHistory` (Task 4), `LabActionKind` (Task 7).
- Produces:
```kotlin
interface NoteStore { var pending: String? }                       // SharedPreferences-backed implementation in the same file
class CrashNote(private val store: NoteStore, private val nowMs: () -> Long = System::currentTimeMillis) {
    fun recordBeforeCrash(kind: String)                            // synchronous commit(), called right before throwing
    fun consume(): String?                                         // returns the note once, then null
}
object FingerprintCounter { fun countExceptions(items: List<InspectedItem>): Map<String, Int> }   // name == "exception", grouped by attributes["fingerprint"]
```

- [ ] **Step 1: Write the failing tests**

- `CrashNoteTest`: `consumeReturnsNoteOnceThenNull`; the note contains the `kind` and a timestamp from the injected clock; a second `recordBeforeCrash` overwrites the first.
- `FingerprintCounterTest`: 15 items named `exception` with fingerprint `f1`, 3 with `f2`, 5 items named `track` → `{f1=15, f2=3}` (the lab compares the count the Inspector received with the number sent, to show the SDK's 10-per-fingerprint dedupe).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.labs.CrashNoteTest" --tests "id.art.tracker.playground.labs.FingerprintCounterTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

Crash lab (all destructive actions use `ActionCard(destructive = true, confirmText = ...)`): crash on main thread, crash on a background thread, ANR 10 s, and "kirim 15 error identik" (`repeat(15) { ARTracker.captureException(IllegalStateException("dedupe-demo")) }` thrown from one call site so the fingerprint is identical) followed by the count from `FingerprintCounter`, which only works on the SINK profile (the card explains that on other profiles the Inspector holds metadata only). Before every crash, `CrashNote.recordBeforeCrash(kind)`; `PlaygroundHost` shows a card with `consume()` on the next launch: "Crash terakhir ... dikirim saat launch ini; cek Inspector setelah flush". Crash items bypass `beforeSend` and are written to a file, so on SINK they appear in the Inspector only after the relaunch and the next flush. An offline-crash guide lists the three steps of `docs/e2e-manual-test.md` §4 (use the OFFLINE profile). Session lab: current session id, effective `sessionTimeoutMs`, a countdown "tekan Home, tunggu N detik, kembali" computed from it, `reset()` shortcut, and the `SessionHistory` list.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Run on a device**

Run: preset `DEBUG_FAST` → restart; Session lab: Home, wait 12 s, return.
Expected: session id changes (history shows two ids); Inspector shows `session_end`/`session_start`. Then "15 error identik": the card reports 10 received.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(sample): add crash and session labs"
```

---

### Task 12: Scenario runner and synthetic touch

**Files:**
- Create: `playground/scenario/Scenario.kt`, `ScenarioRunner.kt`, `TapRegistry.kt`, `SyntheticTouch.kt`, `SyntheticTouchPlanner.kt`, `ActivityHolder.kt`, `ReplayReadiness.kt`, `ScenarioFields.kt`
- Modify: `sample/SampleApplication.kt` (register `ActivityHolder` as `ActivityLifecycleCallbacks` in the main process)
- Test: `playground/scenario/ScenarioRunnerTest.kt`, `TapRegistryTest.kt`, `SyntheticTouchPlannerTest.kt`, `ReplayReadinessTest.kt`, `SyntheticTouchTest.kt` (Robolectric, one SDK level)

**Interfaces:**
- Produces:
```kotlin
enum class Capability { EVENTS, IDENTITY, SCREENS, LOGS, ERRORS, HTTP, REPLAY, MASKING, REDACTION, OFFLINE, SESSION, QUEUE }
class Step(val label: String, val pauseAfterMs: Long = 600, val run: suspend ScenarioContext.() -> Unit)
class Scenario(val id: String, val title: String, val description: String, val proves: Set<Capability>, val steps: List<Step>)
interface ScenarioContext {
    suspend fun navigate(route: String)
    suspend fun tap(targetId: String)                  // fails the step if the target is missing, outside the activity window, or no resumed activity exists
    suspend fun type(fieldId: String, text: String)
    suspend fun awaitUser(prompt: String)              // suspends until the user presses "Lanjut"
    suspend fun awaitReplayFrame()                     // suspends ReplayReadiness.warmupMs(fps) so a base snapshot of the current session exists
    fun preflight(): List<String>                      // warnings, e.g. replay not recording or recorder not installed
    fun note(message: String)
}
sealed interface RunState { object Idle; data class Running(val index: Int, val total: Int, val label: String);
    data class Done(val warnings: List<String>); data class Failed(val index: Int, val message: String); object Cancelled }
class ScenarioRunner(private val pause: suspend (Long) -> Unit = { delay(it) }) {
    suspend fun run(scenario: Scenario, ctx: ScenarioContext, onState: (RunState) -> Unit)    // calls ctx.preflight() once before the first step
}
data class TapTarget(val boundsInWindow: Rect, val inActivityWindow: Boolean)
class TapRegistry { fun register(id: String, target: TapTarget); fun unregister(id: String); fun find(id: String): TapTarget?
    companion object { val Shared: TapRegistry } }
fun Modifier.tapTarget(id: String): Modifier           // onGloballyPositioned; inActivityWindow = (LocalView.rootView === current activity's decorView); unregisters on dispose
object ReplayReadiness { fun warmupMs(fps: Double): Long }       // 2 × frame period + 500 ms, fps clamped to 0.5..4.0 like the SDK, NaN -> 1.0
object ActivityHolder : Application.ActivityLifecycleCallbacks { val resumed: Activity? }   // weak reference to the resumed Activity
data class TouchSpec(val action: Int, val screenX: Float, val screenY: Float, val downTime: Long, val eventTime: Long)
object SyntheticTouchPlanner {
    const val TAP_MS = 60L
    fun tap(centerInWindow: PointF, windowOriginOnScreen: PointF, startMs: Long): List<TouchSpec>
    fun swipe(from: PointF, to: PointF, windowOriginOnScreen: PointF, startMs: Long, durationMs: Long = 300, stepMs: Long = 16): List<TouchSpec>
}
object SyntheticTouch {
    suspend fun tap(activity: Activity, centerInWindow: PointF)                 // main thread
    suspend fun swipe(activity: Activity, from: PointF, to: PointF)
    fun isRecorderInstalled(activity: Activity): Boolean                         // advisory only: window.callback's class simple name == "TouchRecorder"
}
```
**Mechanism (validated against the SDK):** each `TouchSpec` becomes a `MotionEvent` built with raw screen coordinates (window coordinates + the decor view's `getLocationOnScreen`), `offsetLocation(-origin)` so `x` is window-relative and `rawX` is the screen position exactly like a framework-delivered event, `source = SOURCE_TOUCHSCREEN`, `eventTime` from `SystemClock.uptimeMillis()`, dispatched with `activity.window.decorView.dispatchTouchEvent(event)` (DecorView forwards to `window.callback`, which is the SDK's `TouchRecorder` while replay is recording) and recycled after dispatch.

- [ ] **Step 1: Write the failing tests**

- `ScenarioRunnerTest` (`runBlocking`, `pause = {}`): all steps run in order and states are `Running(0..n-1)` then `Done(warnings)`; `preflight()` is called exactly once before step 0 and its warnings end up in `Done`; a step that throws yields `Failed(index, message)` and later steps do not run; cancelling the coroutine yields `Cancelled` (via `onState`) and does not swallow the `CancellationException`; `pauseAfterMs` values are passed to `pause` in order.
- `TapRegistryTest`: register then `find` returns the target; `unregister` removes; registering the same id twice keeps the latest; `find` of an unknown id is `null`.
- `SyntheticTouchPlannerTest`: `tap(center=(100,200), origin=(0,48), start=1_000)` → exactly two specs, DOWN then UP, both at screen `(100, 248)`, `downTime == 1_000` for both, `eventTime` 1 000 and 1 060; `swipe(from=(10,10), to=(210,10), origin=(0,0), start=0, 300, 16)` → first spec DOWN at `(10,10)`, last spec UP at `(210,10)`, all middle specs MOVE, `eventTime` strictly increasing, at least 5 MOVEs, last `eventTime - first == 300`.
- `ReplayReadinessTest`: `warmupMs(1.0) == 2_500`, `warmupMs(0.5) == 4_500`, `warmupMs(4.0) == 1_000`, `warmupMs(100.0) == 1_000` (clamped), `warmupMs(Double.NaN) == 2_500`.
- `SyntheticTouchTest` (Robolectric, `ActivityScenario` of a plain `ComponentActivity` with a Compose `Box(Modifier.tapTarget("t").clickable { count++ })`): install a capturing `Window.Callback` wrapper (delegating to the previous callback) on `activity.window`; `SyntheticTouch.tap(activity, centerOfT)` → the wrapper receives DOWN then UP in that order, `getRawX/getRawY` equal the planned screen coordinates, `getX/getY` equal the window coordinates, `source == SOURCE_TOUCHSCREEN`, `eventTime(UP) - eventTime(DOWN) == 60`, and the click counter becomes 1 (the event travelled through the framework path into the view tree); `isRecorderInstalled` is `false` for the plain activity (no SDK recorder).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.scenario.*"`
Expected: FAIL.

- [ ] **Step 3: Implement**

`SyntheticTouch` runs on `withContext(Dispatchers.Main)`, sleeps between specs with `delay`, and always recycles events in `finally`. `Modifier.tapTarget` stores `boundsInWindow()` and `inActivityWindow` computed from `LocalView.current.rootView` compared with `ActivityHolder.resumed?.window?.decorView`. `ScenarioFields` is a registry `id → MutableState<String>` filled by a `ScenarioTextField` composable so `type()` can set text. A scenario host (activity-scoped holder) owns the coroutine so rotation does not restart it and leaving the screen cancels it. The context implementation's `preflight()` returns a warning when: `SdkControls.replayOn` is false, the resumed activity's window callback is not a `TouchRecorder` (`isRecorderInstalled`, advisory), or there is no resumed activity. It also returns the standing warning "Sentuhan di dialog/popup tidak direkam" for scenarios that use `Capability.REPLAY`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.scenario.*"`
Expected: PASS.

- [ ] **Step 5: Verify on a device that a synthetic tap is both clicked and recorded**

Run: install; Replay lab → `GestureArena` with a temporary card calling `SyntheticTouch.tap` on its own target; wait 4 s; press "Ekspor chunk replay" (Task 8 card) and `adb pull /sdcard/Android/data/id.art.tracker/files/replay-chunks.json /tmp/`; then `node -e 'const c=require("/tmp/replay-chunks.json");const ev=c.flatMap(x=>x.events);console.log(ev.filter(e=>e.type===3&&e.data.source===2&&e.data.type===7).length, ev.filter(e=>e.type===3&&e.data.source===2&&e.data.type===9).length)'`
Expected: both counts ≥ 1 and the card's click counter incremented; with replay stopped first (`Stop replay`), the click counter still increments but both counts stay 0 and the scenario preflight lists "replay tidak merekam".

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(sample): add scenario runner and a synthetic touch driver validated against the SDK touch path"
```

---

### Task 13: Mock shop screens and the six scenarios

**Files:**
- Create: `playground/scenario/shop/ShopHome.kt`, `ProductList.kt`, `ProductDetail.kt`, `Cart.kt`, `Checkout.kt`, `PaymentResult.kt`, `playground/scenario/definitions/Scenarios.kt` (+ one file per scenario), `playground/ui/ScenarioScreen.kt`
- Modify: `PlaygroundHost.kt` (Skenario tab)
- Test: `playground/scenario/definitions/ScenariosTest.kt`

**Interfaces:**
- Consumes: Task 12 types. Shop composables use `Modifier.tapTarget(...)` and `ScenarioTextField`. HTTP steps use `HttpTargets` (Task 10); in a release build those steps are skipped with a `note`.
- Produces: `object Scenarios { val all: List<Scenario> }` with ids `ecommerce_checkout`, `onboarding_form`, `frustrated_taps`, `logout_switch_account`, `offline_user`, `long_session`.

- [ ] **Step 1: Write the failing test**

`ScenariosTest` (run every scenario against a recording fake `ScenarioContext` with `pause = {}`): ids are exactly the six above and unique; each has `>= 3` steps, non-blank step labels, and a non-empty `proves`; `Scenarios.all.flatMap { it.proves }.toSet()` contains `EVENTS, IDENTITY, SCREENS, ERRORS, HTTP, REPLAY, MASKING, OFFLINE, SESSION`; `offline_user` calls `awaitUser` at least twice; **every scenario that taps (`frustrated_taps`, `ecommerce_checkout`, `onboarding_form`) calls `awaitReplayFrame()` before its first `tap`**, and `logout_switch_account` calls `awaitReplayFrame()` right after its `reset()` (a new session has no base snapshot until the next frame, so an earlier tap would be dropped silently); no scenario step calls anything named crash/ANR on the fake (destructive actions stay in the Crash lab); no step issues a load/burst (`LabActionKind.LOAD`); the `frustrated_taps` description contains the sentence "SDK tidak mendeteksi rage click" (honest scope, per the report).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "id.art.tracker.playground.scenario.definitions.*"`
Expected: FAIL.

- [ ] **Step 3: Implement** the screens and scenarios

`ecommerce_checkout`: Home → search → product list → detail → add to cart → checkout (types an obviously fake email on `.invalid` and the test card number, both masked) → pay → result; uses `track`, `screen` and a failing HTTP call to the local `/status/404` target. `onboarding_form`: three form steps, identify at the end. `frustrated_taps`: 8 fast taps on a non-responsive button, then `captureException`. `logout_switch_account`: identify persona A → track → `reset()` → `awaitReplayFrame()` → identify persona B. `offline_user`: `awaitUser("Aktifkan mode pesawat, lalu tekan Lanjut")`, activity, `awaitUser("Matikan mode pesawat")`, flush. `long_session`: `awaitUser` for background and return. `ScenarioScreen` lists scenarios as cards with `proves` chips, a Run / Stop button, step progress, the `Done` warnings, and a "Lanjut" button when the runner is awaiting the user.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Run `ecommerce_checkout` on a device**

Run: install; Skenario → run it; then Inspector, and export chunks as in Task 12 Step 5 followed by `node tools/replay-viewer/validate-chunks.mjs /tmp/replay-chunks.json --forbid 4111111111111111`.
Expected: the app navigates and taps by itself; Inspector (SINK) lists `screen_view`, `track`, `http_request` (warn, URL host `localhost:38458`) and `replay_chunk` items; the validator reports no problems and finds touch events.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(sample): add mock shop and six scripted scenarios"
```

---

### Task 14: Instrumented end-to-end tests against MockWebServer

**Files:**
- Modify: `app/build.gradle.kts` (`testInstrumentationRunner = "id.art.tracker.PlaygroundTestRunner"`; `androidTestImplementation(libs.mockwebserver)`; `androidx-junit` is already present)
- Create: `app/src/androidTest/java/id/art/tracker/PlaygroundTestRunner.kt`, `TestPlaygroundApplication.kt`, `MockIngest.kt`, `IngestFlowTest.kt`, `ScenarioFlowTest.kt`, `SafetyTest.kt`
- Keep: `ExampleInstrumentedTest.kt`

**Interfaces:**
- Produces:
```kotlin
class PlaygroundTestRunner : AndroidJUnitRunner()      // newApplication → TestPlaygroundApplication
class TestPlaygroundApplication : Application()        // starts MockIngest and HttpTargetServer (port 0, loopback), then SampleApp.initTracker(this, ingest.url, EndpointGuard.DUMMY_KEY, debug = true, settings = DEFAULT.copy(flushIntervalMs = 1_000), beforeSend = null)
class MockIngest { val url: String
    fun enqueue(status: Int, headers: Map<String, String> = emptyMap())      // scripted response, FIFO; default 202 {"status":"accepted","queued":n}
    fun takeBodies(timeoutMs: Long): List<String>                            // gunzipped JSON envelopes, in order
    fun clear() }
```
The SDK is a process singleton and init-once, so one shared server and one shared init serve all tests; each test calls `MockIngest.clear()` in `@Before`. The 401 kill-switch is deliberately **not** automated (it persists for one hour in process-wide prefs and would poison later tests); it stays in the manual checklist. The test application never calls `Bootstrap`, so no test can reach a remote endpoint.

- [ ] **Step 1: Write the failing tests**

`IngestFlowTest`:
- `trackReachesIngestAsGzipBatch`: `ARTracker.track("e2e_event", mapOf("k" to "v")); ARTracker.flush()` → a gunzipped body contains `"e2e_event"`, the request path is `/api/v1/ingest`, header `X-Artracker-Key` equals `EndpointGuard.DUMMY_KEY`, `Content-Encoding` is `gzip`.
- `lastSendResultBecomesAccepted`: within 10 s `ARTracker.diagnostics().lastSendResult == "accepted"` (called off the main thread).
- `optOutDropsQueueAndSendsNothing`: `optOut(true)`; track 5 events; flush; no request within 3 s; `diagnostics().pendingItems == 0`; `optOut(false)` in `@After`.
- `retryAfterIsHonouredThenAccepted`: `enqueue(503, mapOf("Retry-After" to "1"))` then `202`; track + flush → within 30 s a second request arrives and `lastSendResult == "accepted"`.
- `redactionHoldsOnTheWire` (Review Focus 3): `track` with properties `password = "hunter2-secret"`, `email = "qa@example.invalid"`, `card = "4111111111111111"`, `note = "Bearer FAKE.token.value"`, a JWT-shaped fake, and an event **name** containing an email → in every gunzipped body none of those raw strings appears; masked forms appear (`**** **** **** 1111`).
- `callsToTheIngestEndpointAreNotRecorded`: an OkHttp call (`SampleHttp.client`) to `MockIngest.url` produces no `http_request` item; a call to `HttpTargetServer` `/status/404` produces one with level `warn`, `status 404` and a scrubbed URL (different port, so not skipped).
`ScenarioFlowTest` (`ActivityScenario.launch(MainActivity::class.java)` and the Compose test rule):
- `ecommerceScenarioProducesExpectedItems`: run `ecommerce_checkout` to `Done`; flush; collected items include a `screen_view` for each shop screen, an `http_request` with level `warn`, and at least one `replay_chunk`.
- `typedSecretsNeverAppearInReplayChunks`: during the scenario the typed card number `4111111111111111` and the fake email appear in **no** body of type `replay_chunk`; assert at least one chunk exists so the check is not vacuous.
- `syntheticTapIsRecordedInReplayChunk` (Review Focus 4): `ARTracker.startReplay()`, wait `ReplayReadiness.warmupMs(1.0)`, `SyntheticTouch.tap` on a registered target whose click handler calls `ARTracker.track("tap_target_clicked")`; `ARTracker.stopReplay(); ARTracker.flush()` → a body contains `tap_target_clicked` **and** a `replay_chunk` whose events contain an incremental `source 2, type 7` followed by `type 9` with x, y within ±2 dp of `center / density`.
- `tapWithoutReplayStillClicksButRecordsNoTouchAndPreflightWarns`: `stopReplay()`, tap the same target → the click event arrives, no touch events appear in any chunk, and `ScenarioContext.preflight()` contains "replay tidak merekam".
- `tapOutsideTheActivityWindowFailsTheStep`: a target registered from inside a `Dialog` has `inActivityWindow == false`; `ctx.tap` yields `Failed` with a message naming the target.
`SafetyTest` (Review Focus 2 and 1):
- `sdkPrivateFilesMatchTheNamesTheWiperDeletes`: after the test application initialised the SDK and an item was persisted (make the ingest return `503` once so the item stays queued), `context.getDatabasePath("artracker.db").exists()` and `File(context.filesDir, "artracker").isDirectory` are true; then `QueueWiper.wipe(databasesDir, filesDir)` run on **copies** of those paths in a temp directory removes the same names (guards against the SDK renaming its files).
- `testApplicationNeverUsesARemoteEndpoint`: `EndpointGuard.classify(MockIngest.url) == SAFE_LOCAL` and so is the target server's base URL.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: FAIL/compile errors (runner and helpers missing).

- [ ] **Step 3: Implement** the runner, application and `MockIngest`

`MockIngest` starts `MockWebServer` bound to `InetAddress.getByName("localhost")` (cleartext to `localhost` is allowed by `app/src/debug/res/xml/network_security_config.xml`), replies `202` by default and exposes gunzipped bodies through a `Dispatcher` that records them.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: PASS on an emulator API 30+ (all tests in `IngestFlowTest`, `ScenarioFlowTest`, `SafetyTest`, `ExampleInstrumentedTest`).

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/androidTest
git commit -m "test(sample): add instrumented end-to-end tests against MockWebServer"
```

---

### Task 15: Remove superseded screens, update docs, final verification

**Files:**
- Delete: `sample/screens/LoginScreen.kt`, `PaymentScreen.kt`, `FeedScreen.kt`, `DiagnosticsScreen.kt` (their content now lives in the labs; the "Layar lama" entries from Task 5 go too)
- Modify: `README.md` (add a "Demo app" section: tabs, labs, scenarios, the four endpoint profiles, the typed backend confirmation, the debug-only sink, how to run the tests), `docs/e2e-manual-test.md` (map each section to a lab/scenario, state that the default is the loopback sink and that production needs the confirmation, add the 401 kill-switch and offline-crash steps that stay manual), `docs/demo-baseline.md` (append the final results)
- Test: none new; this task is verification.

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Delete the old screens and fix imports**

Run: `./gradlew :app:assembleDebug && grep -rn "sample.screens" app/src`
Expected: `BUILD SUCCESSFUL`; the grep prints nothing.

- [ ] **Step 2: Update the docs**

The README section lists each lab, the six scenarios with their `proves`, the four endpoint profiles, and states plainly what is still manual (401 kill-switch, offline crash relaunch, replay playback in the dashboard) and the known limits of the Inspector (shows what was sent; crash items appear after relaunch; non-SINK profiles show metadata only).

- [ ] **Step 3: Full verification**

Run: `./gradlew :artracker-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease && ./gradlew :app:connectedDebugAndroidTest`
Expected: all green; SDK test count unchanged from the baseline in `docs/demo-baseline.md`.

- [ ] **Step 4: Safety greps**

Run: `grep -rn "httpbin" app/src docs README.md; ./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -c mockwebserver; grep -rn "ARTRACKER_ENDPOINT\|ARTRACKER_KEY" app/src/main app/src/debug | grep -v "Bootstrap\|SampleApplication\|build.gradle"`
Expected: first command prints nothing; the count is `0`; the last command prints nothing (the real endpoint and key are read only by `SampleApplication` and `Bootstrap`).

- [ ] **Step 5: Confirm the SDK is untouched**

Run: `git diff feature/android-sdk --stat -- artracker-sdk`
Expected: no output.

- [ ] **Step 6: Manual pass on a device**

Run the sections of `docs/e2e-manual-test.md` that remain manual (against SINK or OFFLINE), plus the replay-viewer playback of one `ecommerce_checkout` session in `tools/replay-viewer/index.html` using the exported file.
Expected: results recorded in `docs/demo-baseline.md`; the playback check, which was never verified before, is reported as pass or fail honestly. No step in this task uses the production endpoint.

- [ ] **Step 7: Commit**

```bash
git add -A app docs README.md
git commit -m "docs(sample): document the playground and retire the superseded demo screens"
```

---

## Residual Risks (not removed by this plan)

1. **Inspector latency and coverage.** It shows what the SDK *sent*, so items appear only after a flush (the screen flushes every 2 s while open); crash items appear after the relaunch; on `OFFLINE`, `LOCAL` and `BACKEND` only metadata is available.
2. **Fixed local ports.** `38457` (sink) and `38458` (targets) can be busy; the app then falls back to `OFFLINE` with a notice, and the 15-error dedupe card and Inspector are unavailable until the port is free.
3. **Synthetic touch depends on SDK internals that are not API:** the recorder is installed only while recording and only for the Activity window, a base snapshot must exist, and `isRecorderInstalled` compares a class name (debug builds only; R8 would rename it). It is advisory, never a gate. Compose 1.7's handling of directly dispatched `MotionEvent`s is proven only by Task 12 Step 5 and Task 14.
4. **`QueueWiper` deletes SDK files by their documented names** (`artracker.db*`, `filesDir/artracker/`). `SafetyTest.sdkPrivateFilesMatchTheNamesTheWiperDeletes` detects a rename, but a future SDK change would turn the wipe into a silent no-op until that test runs.
5. **A confirmed BACKEND session is real production traffic** (including crash and ANR items from the Crash lab and a saved transport config that the WorkManager worker may use after the process dies). The grant limits it to one process start; it cannot prevent what the user confirmed.
6. **`LOCAL` sends the real key to a developer backend**; the host must classify as `SAFE_LOCAL`, but any process listening on `10.0.2.2` receives it.
7. **Release builds of the demo have no sink, target server or export**; the playground degrades to `OFFLINE` there.

## Self-Review

**Spec coverage** (against the report and the review of 2026-11-07):
- Inspector data safety (R1) → Tasks 3 and 6, Global Constraints "Inspector data safety", Review Focus 3, `redactionHoldsOnTheWire`.
- Endpoint safety (R2, R3, R4) → Tasks 1, 2, 6, 7, 10; Review Focus 1 and 2; `SafetyTest`.
- Synthetic touch validated against the SDK (R5) → "SDK facts", Task 12 mechanism and tests, Task 13 `awaitReplayFrame` rule, Task 14 touch tests; Review Focus 4.
- MockWebServer for all local HTTP (R6) → Tasks 2, 3, 10, 14; `httpbin` greps in Tasks 10 and 15.
- Baseline safety (R7) → Task 0 Steps 3 and 6.
- Preserved from revision 1: the 16 tasks and their order, the three cut lines, every technical constraint (SDK untouched, `diagnostics()` off main, init-once, multi-process, Compose BOM pin, config validation, existing `SampleAppTest`), and every acceptance step; changes only add, tighten or replace the specific mechanism named in the revision table. Two acceptance items are tightened rather than kept: burst on a remote endpoint is now blocked (was: confirmed above 100), and the default endpoint is SINK (was: BACKEND).
- Report §4 gaps: A–D mapped as before; D-12 (httpbin dependency) is now closed by the local target server.

**Step scan:** every code step names its signature and file; bodies are left to the implementer except where the plan fixes exact behaviour (`validate()` rules, `classify` parsing, the touch mechanism, the sink's HTTP behaviour).

**Type consistency:** `EndpointProfile`, `EndpointGuard`, `EndpointBadge`, `Resolved`, `LocalServers`, `InspectedItem`, `ItemSource`, `EventRing`, `ActionCard(disabledReason)`, `LabGuard`, `BurstDecision`, `ScenarioContext` (with `awaitReplayFrame` and `preflight`), `RunState.Done(warnings)` are defined once and used with the same names later; `SampleApp.initTracker/config` gain trailing defaulted parameters only; `PlaygroundSettings.toConfig` takes the already-resolved endpoint (Task 1), which `SampleApp.config` and `Bootstrap` rely on (Task 2).

**Review Focus:** all five lines have an owning test (1: Tasks 1, 2, 6; 2: Tasks 2, 14; 3: Tasks 3, 14; 4: Tasks 12, 13, 14; 5: Tasks 1, 2, 3).

**Proportion:** tests are specified by name and assertion values, not written out; bodies are not included.
