# App Start Profiling — Android SDK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Android SDK measures cold, warm and hot starts with a phase breakdown, sends them as `perf` items named `app_start`, and on a sampled fraction of cold starts samples the main-thread stack every 10 ms and uploads the profile to `POST /api/v1/app-start/profiles`.

**Architecture:**
- `lifecycle/` keeps the start trackers. `AppStartTracker` (cold) gains phase timestamps and drop reasons. A new `WarmHotStartTracker` measures warm and hot starts. Pure phase math lives in `StartPhases.kt`.
- A new package `id.art.tracker.appstart` holds the profiler: the sampler thread, the stack dedup buffer, the profile JSON, the file store and the uploader.
- `ARTrackerCore` wires everything behind injection seams, like `appStartFactory` and `perfFactory` today.

**Tech Stack:** Kotlin 2.0.21, minSdk 24, compileSdk 35, `explicitApi()`, coroutines, JUnit4 + Robolectric 4.14.1 (`@Config(sdk = [34])`), MockWebServer, hand-written fakes in `src/test/.../testutil`.

**Spec:** `artracker/docs/superpowers/specs/2026-11-19-app-start-profiling-design.md`. Start types and phases: §4.1. Wire contract: §5.1–5.2. Profiler behaviour: §5.3.

**Repo:** this plan is stored in `artracker`, but **every task's code, tests and commits go to `~/AndroidStudioProjects/github/artracker-android-sdk`**, on a branch `feat/app-start-profiling` created from `main`. Paths are relative to:
- SDK code: `artracker-sdk/src/main/java/id/art/tracker/`
- Tests: `artracker-sdk/src/test/java/id/art/tracker/`

The test command for one class is `./gradlew :artracker-sdk:testDebugUnitTest --tests '<fqcn>'`. The full gate is `./gradlew :artracker-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`, the same as CI.

## Global Constraints

**Item and phases**
- Item: `type "perf"`, `name "app_start"`. Attributes: `v: 1`, `platform: "android"`, `start_id`, `type` (`cold`, `warm` or `hot`), `duration_ms`, `phases: [{name, ms}]`, `first_screen`, `trampoline`, `profiled`, `build`, `device: {model, os_version, ram_mb, cores}`. A missing value is **omitted**, never 0.
- The item timestamp is the **wall-clock time of the start anchor**, not the time of emission.
- Phases are contiguous and in order. Their sum equals `duration_ms` exactly; the backend tolerates ±5 ms.
- Cold phases: `process_to_init`, `application_on_create`, `activity_create`, `first_draw`. On API 35+, `process_to_init` is replaced by `bind_application`, `content_providers` and `app_on_create_pre_init`.
- Warm phases: `activity_create`, `first_draw`. Hot phases: `activity_start`, `first_draw`.

**Start types and guards**
- Cold max duration is 60 000 ms (unchanged). Warm and hot max is 10 000 ms. A start that goes to the background before its first frame is dropped.
- A process launched in the background (not foreground at init), or one whose first Activity comes more than `MAX_FIRST_CREATE_DELAY_MS` (10 000 ms) after init, is **never cold**. Its first Activity is a **warm** start.
- The old `event` named `app_start` is **no longer emitted**.

**Config**
- `AppStartConfig(enabled = true, profiling = false, profileSampleRate = 0.1, inAppPackages = emptySet())`. `profileSampleRate` must be in 0.0..1.0.
- `enabled = false` means no tracker, no profiler and no callbacks are registered.

**Profiler**
- Only cold starts are profiled. Whether to profile is decided once per process at init: `appStart.enabled && profiling && random < profileSampleRate`.
- It samples `Looper.getMainLooper().thread.stackTrace` on the thread `artracker-startup-sampler` every 10 ms.
- It stops at the first frame, on background, at 10 000 ms, or when the SDK is disabled. A start that is not reported discards its profile.
- Backoff: when the running mean cost per sample exceeds 1 ms, the interval doubles (10 → 20 → 40 ms, capped at 40). The payload's `interval_ms` is the final interval.
- Caps: at most 1000 samples; at most 128 frames per stack, dropping the frames **nearest the root**. Stacks are root-first in the payload. Sample `t` is ms since process start (`t0: "process_start"`).

**Profile payload and upload**
- Payload: `{sdk, context: {app_version, build, environment}, profile: {v: 1, start_id, platform: "android", app_packages, interval_ms, t0, frames: [{c, m, l?, f?}], stacks, samples: [{t, s}]}}`.
- `app_packages` is `[app.packageName] + inAppPackages`, deduplicated.
- Upload: `POST {endpoint}/api/v1/app-start/profiles`, gzip, the same headers as ingest.
- Store: at most 3 pending files (the oldest is evicted); a file older than 3 days is deleted; a file is deleted after 5 failed attempts. 413 or any other drop deletes the file. 401/403 stops the upload and leaves the file.
- Serialisation, gzip and file I/O never run on the main thread.

## Review Focus

1. **Init called late** (Activities already alive when `ARTracker.init` runs). No cold start is reported (existing behaviour), and **no warm or hot start may be reported until the app has been in the background once**. The test is in Task 4.
2. **Splash/trampoline cold start with profiling.** The profile keeps running across the given-up splash. `trampoline: true` is set, and `activity_create` starts at the splash's creation. The test is in Task 3.
3. **Offline for days, or many profiled launches.** The store never holds more than 3 files, never keeps one older than 3 days, and the upload never blocks ingest flushing. The test is in Task 8.
4. **SDK disabled (opt-out or 401 kill switch) during startup.** The sampler stops, its profile is discarded, nothing is written or uploaded, and no `app_start` is emitted. The test is in Task 9.
5. **The main-thread stack read throws or returns an empty array.** That sample is skipped, sampling continues, and nothing escapes the sampler thread. The test is in Task 7.

---

## File Structure

| Path (SDK code) | Responsibility |
|---|---|
| `ARTrackerConfig.kt` (modify) | `AppStartConfig`, `appStart` param + Builder method, validation |
| `lifecycle/StartPhases.kt` (create) | `StartType`, `Phase`, `StartupTimestamps`, `coldPhases()`, `splitProcessToInit()` — pure |
| `lifecycle/AppStartTracker.kt` (modify) | cold tracker: records init / first-create / resumed / frame times, returns phases, reports drop reasons |
| `lifecycle/WarmHotStartTracker.kt` (create) | warm and hot starts, armed by the core |
| `lifecycle/StartInfoSource.kt` (create) | API 35+ `ApplicationStartInfo` timestamps behind a `fun interface` |
| `lifecycle/AppStartItem.kt` (create) | builds the `app_start` attributes map |
| `appstart/IntervalPolicy.kt` (create) | adaptive sampling interval — pure |
| `appstart/StackSampleBuffer.kt` (create) | frame/stack dedup, root drop, caps — pure |
| `appstart/ProfilePayload.kt` (create) | payload JSON + gzip |
| `appstart/StartupProfiler.kt` (create) | sampler thread lifecycle |
| `appstart/ProfileStore.kt` (create) | pending profile files |
| `appstart/ProfileUploader.kt` (create) | uploads pending files with an injected `Sender` |
| `transport/IngestRequest.kt`, `transport/IngestClient.kt` (modify) | `path` parameter so the same client posts to the profile endpoint |
| `transport/FlushWorker.kt` (modify) | also uploads profiles (registry or standalone) |
| `core/ItemFactory.kt` (modify) | `perf(attrs, name)` |
| `core/DeviceContext.kt` (modify) | `appBuild` made `internal` |
| `core/ARTrackerCore.kt` (modify) | wiring, new injection seams |

---

### Task 1: `AppStartConfig`

**Files:**
- Modify: `ARTrackerConfig.kt`
- Test: `ARTrackerConfigTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  public class AppStartConfig @JvmOverloads constructor(
      public val enabled: Boolean = true,
      public val profiling: Boolean = false,
      public val profileSampleRate: Double = 0.1,
      public val inAppPackages: Set<String> = emptySet(),
  )
  ```
  - `ARTrackerConfig.appStart: AppStartConfig`, the last constructor parameter (after `performance`, so positional Java/Kotlin callers keep working).
  - `ARTrackerConfig.Builder.appStart(value: AppStartConfig)`.

- [ ] **Step 1: Write failing tests** in `ARTrackerConfigTest`:
  - `appStart defaults measure and do not profile`: `enabled == true`, `profiling == false`, `profileSampleRate == 0.1`, `inAppPackages` empty.
  - `builder carries appStart`.
  - `appStart profileSampleRate outside 0 to 1 is invalid`: `1.5`, `-0.1` and `Double.NaN` each produce the error `"appStart.profileSampleRate must be between 0.0 and 1.0"`.
- [ ] **Step 2:** Run `./gradlew :artracker-sdk:testDebugUnitTest --tests 'id.art.tracker.ARTrackerConfigTest'`. Expected: FAIL (unresolved `AppStartConfig`).
- [ ] **Step 3: Implement.** Add the class with KDoc, the parameter, the Builder method and the `validate()` rule. Use the same `!(x in 0.0..1.0)` form as `replay.sampleRate`, so NaN fails.
- [ ] **Step 4:** Run the same command. Expected: PASS. Also run `PublicApiFuzzTest` and `ARTrackerFacadeTest` to confirm the constructor change broke nothing.
- [ ] **Step 5: Commit**: `feat(app-start): AppStartConfig (measurement on, profiling opt-in)`.

---

### Task 2: Phase model and math (pure)

**Files:**
- Create: `lifecycle/StartPhases.kt`
- Test: `lifecycle/StartPhasesTest.kt` (plain JUnit, no Robolectric)

**Interfaces:**
- Produces:
  ```kotlin
  internal enum class StartType(val wire: String) { COLD("cold"), WARM("warm"), HOT("hot") }
  internal data class Phase(val name: String, val ms: Long)
  /** Differences only are used; values are ms on any single monotonic clock. */
  internal data class StartupTimestamps(val forkMs: Long, val bindApplicationMs: Long?, val applicationOnCreateMs: Long?)
  internal fun coldPhases(processStartMs: Long, initMs: Long, firstCreateMs: Long, resumedMs: Long, frameMs: Long): List<Phase>
  internal fun warmPhases(createMs: Long, resumedMs: Long, frameMs: Long): List<Phase>
  internal fun hotPhases(startMs: Long, resumedMs: Long, frameMs: Long): List<Phase>
  /** Replaces `process_to_init` by the three API 35+ phases; returns [phases] unchanged when [ts] lacks a boundary. */
  internal fun splitProcessToInit(phases: List<Phase>, ts: StartupTimestamps): List<Phase>
  ```
- Algorithm (the tests do not fully determine it):
  - Boundaries are made monotonic: `b[i] = max(b[i-1], b[i])`, with `b[0]` = the anchor.
  - Each phase is `b[i+1] - b[i]`, so the sum is exactly `frame - anchor`.
  - Phases of 0 ms are kept, so the dashboard always gets the same names.
  - `splitProcessToInit`:
    - `bind = bindApplicationMs - forkMs`
    - `cp = applicationOnCreateMs - bindApplicationMs`
    - `pre = process_to_init.ms - bind - cp`
    - If any of the three is negative, or `bind + cp > process_to_init.ms`, return the input unchanged.

- [ ] **Step 1: Write failing tests:**
  - `cold phases are contiguous and sum to the duration`: `(1000, 1310, 2812, 3192, 3412)` → `[process_to_init 310, application_on_create 1502, activity_create 380, first_draw 220]`, sum 2412.
  - `out of order boundaries are clamped, sum unchanged`: firstCreate earlier than init → `application_on_create` is 0, and the sum still equals `frame - processStart`.
  - `warm and hot phase names`: `[activity_create, first_draw]` and `[activity_start, first_draw]`.
  - `split on API 35 replaces process_to_init`: fork 0, bind 120, onCreate 250, `process_to_init` 310 → `bind_application 120, content_providers 130, app_on_create_pre_init 60`, followed by the other phases unchanged.
  - `split keeps phases when a boundary is missing or inconsistent`: `bindApplicationMs = null`, and separately `bind + cp > 310`.
- [ ] **Step 2:** Run `--tests 'id.art.tracker.lifecycle.StartPhasesTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** in `lifecycle/StartPhases.kt`.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(app-start): phase model and contiguous phase math`.

---

### Task 3: Cold tracker records phases and drop reasons

**Files:**
- Modify: `lifecycle/AppStartTracker.kt`
- Test: `lifecycle/AppStartTrackerTest.kt` (update existing tests to the new shapes, then add tests)

**Interfaces:**
- Consumes: `coldPhases`, `Phase`, `StartType` (Task 2).
- Produces:
  ```kotlin
  internal data class AppStart(
      val type: StartType, val durationMs: Long, val firstScreen: String, val phases: List<Phase>,
      val trampoline: Boolean, val anchorUptimeMs: Long,
  )
  internal enum class ColdStartDrop { NOT_FOREGROUND_AT_INIT, LATE_FIRST_ACTIVITY, INIT_AFTER_FIRST_ACTIVITY, NEXT_ACTIVITY_TOO_LATE, BACKGROUND_BEFORE_FRAME, OUT_OF_RANGE }
  // AppStartTracker constructor: onDone changes to
  private val onDone: (drop: ColdStartDrop?) -> Unit   // null = reported
  ```
  - `AppStart` and `FirstFrameHook` stay in this file.
  - The core's `appStartFactory` type becomes `((onAppStart: (AppStart) -> Unit, onDone: (ColdStartDrop?) -> Unit) -> AppStartTracker?)?`.

- [ ] **Step 1: Write failing tests** (keep the existing ones, adapted to the new `AppStart`/`onDone` shapes):
  - `cold start reports four phases summing to the duration`:
    - Setup: process start 10 000; tracker built at 10 300 (init); `onActivityPreCreated` at 11 800; `onActivityCreated` at 11 850; resumed at 12 180; frame at 12 400.
    - Expect `phases == [process_to_init 300, application_on_create 1500, activity_create 380, first_draw 220]`, `durationMs == 2400`, `type == COLD`, `trampoline == false`, `anchorUptimeMs == 10_000`.
  - `without onActivityPreCreated the created time is the boundary`: no pre-created call, so `application_on_create` ends at 11 850.
  - `trampoline measures activity_create from the first activity`:
    - Splash pre-created at 11 800, then paused; Main created at 12 000; Main frame at 12 600.
    - Expect `trampoline == true`, `first_screen == "MainActivity"`, and `activity_create` starting at 11 800 (so `application_on_create` == 1 500).
  - `each drop path reports its reason`: one assertion per enum value on the `onDone` argument, reusing the existing drop scenarios.
  - `a reported start calls onDone with null`.
- [ ] **Step 2:** Run `--tests 'id.art.tracker.lifecycle.AppStartTrackerTest'`. Expected: FAIL (compile errors on the new shapes).
- [ ] **Step 3: Implement.**
  - Add `onActivityPreCreated` (API 29+ callback), which records `firstCreateUptimeMs` only when it is not set yet.
  - `onActivityCreated` sets it if still unset.
  - Remember `resumedUptimeMs` of the final candidate, and set `trampoline` when a candidate is given up.
  - Build `phases` with `coldPhases(processStartUptimeMs, initUptimeMs, firstCreate, resumed, frame)`.
  - Pass the reason from each existing `drop(...)` call site to `onDone`.
  - The guard constants are unchanged.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(app-start): cold start phases, trampoline flag and drop reasons`.

---

### Task 4: Warm and hot start tracker

**Files:**
- Create: `lifecycle/WarmHotStartTracker.kt`
- Test: `lifecycle/WarmHotStartTrackerTest.kt` (Robolectric, fake `FirstFrameHook`, manual clock, `Robolectric.buildActivity`)

**Interfaces:**
- Consumes: `FirstFrameHook`, `AppStart`, `StartType`, `warmPhases`, `hotPhases`.
- Produces:
  ```kotlin
  internal class WarmHotStartTracker(
      private val uptime: () -> Long,
      private val firstFrame: FirstFrameHook,
      private val onAppStart: (AppStart) -> Unit,
  ) : Application.ActivityLifecycleCallbacks {
      /** The next Activity created (→ warm) or, failing that, started (→ hot) is measured. Called by the core. */
      fun arm()
      /** Background before the first frame: the pending start is dropped and the tracker re-arms itself. */
      fun onAppBackground()
  }
  ```
- Behaviour:
  - Unarmed, the tracker only remembers `(activity, uptime)` of the latest `onActivityPreCreated`; it measures nothing.
  - Armed:
    - The next `onActivityCreated` opens a **warm** start, anchored at that Activity's pre-created time when it has one, else its created time.
    - Otherwise, the next `onActivityStarted` opens a **hot** start, anchored at the started time.
  - The measured Activity's `onActivityResumed` calls `firstFrame.await`, and the frame reports.
  - A measured Activity destroyed before its first frame (rotation) hands the start to the next created Activity, keeping the same anchor.
  - The tracker disarms after a report, after a drop, or when duration > 10 000 ms (`MAX_WARM_HOT_MS = 10_000L`).
  - All callbacks go through the same `guard {}` pattern as `AppStartTracker`.

- [ ] **Step 1: Write failing tests:**
  - `unarmed tracker reports nothing`: create, resume and frame an Activity → no starts.
  - `armed then created is warm`: arm; pre-created at 1 000, created at 1 050, resumed at 1 300, frame at 1 450 → `type WARM`, `duration 450`, `phases [activity_create 300, first_draw 150]`.
  - `armed then started without create is hot`: arm; started at 2 000, resumed at 2 080, frame at 2 140 → `HOT`, `[activity_start 80, first_draw 60]`.
  - `background before frame drops and re-arms`: arm, started, `onAppBackground()`, then a second started/resumed/frame → exactly one HOT start (the second).
  - `over 10 s is dropped`.
  - `rotation before frame keeps the anchor`: arm; created at 1 000; destroyed; new instance created at 1 200, resumed at 1 300, frame at 1 400 → WARM, duration 400.
  - Review Focus 1, `not armed means no start even when activities are alive at registration`: register the tracker while an Activity is already started (simulating a late init), then stop and start that Activity without `arm()` → no starts.
- [ ] **Step 2:** Run `--tests 'id.art.tracker.lifecycle.WarmHotStartTrackerTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** `WarmHotStartTracker`.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(app-start): warm and hot start tracker`.

---

### Task 5: `app_start` perf item and core wiring for measurement

**Files:**
- Create: `lifecycle/AppStartItem.kt`, `lifecycle/StartInfoSource.kt`
- Modify: `core/ItemFactory.kt`, `core/DeviceContext.kt` (`appBuild` → `internal val`), `core/ARTrackerCore.kt` (`startAppStartTracker`, `createAppStartTracker`, `reportAppStart`, `onBackground`, `shutdown`, constructor seams)
- Test: `lifecycle/AppStartItemTest.kt`, `lifecycle/AppStartCoreTest.kt` (update)

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces:
  ```kotlin
  // core/ItemFactory.kt — replaces fun perf(attrs)
  fun perf(attrs: Map<String, Any?>, name: String = "perf.samples"): TelemetryItem
  // lifecycle/AppStartItem.kt
  internal data class AppStartDevice(val model: String?, val osVersion: String?, val ramMb: Int?, val cores: Int?)
  internal fun appStartAttributes(start: AppStart, startId: String, profiled: Boolean, build: String?, device: AppStartDevice): Map<String, Any?>
  // lifecycle/StartInfoSource.kt
  internal fun interface StartInfoSource {
      /** Calls [onResult] once, on any thread, with the timestamps or null (unsupported, timeout 2 000 ms, error). */
      fun request(onResult: (StartupTimestamps?) -> Unit)
  }
  internal object NoStartInfo : StartInfoSource
  /** API 35+: ActivityManager.addApplicationStartInfoCompletionListener; START_TIMESTAMP_FORK / BIND_APPLICATION / APPLICATION_ONCREATE, nanos → ms. */
  @RequiresApi(35) internal class ApplicationStartInfoSource(app: Application, mainHandler: Handler) : StartInfoSource
  ```
- New `ARTrackerCore` constructor seams, placed after `appStartFactory`:
  - `warmHotFactory: ((onAppStart: (AppStart) -> Unit) -> WarmHotStartTracker?)? = null`
  - `startInfoSource: StartInfoSource? = null` (null means `ApplicationStartInfoSource` on API 35+, else `NoStartInfo`)
- Core behaviour:
  - `config.appStart.enabled == false` → neither tracker is created.
  - The warm/hot tracker is registered right after the cold tracker.
  - `onDone(drop)`: when `drop` is `NOT_FOREGROUND_AT_INIT` or `LATE_FIRST_ACTIVITY`, call `warmHot.arm()`. These arrive inside the same `onActivityCreated` dispatch, before the warm tracker's own callback, because the cold tracker is registered first.
  - `onBackground()` calls `warmHot.onAppBackground()` and then `warmHot.arm()`.
  - Cold report:
    - Run `startInfoSource.request`, then `splitProcessToInit` when the result is non-null, then emit.
    - The request runs off the main thread, so `emit` happens from the callback.
  - Warm/hot reports emit at once.
  - Emit:
    - `items.perf(appStartAttributes(...), name = "app_start")`.
    - `.copy(timestampMs = clock.nowMs() - (SystemClock.uptimeMillis() - start.anchorUptimeMs))`.
    - `startId = UuidV7.generate(clock.nowMs())`.
    - `profiled = false` in this task (Task 9 sets it).
    - `build = device.appBuild.takeIf { it > 0 }?.toString()`.
    - `device` from `Build.MODEL`, `Build.VERSION.RELEASE`, `AndroidDeviceProbe` `ramMb()` and `cores()`.
  - The old `items.event("app_start", ...)` call is deleted.

- [ ] **Step 1: Write failing tests:**
  - `AppStartItemTest`:
    - `attributes follow the wire contract`: exact map with `v 1`, `platform "android"`, `start_id`, `type "cold"`, `duration_ms`, `phases` as a list of `{name, ms}` maps, `first_screen`, `trampoline false`, `profiled false`, `build "1203"`, `device {model, os_version, ram_mb, cores}`.
    - `null device fields and build are omitted`: no `build` key, and `device` without `ram_mb`.
  - `AppStartCoreTest` (update):
    - `cold start emits a perf item named app_start`: `type == ItemType.PERF`, `name == "app_start"`, `attributes["type"] == "cold"`, and the phases' `ms` sum equals `duration_ms`.
    - `no event named app_start is emitted any more`.
    - `item timestamp is the start anchor`: fake clock and uptime → `timestampMs == wallNow - (uptimeNow - 10_000)`.
    - `api 35 timestamps split process_to_init`: fake `StartInfoSource` returning `(0, 120, 250)` → phase names begin `bind_application, content_providers, app_on_create_pre_init`.
    - `start info null keeps process_to_init`.
    - `background launched process reports its first activity as warm`: cold tracker with `startedInForeground = false` → one item with `type "warm"`.
    - `return from background reports hot`.
    - `appStart disabled registers no tracker`: `core.appStartTracker == null` and no `app_start` items after a full launch.
- [ ] **Step 2:** Run `--tests 'id.art.tracker.lifecycle.AppStartItemTest' --tests 'id.art.tracker.lifecycle.AppStartCoreTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** as described above. `PerfMonitor` keeps calling `perf(attrs)` with the default name, so it is unchanged.
- [ ] **Step 4:** Run those two classes, then `PerfMonitorTest`, `PerfCoreIntegrationTest` and `ARTrackerCoreTest`. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(app-start): app_start perf item with phases, warm/hot starts and API 35 phase split`.

---

### Task 6: Stack buffer and profile payload (pure)

**Files:**
- Create: `appstart/StackSampleBuffer.kt`, `appstart/ProfilePayload.kt`
- Test: `appstart/StackSampleBufferTest.kt`, `appstart/ProfilePayloadTest.kt` (plain JUnit; `StrictJson` from testutil parses the output)

**Interfaces:**
- Produces:
  ```kotlin
  internal class StackSampleBuffer(private val maxSamples: Int = 1000, private val maxDepth: Int = 128) {
      /** [leafFirst] as returned by Thread.getStackTrace. Returns false once full (sample ignored). Empty arrays are ignored. */
      fun add(tMs: Long, leafFirst: Array<StackTraceElement>): Boolean
      val sampleCount: Int
      fun frames(): List<Map<String, Any?>>   // {c, m, l?, f?}
      fun stacks(): List<List<Int>>            // root first
      fun samples(): List<Map<String, Any?>>   // {t, s}
  }
  internal object ProfilePayload {
      fun json(startId: String, intervalMs: Long, appPackages: List<String>, buffer: StackSampleBuffer,
               appVersion: String, build: String?, environment: String): String
      fun gzip(json: String): ByteArray
  }
  ```
- Algorithm:
  - Keep the `min(maxDepth, size)` frames nearest the leaf, i.e. `leafFirst.copyOfRange(0, min(maxDepth, size))`, then reverse them to root-first.
  - Frame key is `className|methodName|lineNumber|fileName`, mapped to its index in a `HashMap`.
  - Stack key is the `IntArray` contents (use `List<Int>` as the map key), mapped to its index.
  - `l` is omitted when `lineNumber < 0`, and `f` when `fileName == null`.
  - `sdk` is `{name: "artracker-android", version: SdkInfo.VERSION}`. `t0` is always `"process_start"`.
  - `build` is omitted when null.

- [ ] **Step 1: Write failing tests:**
  - `identical stacks share one stack and frames`: 3 adds of the same stack → `stacks().size == 1`, `samples().map { it["s"] } == [0, 0, 0]`.
  - `stacks are root first`: leafFirst `[leaf, mid, root]` → `frames()[stacks()[0][0]]["m"] == "root"`.
  - `deep stack drops frames nearest the root`: 200 frames named `m0`(leaf)..`m199`(root) → stack size 128, root-most kept frame `m127`, leaf `m0`.
  - `buffer stops at max samples`: the 1001st `add` returns false and `sampleCount == 1000`.
  - `empty stack is ignored`: returns false and nothing is recorded.
  - `negative line and null file are omitted`.
  - `payload matches the wire contract`: parse the JSON and assert:
    - `profile.v == 1`, `platform == "android"`, `t0 == "process_start"`, `interval_ms == 10`, `app_packages == ["com.example"]`
    - `context.environment`, `context.app_version`, `context.build`
    - `sdk.name == "artracker-android"`
  - `gzip round trips`.
- [ ] **Step 2:** Run `--tests 'id.art.tracker.appstart.*'`. Expected: FAIL.
- [ ] **Step 3: Implement** both files. Write JSON with `core/Json.encode` on plain maps and lists.
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(app-start): startup stack buffer and profile payload`.

---

### Task 7: Startup profiler (sampler thread)

**Files:**
- Create: `appstart/IntervalPolicy.kt`, `appstart/StartupProfiler.kt`
- Test: `appstart/IntervalPolicyTest.kt`, `appstart/StartupProfilerTest.kt`

**Interfaces:**
- Consumes: `StackSampleBuffer` (Task 6).
- Produces:
  ```kotlin
  internal class IntervalPolicy(initialMs: Long = 10, private val maxMs: Long = 40, private val thresholdNanos: Long = 1_000_000) {
      val intervalMs: Long
      /** Adds one sample's cost; doubles the interval (capped at maxMs) when the running mean exceeds the threshold, then resets the mean. */
      fun record(costNanos: Long)
  }
  internal fun interface MainStackSource { fun read(): Array<StackTraceElement> }
  internal object LooperMainStack : MainStackSource   // Looper.getMainLooper().thread.stackTrace
  internal class StartupProfiler(
      private val source: MainStackSource,
      private val uptime: () -> Long,          // SystemClock.uptimeMillis
      private val nanoTime: () -> Long,        // System.nanoTime
      private val processStartUptimeMs: Long,
      private val maxDurationMs: Long = 10_000,
      private val sleep: (Long) -> Unit = Thread::sleep,
  ) {
      fun start()                              // starts thread "artracker-startup-sampler", daemon, Thread.NORM_PRIORITY
      /** Stops and returns the buffer and the final interval; null when nothing was sampled or already discarded. */
      fun stop(): ProfileResult?
      fun discard()
      internal fun runLoopForTest()            // the loop body run synchronously (tests pass a sleep that advances a fake clock)
  }
  internal data class ProfileResult(val buffer: StackSampleBuffer, val intervalMs: Long, val meanCostMicros: Long)
  internal object ProfileDecision { fun shouldProfile(config: AppStartConfig, random: () -> Double = Math::random): Boolean }
  ```
- Loop:
  - While not stopped and `uptime() - processStartUptimeMs < maxDurationMs` and the buffer is not full:
    - `c0 = nanoTime()`; read the stack (catch `Throwable`, and skip the sample on failure); `policy.record(nanoTime() - c0)`.
    - `buffer.add(uptime() - processStartUptimeMs, stack)`; `sleep(policy.intervalMs)`.
  - `stop()` and `discard()` set a volatile flag and `interrupt()` the thread. The buffer is handed over only after the thread has ended (`join(50)`); if it has not ended, `stop` returns null.

- [ ] **Step 1: Write failing tests:**
  - `IntervalPolicyTest`:
    - `cheap samples keep 10 ms`: 100 records of 200 µs → 10.
    - `expensive samples double up to 40`: records of 2 ms → 20 after the first window, then 40, and it stays 40.
  - `StartupProfilerTest`:
    - `samples until stopped with t relative to process start`: fake uptime starts at process start + 300 and `sleep` advances it → first sample `t == 300`, consecutive samples 10 apart.
    - `stops at max duration`: `maxDurationMs = 100` → at most 10 samples.
    - Review Focus 5, `a throwing or empty stack read is skipped`: the source throws on the 2nd call and returns empty on the 3rd → the loop continues, and the sample count is 2 lower than the number of reads.
    - `discard returns nothing`: `discard()` then `stop()` → null.
    - `ProfileDecision`: `profiling = false` → false whatever the random; `rate 0.1` with random `0.05` → true, `0.2` → false; `enabled = false` → false.
- [ ] **Step 2:** Run `--tests 'id.art.tracker.appstart.IntervalPolicyTest' --tests 'id.art.tracker.appstart.StartupProfilerTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(app-start): main-thread startup sampler with adaptive interval`.

---

### Task 8: Profile store, client and uploader

**Files:**
- Create: `appstart/ProfileStore.kt`, `appstart/ProfileUploader.kt`
- Modify: `transport/IngestRequest.kt` (`IngestEndpoint.url(endpoint, path = INGEST_PATH)`, `const val PROFILES_PATH = "/api/v1/app-start/profiles"`), `transport/IngestClient.kt` (constructor param `path: String = IngestEndpoint.INGEST_PATH`), `transport/FlushWorker.kt` (`FlusherRegistry.profiles: ProfileUploader?`; uploads profiles after ingest, standalone only when the profile dir has files)
- Test: `appstart/ProfileStoreTest.kt`, `appstart/ProfileUploaderTest.kt`, `appstart/ProfileClientTest.kt` (MockWebServer)

**Interfaces:**
- Consumes: `Sender`, `SendResult`, `IngestResponse`, `KillSwitch`, `Clock`.
- Produces:
  ```kotlin
  internal class ProfileStore(private val dir: File, private val clock: Clock) {   // dir = filesDir/artracker/appstart
      data class Pending(val file: File, val startId: String, val attempts: Int)
      /** Atomic tmp+rename to "<startId>.a0.gz"; evicts the oldest beyond 3 files. */
      fun write(startId: String, gzip: ByteArray): Boolean
      fun pending(): List<Pending>          // oldest first; deletes files older than 3 days and stale .tmp first
      fun delete(p: Pending)
      /** Renames to attempts+1; deletes it when that reaches 5. */
      fun markFailed(p: Pending)
      companion object { const val MAX_FILES = 3; const val MAX_AGE_MS = 3 * 24 * 3_600_000L; const val MAX_ATTEMPTS = 5; const val DIR = "artracker/appstart" }
  }
  internal class ProfileUploader(private val store: ProfileStore, private val sender: Sender, private val killSwitch: KillSwitch) {
      /** One file at a time, oldest first, never concurrently (a lock). Returns FlushOutcome (EMPTY / SENT / FAILED / DISABLED). */
      fun uploadAll(): FlushOutcome
  }
  ```
- Result handling:
  - `Accepted`, `Drop` and `TooLarge` → delete the file and continue.
  - `Unauthorized` → stop and keep the file. The ingest flusher owns the kill-switch trip.
  - `RetryAfter` and `NetworkError` → `markFailed` and stop.
  - Kill switch active → `DISABLED`; nothing is sent.

- [ ] **Step 1: Write failing tests:**
  - `ProfileStoreTest`:
    - `write then pending returns it with attempts 0`.
    - Review Focus 3, `a fourth write evicts the oldest`.
    - Review Focus 3, `files older than 3 days are deleted on pending`: set `lastModified` to `now - 3 days - 1`.
    - `fifth failure deletes the file`.
    - `startId with a slash is rejected`.
  - `ProfileUploaderTest`, with a fake `Sender` returning a scripted list:
    - `accepted, drop and too large delete`.
    - `network error marks failed and stops`: the second file is untouched.
    - `unauthorized keeps the file`.
    - `kill switch sends nothing`.
  - `ProfileClientTest`: `posts gzip to the profiles path with ingest headers` → MockWebServer sees path `/api/v1/app-start/profiles`, `X-Artracker-Key`, `Content-Encoding: gzip`, and the body bytes.
- [ ] **Step 2:** Run `--tests 'id.art.tracker.appstart.Profile*'`. Expected: FAIL.
- [ ] **Step 3: Implement**, including the `IngestEndpoint`/`IngestClient` path parameter and the `FlushWorker` change. In `FlushWorker.result(...)`, include the profile outcome.
- [ ] **Step 4:** Run those tests, plus `IngestClient`-related tests and the `transport` package (`--tests 'id.art.tracker.transport.*'`). Expected: PASS.
- [ ] **Step 5: Commit**: `feat(app-start): profile file store and uploader on /api/v1/app-start/profiles`.

---

### Task 9: Wire the profiler into the core

**Files:**
- Modify: `core/ARTrackerCore.kt`
- Test: `lifecycle/AppStartProfilingCoreTest.kt` (Robolectric; fake `MainStackSource`, fake `Sender`, fake hook as in `AppStartCoreTest`)

**Interfaces:**
- Consumes: Tasks 5–8.
- New `ARTrackerCore` constructor seams:
  - `profilerFactory: ((processStartUptimeMs: Long) -> StartupProfiler)? = null` (null means the real one with `LooperMainStack`)
  - `profileRandom: () -> Double = Math::random`
  - `profileSender: Sender? = null` (null means `IngestClient(endpoint, key, path = PROFILES_PATH)`)
- Behaviour:
  - In `start()`, before `startAppStartTracker()`: if `ProfileDecision.shouldProfile(config.appStart, profileRandom)` and the SDK is not disabled, create the profiler and `start()` it.
  - Cold `AppStart` reported:
    - `profiler.stop()`. When the result is non-null, `profiled = true`.
    - On `scope`: build `ProfilePayload.json(...)`, gzip it, `store.write(startId, ...)`, then `uploader.uploadAll()`.
  - Any `ColdStartDrop`, `onBackground`, `shutdown`, or `isDisabled()` at report time → `profiler.discard()`.
  - `onBackground` also launches `uploader.uploadAll()` on `scope` (skipped while disabled).
  - In background init (`maintainQueueAndSaveTransportConfig`): set `FlusherRegistry.profiles = uploader` when the profile dir exists.
  - `app_packages = (listOf(app.packageName) + config.appStart.inAppPackages).distinct()`.

- [ ] **Step 1: Write failing tests:**
  - `profiled cold start writes one profile and marks the item`:
    - `profiling = true`, `profileRandom = { 0.0 }`, fake stack source.
    - After the first frame: `app_start` has `profiled == true`, the fake sender received one body, and that body (gunzipped and parsed) has `profile.start_id == attributes["start_id"]`.
  - `not chosen by the sample rate means no profiler`: `profileRandom = { 0.99 }` → `profiled == false`, no sender call.
  - `dropped cold start discards the profile`: `startedInForeground = false` → no file in `filesDir/artracker/appstart`.
  - Review Focus 4, `disabled during startup discards everything`:
    - Opt out (`ARTracker.optOut()` path / kill switch) before the first frame.
    - Then: no `app_start` item, no file, no sender call, and the profiler thread is not alive.
  - `app_packages include the package and configured extras`: `inAppPackages = setOf("com.example.feature")` → `["<app.packageName>", "com.example.feature"]`.
- [ ] **Step 2:** Run `--tests 'id.art.tracker.lifecycle.AppStartProfilingCoreTest'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run the class, then the full gate `./gradlew :artracker-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit**: `feat(app-start): profile sampled cold starts and upload them`.

---

### Task 10: Docs, sample app and on-device overhead measurement

**Files:**
- Modify:
  - `artracker-sdk/README.md` (new section "App start", replacing the "Warm and hot starts are not measured" note near :563)
  - `artracker-sdk/ARCHITECTURE.md` (lifecycle table :82/:150, event table :442: `perf` `app_start` with the new attributes)
  - `artracker-sdk/CHANGELOG.md` (`[Unreleased]` → Added + Changed: "`app_start` is now a `perf` item; the `event` of the same name is no longer sent")
  - `artracker-sdk/PERF.md` (new section "App start profiling overhead")
  - `app/.../sample/SampleApp.kt` (`appStart = AppStartConfig(profiling = true, profileSampleRate = 1.0)` in debug only)

- [ ] **Step 1:** Update README, ARCHITECTURE and CHANGELOG. Cover:
  - the config table, the phase names per type, and the advice to call `ARTracker.init` first in `Application.onCreate`;
  - that code before init has a duration but no stacks;
  - that profiles need the uploaded `mapping.txt` (the Gradle plugin) to be readable.
- [ ] **Step 2:** In `SampleApp`, enable profiling in debug builds only.
- [ ] **Step 3: Measure on a physical device, in a release build of the sample app.**
  - Run 10 cold starts each with profiling off and with profiling on (`adb shell am force-stop` + `am start -W`).
  - Record median `TotalTime` for both, plus the `meanCostMicros` and final interval logged by the profiler (log it with `SdkLog.d` when `debug = true`).
  - Accept when the mean cost is ≤ 500 µs and the median `TotalTime` difference is ≤ 5 %. Write both tables into `PERF.md`.
  - If no physical device is available, write the section with `NOT MEASURED` and the exact commands, as `PERF.md` already does for other items.
- [ ] **Step 4:** Run the full gate `./gradlew :artracker-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit**: `docs(app-start): README, architecture, changelog and profiling overhead`.

---

## Self-review notes

- **Spec coverage:**
  - §3 decisions: Tasks 1, 5, 9.
  - §4.1 types and phases, including API 35 and background-launched warm: Tasks 2–5.
  - §5.1: Task 5. §5.2: Tasks 6 and 9. §5.3: Tasks 7–9.
  - §8 Android docs: Task 10. §9 anti-goals (overhead, misleading numbers, sampler safety): Tasks 3–4, 7 and 10.
- **Interpretations:**
  - The spec's `process_to_init` ends at "`ARTracker.init` entry". The tracker's `initUptimeMs` is taken when the tracker is built inside `core.start()`, a few ms after entry. That gap is accepted, and it lands in `application_on_create`.
  - `ApplicationStartInfo` timestamps are used only as differences, so their clock base does not matter.
