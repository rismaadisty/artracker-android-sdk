# Replay On Error — Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Save user data quota: by default replay records into a rolling on-device buffer and uploads only the last ~60 s around a crash, an ANR or a logged error, instead of the whole session.

**Architecture:** A new per-session `RecordingMode` (`FULL` = today's behavior, `BUFFER` = new). In `BUFFER` mode `ScreenChunker` closes 5 s chunks flagged `held`; `ReplayStore` keeps held rows (state 0, `seq = -1`) trimmed to 60 s / 13 chunks per session and never hands them to the uploader. A trigger promotes the session's held rows to ready (allocating `seq` in `first_ts` order) and opens a 30 s follow-up window. Crashes and API 30+ ANRs are handled at the next launch: held rows created before this process started are promoted if the previous run crashed, otherwise dropped.

**Tech Stack:** Kotlin, SQLite (`SQLiteOpenHelper`), JUnit4 + Robolectric (`FakeClock`, `ManualDispatcher`, `testReplayController`, `testScreenshotKit` from `testutil`).

**Spec:** `docs/superpowers/specs/2026-11-21-replay-on-error-design.md` (comparison and rationale: `docs/replay-on-error-comparison.md`).

**Branch:** create `feature/replay-on-error` from `main` (the current checkout `feature/team-lead-feedback` has unrelated work).

**Test command:** `./gradlew :artracker-sdk:testDebugUnitTest --tests '<FQCN>'`; full gate `./gradlew :artracker-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`.

## Global Constraints

- `ReplayConfig.sampleRate` default changes `0.1` → `0.0`; new `ReplayConfig.onErrorSampleRate: Double = 1.0`, added as the **last** constructor parameter (`@JvmOverloads` positional callers keep compiling), validated `0.0..1.0` with message `"replay.onErrorSampleRate must be between 0.0 and 1.0"`. `explicitApi()` is on: every new public member needs an explicit `public`.
- Mode per session: `sampleRate` hit → `FULL`; else `onErrorSampleRate` hit (sampled on `sessionId + ":on-error"`) → `BUFFER`; else not recorded. `startReplay()` (manual) → always `FULL`.
- `BUFFER_WINDOW_MS = 5_000` (chunk window in BUFFER mode); FULL keeps `ScreenChunker.DEFAULT_WINDOW_MS = 10_000`.
- `HELD_WINDOW_MS = 60_000`, `MAX_HELD_PER_SESSION = 13` (12 × 5 s + the trigger chunk; was 8, fixed in the final review).
- `FOLLOW_UP_MS = 30_000` after a logged-error trigger.
- `MAX_ERROR_TRIGGERS_PER_SESSION = 3`, `MIN_TRIGGER_GAP_MS = 60_000`.
- Triggers: items of level `ERROR`/`FATAL` accepted by the pipeline whose `timestampMs >=` the core's process start time (this covers the API 24–29 watchdog ANR); at launch: `CrashRecovery.recover(...) > 0` or an exit item named `anr` / `native_crash`.
- DB version 4 → 5; rows that exist before the upgrade are ready (`state = 1`).
- No change to capture, masking, encoder, governor, the `artracker-screen-v1` format, `ReplayClient` or `ReplayFlusher`.
- `CrashHandler` stays as is: no database, network or lock work while the process is dying.
- FULL mode (explicit `sampleRate > 0` or `startReplay()`) behaves exactly as today.

## Review Focus

1. **Exit/crash items of the previous process emitted at launch** (API 30+ `anr`, `native_crash`): they must not count as an error trigger of the session now running (would upload 30 s of a healthy new session and burn a trigger slot). Task 7 `exitItemOfPreviousProcess_doesNotTriggerCurrentBuffer`.
2. **Session continues after a crash restart** (the session timeout has not passed, so the same session id): the launch decision works by `created_at < processStart`, not by session id. Task 2 `promoteHeldBefore_ignoresSessionId` and Task 7 `crashThenRelaunchSameSession_promotesPreviousBuffer`.
3. **A burst of logged errors** (an API failing every second): one promotion, then nothing until 60 s later, and at most 3 per session. Task 5 table and Task 6 `errorBurst_promotesOnce`.
4. **Opt-out or a 401 kill-switch while held rows exist**: nothing is uploaded, not even after a crash relaunch. Task 7 `optedOut_heldRowsNeverPromoted`.
5. **Upgrade from DB v4 with chunks still queued**: they keep uploading. Task 2 `upgradeFromV4_existingRowsStayReady`.

---

### Task 1: Config and recording mode selection

**Files:**
- Modify: `artracker-sdk/src/main/java/id/art/tracker/ARTrackerConfig.kt:66-84` (KDoc + `ReplayConfig`), `:162-170` (validation)
- Create: `artracker-sdk/src/main/java/id/art/tracker/replay/RecordingMode.kt`
- Test: `artracker-sdk/src/test/java/id/art/tracker/ARTrackerConfigTest.kt`, `artracker-sdk/src/test/java/id/art/tracker/replay/RecordingModeTest.kt` (new)

**Interfaces:**
- Produces:
  - `ReplayConfig.onErrorSampleRate: Double` (public, default `1.0`); `ReplayConfig.sampleRate` default `0.0`.
  - `internal enum class RecordingMode { FULL, BUFFER }`
  - `internal fun RecordingMode.Companion.select(sessionId: String, manual: Boolean, config: ReplayConfig, sampler: (String, Double) -> Boolean): RecordingMode?` (null = do not record; does not look at `enabled`, the caller does)
  - `internal const val ON_ERROR_SALT = ":on-error"` in `RecordingMode.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// ARTrackerConfigTest
@Test fun replayDefaults_bufferModeByDefault() {
    val c = ReplayConfig()
    assertEquals(0.0, c.sampleRate, 0.0)
    assertEquals(1.0, c.onErrorSampleRate, 0.0)
}
@Test fun onErrorSampleRate_outOfRange_isInvalid() {
    val errors = config(replay = ReplayConfig(onErrorSampleRate = 1.5)).validate()
    assertTrue(errors.contains("replay.onErrorSampleRate must be between 0.0 and 1.0"))
}

// RecordingModeTest
private val always = { _: String, _: Double -> true }
private val never = { _: String, _: Double -> false }
private val onlyOnError = { id: String, _: Double -> id.endsWith(ON_ERROR_SALT) }
@Test fun manual_isFull() = assertEquals(RecordingMode.FULL, RecordingMode.select("s", true, ReplayConfig(), never))
@Test fun sampleRateHit_isFull() = assertEquals(RecordingMode.FULL, RecordingMode.select("s", false, ReplayConfig(), always))
@Test fun onlyOnErrorHit_isBuffer() = assertEquals(RecordingMode.BUFFER, RecordingMode.select("s", false, ReplayConfig(), onlyOnError))
@Test fun noHit_isNull() = assertNull(RecordingMode.select("s", false, ReplayConfig(), never))
@Test fun bufferUsesSaltedId_andOnErrorRate() {
    val calls = mutableListOf<Pair<String, Double>>()
    RecordingMode.select("s", false, ReplayConfig(sampleRate = 0.2, onErrorSampleRate = 0.7)) { id, r -> calls += id to r; false }
    assertEquals(listOf("s" to 0.2, "s$ON_ERROR_SALT" to 0.7), calls)
}
```

- [ ] **Step 2: Run, expect FAIL** (`onErrorSampleRate` / `RecordingMode` unresolved)

Run: `./gradlew :artracker-sdk:testDebugUnitTest --tests 'id.art.tracker.ARTrackerConfigTest' --tests 'id.art.tracker.replay.RecordingModeTest'`

- [ ] **Step 3: Implement** the config change (KDoc: describe BUFFER mode, the trigger list and that `sampleRate` now selects full-session recording), the validation line next to the `sampleRate` one, and `RecordingMode` + `select` (`enum class` with `companion object`).

- [ ] **Step 4: Run, expect PASS**; then run the whole `id.art.tracker.*ConfigTest` and `id.art.tracker.replay.*` suites. Existing tests that relied on `sampleRate = 0.1` as a default must now state it explicitly. Do not change their expectations.

- [ ] **Step 5: Commit** `feat(replay): add onErrorSampleRate and per-session recording mode`

---

### Task 2: ReplayStore held rows (DB v5)

**Files:**
- Modify: `artracker-sdk/src/main/java/id/art/tracker/transport/ReplayQueue.kt`, `artracker-sdk/src/main/java/id/art/tracker/transport/ReplayStore.kt`
- Test: `artracker-sdk/src/test/java/id/art/tracker/transport/ReplayStoreTest.kt`

**Interfaces:**
- Produces:
  - `NewReplayBatch.held: Boolean = false` (new last constructor parameter)
  - on `ReplayQueue`: `fun promote(sessionId: String): Int`, `fun promoteHeldBefore(cutoffMs: Long): Int`, `fun dropHeld(sessionId: String): Int`, `fun dropHeldBefore(cutoffMs: Long): Int` (return = rows affected; never throw, 0 on storage failure, like the other hot-path methods)
  - `ReplayStore` constructor gains `heldWindowMs: Long = HELD_WINDOW_MS`, `maxHeldPerSession: Int = MAX_HELD_PER_SESSION` (after `maxAttempts`, before `dbName`, so update the test helper `store(...)`); constants `HELD_WINDOW_MS = 60_000L`, `MAX_HELD_PER_SESSION = 8` (13 after the final review) in its companion

- [ ] **Step 1: Write the failing tests** (extend the `batch(...)` helper with `held: Boolean = false`)

```kotlin
@Test fun heldBatch_isNotClaimable_andNotPending() {
    val s = store(); s.enqueue(batch("s1", held = true))
    assertNull(s.claim()); assertEquals(0, s.pendingCount())
}
@Test fun promote_makesHeldClaimable_withSeqInFirstTsOrder_andStartFromFirstPromoted() {
    val s = store()
    s.enqueue(batch("s1", firstTs = 20_000, lastTs = 24_000, held = true))
    s.enqueue(batch("s1", firstTs = 25_000, lastTs = 29_000, held = true))
    assertEquals(2, s.promote("s1"))
    val a = s.claim()!!; s.complete(a.id); val b = s.claim()!!
    assertEquals(0, a.seq); assertEquals(1, b.seq)
    assertEquals(20_000, a.startMs); assertEquals(9_000, b.durationMs)
}
@Test fun held_trimmedTo60sWindow() {
    val s = store()
    s.enqueue(batch("s1", firstTs = 0, lastTs = 4_000, held = true))        // last_ts 4s: older than 70s - 60s
    s.enqueue(batch("s1", firstTs = 65_000, lastTs = 70_000, held = true))
    assertEquals(1, s.promote("s1"))
}
@Test fun held_cappedAt8PerSession_newestKept() {
    val s = store()
    repeat(10) { i -> s.enqueue(batch("s1", firstTs = i * 5_000L, lastTs = i * 5_000L + 4_000, held = true)) }
    assertEquals(8, s.promote("s1"))
    assertEquals(10_000, s.claim()!!.startMs) // chunks 0 and 1 trimmed
}
@Test fun held_trimDoesNotTouchOtherSessionsOrReadyRows() { /* s1 ready + s2 held old; enqueue s2 held new → s1 still claimable */ }
@Test fun promoteHeldBefore_ignoresSessionId() {
    val s = store(); s.enqueue(batch("s1", held = true)); clock.advance(10)
    val cutoff = clock.nowMs(); s.enqueue(batch("s1", held = true))
    assertEquals(1, s.promoteHeldBefore(cutoff)); assertEquals(1, s.pendingCount())
}
@Test fun dropHeld_andDropHeldBefore_deleteRowsAndBodyFiles() { /* rows gone, bodyFiles() empty */ }
@Test fun promotedThenLaterReady_continuesSeq() { /* promote 2 held (seq 0,1), then enqueue ready → seq 2 */ }
@Test fun clearFormat_alsoDropsHeld() { /* screenshot_disabled path */ }
@Test fun upgradeFromV4_existingRowsStayReady() { /* build a v4 DB with the old schema via SQLiteOpenHelper(version 4), insert a row + body, open ReplayStore → claim() returns it */ }
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew :artracker-sdk:testDebugUnitTest --tests 'id.art.tracker.transport.ReplayStoreTest'`

- [ ] **Step 3: Implement**
  - `DB_VERSION = 5`; `createBatchesTable` gets `state INTEGER NOT NULL DEFAULT 1`; `onUpgrade` `if (oldVersion < 5) ALTER TABLE replay_batches ADD COLUMN state INTEGER NOT NULL DEFAULT 1` (the `< 2` rebuild path already has it).
  - `enqueue`: held → `state = 0`, `seq = -1`, **no** `nextSeq` call (no `replay_seq` row); then, in the same transaction, trim that session's held rows: delete held rows of the session with `last_ts < (max held last_ts of the session) - heldWindowMs`, then all but the newest `maxHeldPerSession` by `first_ts`; then `evictIfNeeded`.
  - `nextPending` and `pendingCount`: add `state = 1`.
  - `promote` / `promoteHeldBefore`: one transaction; select held rows (`state = 0` and the session / `created_at < cutoff`) ordered by `session_id, first_ts, rowid`; for each, `seq = nextSeq(db, sessionId, firstTs, now)` and `state = 1`. Refactor `nextSeq` to take `(sessionId, firstTsMs)` instead of the batch.
  - `dropHeld` / `dropHeldBefore`: `deleteRows(db, "state = 0 AND ...")`.

- [ ] **Step 4: Run, expect PASS**; also run `ReplayFlusherTest` and `ReplayClientTest` (no changes expected).

- [ ] **Step 5: Commit** `feat(replay): held replay rows in ReplayStore (db v5)`

---

### Task 3: ReplaySink promote/discard and ReplayIntake

**Files:**
- Modify: `artracker-sdk/src/main/java/id/art/tracker/replay/ReplayIntake.kt`
- Test: `artracker-sdk/src/test/java/id/art/tracker/replay/ReplayIntakeTest.kt`

**Interfaces:**
- Consumes: Task 2 `ReplayQueue.promote`, `dropHeld`, `NewReplayBatch.held`
- Produces: on `fun interface ReplaySink`: `fun promote(sessionId: String) {}` and `fun discardHeld(sessionId: String) {}` (default no-ops, so test lambdas `ReplaySink { ... }` keep compiling)

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun heldBatch_isStored_butDoesNotKick()      // queue has 1 row, kicks == 0
@Test fun promote_kicksOnlyWhenRowsPromoted()      // promote with 0 rows → kicks 0; with 2 held → kicks 1
@Test fun promote_whileDisabled_doesNothing()      // isDisabled = true → queue.promote not called, kicks 0
@Test fun discardHeld_dropsSessionHeldRows()
```

- [ ] **Step 2: Run, expect FAIL**: `--tests 'id.art.tracker.replay.ReplayIntakeTest'`
- [ ] **Step 3: Implement**: `accept` calls `onQueued()` only for `!batch.held`; `promote` → `if (!isDisabled() && queue().promote(id) > 0) onQueued()`; `discardHeld` → `queue().dropHeld(id)`.
- [ ] **Step 4: Run, expect PASS**
- [ ] **Step 5: Commit** `feat(replay): sink can promote or discard a session's held chunks`

---

### Task 4: ScreenChunker held flag and per-mode window

**Files:**
- Modify: `artracker-sdk/src/main/java/id/art/tracker/replay/screenshot/ScreenChunker.kt`
- Test: `artracker-sdk/src/test/java/id/art/tracker/replay/screenshot/ScreenChunkerTest.kt`

**Interfaces:**
- Produces: `var held: Boolean = false` and `var windowMs: Long` (was the `windowMs` constructor `val`; keep the constructor parameter as the initial value) on `ScreenChunker`; `const val BUFFER_WINDOW_MS: Long = 5_000` in its companion. Both are set only from the processing context.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun close_carriesHeldFlag()            // held = true → batch.held; held = false → !batch.held
@Test fun heldReadAtClose_notAtOpen()        // open with held = true, set false, close → !batch.held
@Test fun windowMs_change_appliesToOpenChunk()  // windowMs = 5_000, advance 5_000, closeIfDue → closed
```

- [ ] **Step 2: Run, expect FAIL**: `--tests 'id.art.tracker.replay.screenshot.ScreenChunkerTest'`
- [ ] **Step 3: Implement** (`NewReplayBatch(..., held = held)` in `close()`)
- [ ] **Step 4: Run, expect PASS**
- [ ] **Step 5: Commit** `feat(replay): chunker marks held chunks and takes a mode window`

---

### Task 5: ErrorTriggerPolicy

**Files:**
- Create: `artracker-sdk/src/main/java/id/art/tracker/replay/ErrorTriggerPolicy.kt`
- Test: `artracker-sdk/src/test/java/id/art/tracker/replay/ErrorTriggerPolicyTest.kt` (new)

**Interfaces:**
- Produces: `internal class ErrorTriggerPolicy(maxPerSession: Int = 3, minGapMs: Long = 60_000)` with `fun admit(sessionId: String, nowMs: Long): Boolean` (true = promote now; a new session id resets the count and the gap). Not thread-safe: main thread only.

- [ ] **Step 1: Write the failing test** as one table test:

| call | session | nowMs | expected |
|---|---|---|---|
| 1 | s1 | 0 | true |
| 2 | s1 | 1_000 | false (gap) |
| 3 | s1 | 60_000 | true |
| 4 | s1 | 120_000 | true |
| 5 | s1 | 999_000 | false (3 used) |
| 6 | s2 | 999_500 | true (new session) |

- [ ] **Step 2: Run, expect FAIL**; **Step 3: Implement**; **Step 4: Run, expect PASS**
- [ ] **Step 5: Commit** `feat(replay): rate limit for error-triggered uploads`

---

### Task 6: ReplayController BUFFER mode

**Files:**
- Modify: `artracker-sdk/src/main/java/id/art/tracker/replay/ReplayController.kt`
- Test: `artracker-sdk/src/test/java/id/art/tracker/replay/ReplayControllerTest.kt`

**Interfaces:**
- Consumes: Task 1 `RecordingMode.select`, Task 3 `ReplaySink.promote/discardHeld` (called on the sink passed to the controller, by default `core.replaySink`), Task 4 `ScreenChunker.held/windowMs/BUFFER_WINDOW_MS`, Task 5 `ErrorTriggerPolicy`
- Produces:
  - `internal fun onError()`: `noteError()` renamed (update its callers and tests). FULL: same as today's `noteError()`. BUFFER: also promotes when the policy admits.
  - `@VisibleForTesting internal val mode: RecordingMode?` (main thread view; null when not running)
  - Constructor gains `sink: ReplaySink = core.replaySink` (after `sampler`, threaded through `create(...)` and `testReplayController(...)`), used only for `promote` / `discardHeld`.

Behavior to implement:
- `shouldRecord(sessionId)` → `modeFor(sessionId): RecordingMode?` = `null` if `userStopped || screenshotDisabled`; else `RecordingMode.select(sessionId, manualRequested, replayConfig, sampler)` gated by `manualRequested || replayConfig.enabled`. `evaluate()` records the mode; on a session change in `evaluate()` / `onTouch` re-select the mode for the new id.
- Apply the mode on the processing context with `submit { chunker.held = mode == BUFFER; chunker.windowMs = if (BUFFER) BUFFER_WINDOW_MS else DEFAULT_WINDOW_MS }` in `begin()` and on a session or mode change, **before** the next frame is submitted (the processing context is serial, so the order of `submit` calls is enough).
- Session change while in BUFFER: after the old chunk is closed (`processShot` already closes on session change), call `sink.discardHeld(oldSessionId)` on the processing context.
- `onError()` in BUFFER, main thread: `markError` as today; if `errorPolicy.admit(recordingSessionId, now)`: `submit { chunker.close(); sink.promote(sessionId); chunker.held = false }` and set `followUpUntilMs = now + FOLLOW_UP_MS`. When the follow-up window ends (checked in `onFlushTick`; `halt()` also clears `followUpUntilMs`), `submit { chunker.close(); chunker.held = true }` so the follow-up chunk ships and later chunks are held again. If a chunk is already closed as ready during follow-up it is uploaded by the normal kick.
- Background, `stop()`, kill-switch: unchanged; in BUFFER the closing chunk is held, so nothing is kicked.
- `companion`: `FOLLOW_UP_MS = 30_000L`.

- [ ] **Step 1: Write the failing tests** (sampler `onlyOnError` from Task 1; config `ReplayConfig(enabled = true)`; sink that records `accept`/`promote`/`discardHeld` calls)

```kotlin
@Test fun bufferMode_chunksAreHeld_andUse5sWindow()          // frames over 6 s → ≥1 batch, all held, window 5 s
@Test fun bufferMode_noError_nothingPromoted()               // run 2 min of frames → promote never called
@Test fun bufferMode_error_closesOpenChunkHeld_thenPromotes() // order of sink calls: accept(held=true) then promote(sid)
@Test fun bufferMode_followUp30s_ready_thenHeldAgain()        // chunks closed within 30 s after error: held=false; after: held=true
@Test fun errorBurst_promotesOnce()                            // 10 onError() within 5 s → promote called once
@Test fun bufferMode_sessionChange_discardsOldHeld()           // rotate session → discardHeld(old) called
@Test fun fullMode_unchanged_noPromoteNoHeld()                 // sampler always true → all batches !held, onError never promotes
@Test fun manualStart_isFull_evenWithBufferSampler()
@Test fun bufferMode_background_closesHeld_noKick()            // onAppBackground → last batch held
```

- [ ] **Step 2: Run, expect FAIL**: `--tests 'id.art.tracker.replay.ReplayControllerTest'`
- [ ] **Step 3: Implement** per "Behavior to implement"
- [ ] **Step 4: Run, expect PASS**; then the whole `id.art.tracker.replay.*` package (`ReplayIntegrationTest`, `ReplayScreenshotCaptureTest` use the always-true sampler, so they stay FULL)
- [ ] **Step 5: Commit** `feat(replay): buffer mode records held chunks and promotes them on error`

---

### Task 7: Core wiring: triggers and launch decision

**Files:**
- Modify: `artracker-sdk/src/main/java/id/art/tracker/core/ARTrackerCore.kt:414-421` (`onItemAccepted`), `:639-675` (`onBackgroundInit`, `collectExitInfo`)
- Test: `artracker-sdk/src/test/java/id/art/tracker/core/ARTrackerCoreTest.kt`

**Interfaces:**
- Consumes: Task 6 `ReplayController.onError()`, Task 2 `ReplayStore.promoteHeldBefore/dropHeldBefore`, Task 3 kick behavior
- Produces: `private val processStartMs = clock.nowMs()` captured in the core's constructor; `collectExitInfo(): Boolean` (true when an exit item named `anr` or `native_crash` was emitted)

Behavior to implement:
- `onItemAccepted`: call `it.onError()` and `it.onTrigger(TriggerKind.ERROR)` only if `item.timestampMs >= processStartMs` (exit items are re-timed to the exit and recovered crashes never go through the pipeline).
- End of `onBackgroundInit` (not in the opted-out early-return branch): `val previousRunCrashed = recovered > 0 || exitCrashed`; then, only if the replay DB file exists (`app.getDatabasePath(ReplayStore.DB_NAME).exists()`, so no DB is created for apps without replay) and `!isDisabled()`: if crashed → `replayQueue.promoteHeldBefore(processStartMs)` and kick when > 0 (`SdkLog.d("replay buffer promoted after a crash: $n chunks")`); else → `replayQueue.dropHeldBefore(processStartMs)`.
- The opted-out branch: `replayQueue.dropHeldBefore(processStartMs)` if the DB exists (opt-out already `clear()`s, this is belt and braces for a crash between the two).

- [ ] **Step 1: Write the failing tests** (seed `artracker_replay.db` through a `ReplayStore` with an earlier `FakeClock` time before building the core; seed a crash via `crashStore` the way existing crash-recovery tests do; replay sink = the real intake)

```kotlin
@Test fun crashThenRelaunch_promotesPreviousBuffer()             // held rows → pendingCount 2 after awaitIdle
@Test fun crashThenRelaunchSameSession_promotesPreviousBuffer()  // held rows use the session id that is still current
@Test fun cleanRelaunch_dropsPreviousBuffer()                    // no crash file, no exit item → rows gone
@Test fun optedOut_heldRowsNeverPromoted()                       // crash file + opt-out → nothing pending
@Test fun noReplayDb_launchDoesNotCreateIt()
@Test fun exitItemOfPreviousProcess_doesNotTriggerCurrentBuffer() // fake replay controller counts onError(): 0 for an item with timestampMs < processStartMs, 1 for a live error
```

For the API 30+ exit path, use the existing `ExitInfoCollector` test seam if `ARTrackerCoreTest` already has one; otherwise test `collectExitInfo()`'s return value through a Robolectric `ShadowActivityManager.addApplicationExitInfo` with `REASON_ANR`.

- [ ] **Step 2: Run, expect FAIL**: `--tests 'id.art.tracker.core.ARTrackerCoreTest'`
- [ ] **Step 3: Implement** per "Behavior to implement"
- [ ] **Step 4: Run the full gate**: `./gradlew :artracker-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`, expect BUILD SUCCESSFUL
- [ ] **Step 5: Commit** `feat(replay): upload the buffer on live errors and after a crash relaunch`

---

### Task 8: Docs, sample app, measurement

**Files:**
- Modify: `README.md` (config table line 129, "Session replay" section around lines 298-365, the example at line 104), `artracker-sdk/CHANGELOG.md` (`[Unreleased]`), `docs/android-sdk-playbook.md` (replay section), `artracker-sdk/PERF.md` (new section), `app/src/main/java/id/art/tracker/sample/SampleApp.kt:60`, `app/src/main/java/id/art/tracker/playground/settings/PlaygroundSettings.kt:69`

- [ ] **Step 1: CHANGELOG** under `[Unreleased]`: `### Changed`, **behavior change**: `ReplayConfig.sampleRate` default `0.1` → `0.0`; apps that want full-session replay set it explicitly. `### Added`: `onErrorSampleRate` (default `1.0`), what triggers an upload, the 60 s buffer, the 30 s follow-up, the 3-per-session limit, and the crash/ANR upload on the next launch.
- [ ] **Step 2: README + playbook**: same facts in the config table and the replay section; state the known gap: up to the last 5 s before a crash (the chunk still open in memory) is lost.
- [ ] **Step 3: Sample app / playground**: keep their current behavior explicit (state `sampleRate`), and add a playground switch for `onErrorSampleRate` if `PlaygroundSettings` already exposes `sampleRate`.
- [ ] **Step 4: PERF.md**: add section "Replay buffer mode", same procedure as section 2 with `sampleRate = 0.0`, `onErrorSampleRate = 1.0`: 5 minutes of scrolling without errors, then the same with one `ARTracker` error log. Record the bytes uploaded (expected ~0, then ≤ ~5 MB + follow-up) and the capture p95. Values the run did not produce stay `NOT MEASURED`.
- [ ] **Step 5: Run the full gate**, then commit `docs(replay): document buffer mode and the sampleRate default change`

---

## Out of scope (tracked elsewhere)

- Backend/dashboard (`artracker` repo): a replay whose `start_ms` is mid-session; a link from a crash to its replay; using `has_error`.
- Web and iOS SDKs.
