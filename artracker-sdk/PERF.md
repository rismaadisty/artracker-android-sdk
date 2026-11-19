# Performance measurement notes (Task 25)

Status (Phase 4, 2026-11-16): **measured on a physical device**, OPPO CPH2387 (Android 14, API 34, 60 Hz), **debug build**
of the sample at commit `ec9ace3` plus the uncommitted Phase 4 test changes (adb over USB, screen on unless noted). The emulator has only
API 30 images here, so the "Emulator API 34" columns stay **NOT MEASURED**. Nothing is an estimate; items that could
not be run are listed under "Not measured here". Targets were not lowered: one miss (`init` cost, section 1) is recorded
with its real number and cause. Not run at all: Perfetto traces, API 24-29 (no such device), backend E2E (no Docker).

Targets come from the issue #12 acceptance criteria and `ARCHITECTURE.md` section 1 / 6.

## What was added for measuring

Both hooks only run when `ARTrackerConfig.debug = true` (they are behind `SdkLog.enabled`; with `debug = false` the
cost is one volatile boolean read, no allocation, no log call). The sample app is built with `debug = true`.

| Log line (tag `ARTracker`, priority D) | Where | Meaning |
| --- | --- | --- |
| `init synchronous part took <N> us on thread <name>` | `ARTracker.init`, after the object graph is built and `start()` returned | Everything `init` does on the calling thread (config validation, `ARTrackerCore` construction incl. the `SharedPreferences` handles, crash handler install, lifecycle registration). The database, the purge, crash recovery and the first flush are on the background thread and are not included, by design. `thread main` confirms it ran on the main thread. |
| `replay capture took <N> us (budget 16 ms, fps <f>)` | `FrameScheduler`, after each capture that did not throw | Wall time of one capture on the main thread: `RootViewLocator` + `ScreenshotCapturer` (composite and mask collection, the capture callback). The mask, scale, JPEG and chunk work happens on a background thread and is not part of this number. |

## 1. `init()` main-thread cost (target: median of 5 cold starts <= 2 ms)

Procedure (debug build of the sample app, cold process each time):

```sh
./gradlew :app:installDebug
for i in 1 2 3 4 5; do
  adb shell am force-stop id.art.tracker
  adb logcat -c
  adb shell am start -W -n id.art.tracker/.MainActivity > /dev/null
  sleep 3
  adb logcat -d -s ARTracker:D | grep "init synchronous part"
done
```

Take the median of the five `<N> us` values (2000 us is the target). Run on the emulator (API 34) and, if available,
on a mid-range physical device (the emulator on a fast host understates the cost; the device number decides).
Do not use a run right after install: the first launch pays for dex loading and is not comparable. Discard it and
take five more.

Known risk (finding from the code review of the core wiring): `ARTrackerCore` opens three `SharedPreferences` files
on the calling thread (`artracker_transport`, `artracker_identity`, `artracker_session`) and reads values from them
(`IdentityStore`, `SessionManager`, kill-switch). The first `getSharedPreferences` + first read blocks until the XML file
is loaded from disk, which on a slow device can exceed 2 ms. **If the measured median is above 2 ms, the remedy is to
prefetch those preferences off the main thread** (start the three loads on the SDK background thread before
`ARTrackerCore` needs them, or construct the stores lazily), then re-measure. Do not raise the target.

| Run | Emulator API 34 (us) | OPPO CPH2387, API 34, debug build (us) |
| --- | --- | --- |
| discarded (first start after the data wipe) | NOT MEASURED | 126517 |
| 1 | NOT MEASURED | 104699 |
| 2 | NOT MEASURED | 105280 |
| 3 | NOT MEASURED | 103355 |
| 4 | NOT MEASURED | 104221 |
| 5 | NOT MEASURED | 107621 |
| **Median** | **NOT MEASURED** | **104699 (about 105 ms): target 2000 us MISSED** |

**The target is missed by a wide margin on this device and was not lowered.** Where the time goes (from temporary
`init-step` logs around each step of `ARTracker.init`, removed again after the run; one cold start each, so read them as
orders of magnitude): the first `getSharedPreferences` + first read of the three SDK files about 15 ms, building the
pipeline and the coroutine scopes about 10 ms, starting replay about 17 ms, starting performance sampling about 3 ms,
`AppStartTracker` registration 4-5 ms. Those steps explain only part of the 104 ms: in a debug build the code is not AOT
compiled (interpreted/JIT on first use) and the sample has 22 dex files, which inflates every first call, so this
number is not a release-build figure. A second set of 6 cold starts after `cmd package compile -m speed -f` (the package still reported `status=verify`, because the
sample is debuggable, so this is not a true AOT figure) gave 63.8-73.1 ms, median about 65 ms: the cost is real, not only
interpretation. Reaching 2 ms means building the core off the main thread (a facade that queues calls until the core is ready),
which changes the public behaviour of `ARTracker.init` and is **not done here: it needs a decision**; prefetching the
three `SharedPreferences` alone would save only about 15 ms. Not done: a release build (R8 + baseline profile) measurement, and moving the
three `SharedPreferences` loads off the main thread, which the note above prescribes. Both are the next step for this
target.

## 2. Replay capture on the Feed screen (target: p95 <= 16 ms, the `FrameScheduler` budget)

Procedure: sample app, debug build, replay enabled with every session sampled (the sample's default). Open Feed, scroll
it for about 60 s so that frames are captured (a capture only runs when the screen was drawn since the last tick),
then collect and compute the percentiles:

```sh
adb logcat -c
# ... use the Feed screen for ~60 s ...
adb logcat -d -s ARTracker:D | grep -o "replay capture took [0-9]* us" | awk '{print $4}' | sort -n > /tmp/captures.txt
wc -l < /tmp/captures.txt   # sample count; want >= 50 (raise framesPerSecond to 4.0 in the sample if too few)
awk '{a[NR]=$1} END {printf "p50=%d us p95=%d us max=%d us\n", a[int(NR*0.50)], a[int(NR*0.95)], a[NR]}' /tmp/captures.txt
```

p95 must be <= 16000 us. The `FrameScheduler` watchdog already lowers the frame rate after three consecutive captures
over the 16 ms budget (`Replay watchdog: fps lowered to ...` in the same log), so also record whether that line appears.
For a jank view, run a Macrobenchmark scroll of the Feed with and without replay (`FrameTimingMetric`).

| Device | Samples | p50 (us) | p95 (us) | max (us) | Watchdog fired? |
| --- | --- | --- | --- | --- | --- |
| Emulator API 34 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| OPPO CPH2387, API 34, debug build (5 min of Feed scrolling in `ScreenshotBudgetTest`) | 490 | 3892 | **6196** (target <= 16000: met) | 51265 | no (no `fps lowered` line) |

## 3. Background CPU over 10 minutes (target: < 1 %, acceptance criterion #1)

Procedure: install the debug sample, log in, use the app for a minute so that a session and a replay are running, press
Home so the app goes to background, then leave the device idle and unplugged (screen off) for 10 minutes:

```sh
adb shell dumpsys cpuinfo | grep id.art.tracker        # at t = 0 (right after Home)
# wait 10 minutes
adb shell dumpsys cpuinfo | grep id.art.tracker        # at t = 10 min: the "Load: ... / x% id.art.tracker" line
adb shell dumpsys batterystats --charged id.art.tracker | head -40   # optional: CPU time of the uid
```

`dumpsys cpuinfo` reports the average over its own window (the last 1, 5 and 15 minutes in the `Load` line, plus a
per-process `x% pid/process: u% user + k% kernel` line), so read it at the end of the 10 minutes and do the
measurement twice: SDK enabled (the normal sample) and a baseline build of the sample with the `ARTracker.init` call
commented out, and compare the two. The SDK's share is the difference; both must be small and the SDK's must be < 1 %.

Perfetto expectation (10-minute trace in background: `adb shell perfetto -o /data/misc/perfetto-traces/bg.pftrace -t 10m
sched freq idle am wm -a id.art.tracker`, open it in ui.perfetto.dev):

- After the app goes to background there is no recurring timer on the app's main thread from the SDK (the replay flush
  tick, the frame scheduler tick and the touch wrappers are removed on `onAppBackground`) and no SDK network activity
  (the foreground flush timer is cancelled).
- The only SDK work is **one** WorkManager run (`artracker-flush`, `FlushWorker`, constraint `CONNECTED`) shortly after
  the app went to background, plus the background flush that `onBackground` itself triggers just before.
- The SDK has no thread with its own name: it runs on `DefaultDispatcher-worker-N` (kotlinx coroutines, shared with the
  host app), so look for CPU slices on those threads and on the WorkManager `androidx.work-*` thread after the first
  minute: there should be none attributable to the SDK. To attribute slices, enable callstack sampling in the trace
  config and search the samples for `id.art.tracker`.

| Build | Avg CPU 10 min (%) | Perfetto: SDK work in background |
| --- | --- | --- |
| Sample with SDK (OPPO CPH2387, API 34, debug) | **0.00** (0 CPU ticks in a 170 s window, tick = 10 ms); `dumpsys cpuinfo` over 12:27-12:32 does not list the process at all (the list goes down to 0.1 %) | NOT MEASURED (no Perfetto run) |
| Baseline (no `init`) | NOT RUN (the SDK build already shows ~0, so there is nothing to subtract) | n/a |
| **SDK share (difference)** | **< 0.1 %: target < 1 % met** | |

How this was measured (deviation from the procedure above, stated plainly): the sample was launched, left in the
foreground for 20 s, sent to the background with Home, the screen was turned off and left for 10 minutes; at the end the
`dumpsys cpuinfo` window (5 minutes, ending 108 s before the dump) did not list the app. For a number instead of an
absence, `/proc/<pid>/stat` (utime + stime) was read twice, 170 s apart, in the same state: 1723 ticks both times. The
10-minute window was therefore not read as one `x%` figure, and Perfetto was not run, so the "no recurring SDK timer" and
"one `artracker-flush` WorkManager run" expectations are **not verified by a trace**.

## 4. Release AAR size (MEASURED)

Measured on 2026-11-19 (SDK `1.0.4-SNAPSHOT`: code of `908a9ba` plus the `MAX_FIRST_CREATE_DELAY_MS` change in the commit that adds this table; harness `822597e`) with `bash scripts/measure-size.sh`. The same script and
definitions are meant to be copied by the iOS SDK so the two can be compared: raw artifact bytes, release APK delta with
and without the SDK (R8 on), and dex method ids added.

| Metric | Value |
| --- | --- |
| AAR bytes | 634428 |
| classes.jar bytes | 672037 |
| SDK dex methods | 3329 |
| Release APK bytes without SDK | 14569 |
| Release APK bytes with SDK | 258495 |
| APK delta bytes | 243926 |

The APK delta is a **lower bound**: the consumer app calls only `ARTracker.init` with `ReplayConfig(enabled = false)`, so R8 removes
what that app never reaches (event tracking, `identify`, flush, and probably screenshot replay). An app that uses more of the SDK pays more.
State "init only, replay off" next to this number when comparing it with iOS.

How the APK numbers are made: `scripts/consumer-check` is built as a minified release (R8 with the consumer rules packaged
in the AAR) twice, `-PsdkMode=none` and `-PsdkMode=full`, resolving the SDK only from a throw-away Maven repository. The
host app is empty apart from an `Application` that calls `ARTracker.init`, so the delta is the SDK plus the
libraries it brings (WorkManager, lifecycle, coroutines, core-ktx) minus what R8 removes. A real app that already uses
those libraries pays less. The AAR itself is not minified (library modules ship the compiled classes; the host app's R8
shrinks it).

## 5. Method count (MEASURED)

`scripts/measure-size.sh` does this itself; the manual equivalent:

```sh
unzip -o artracker-sdk/build/dist/artracker-android-<version>.aar classes.jar -d /tmp/aar
$ANDROID_HOME/build-tools/<version>/d8 --lib $ANDROID_HOME/platforms/android-<api>/android.jar --output /tmp/aar-dex /tmp/aar/classes.jar
$ANDROID_HOME/build-tools/<version>/dexdump -f /tmp/aar-dex/classes.dex > /tmp/aar-dex/dump.txt && grep method_ids_size /tmp/aar-dex/dump.txt
```

| Metric | Value |
| --- | --- |
| Methods in the SDK dex (before the host app's R8) | **3329** method ids, d8 of `classes.jar` from the same release AAR, default min-api (build-tools 37.0.0; the default min-api adds desugaring helper methods, so state that definition when comparing with iOS; the unresolved `okhttp3` / `androidx.work` desugaring warnings are expected: they are compileOnly / separate libraries) |

## Not measured here (summary)

- Everything in the "Emulator API 34" columns (the AVD here is API 30 only).
- Perfetto traces of any kind (sections 3 and profiling): no trace was recorded, so attribution is by CPU ticks and logs only.
- A 10-minute background window read as one `dumpsys cpuinfo` figure, and a baseline build without `init` (section 3).
- A release-build (R8, baseline profile) `init` measurement (section 1).
- `app_start` against `am start -W` on the device: the sample sent no `app_start` in 5 cold starts, see "app_start on the
  device" below; this OPPO also reports `LaunchState: UNKNOWN` and a constant `WaitTime` of about 3.04 s, so `am start -W` is not a usable reference here.
- Rage tap on the device (6 `input tap` in one shell did not leave a chunk row to inspect, and a `has_rage_click` chunk goes to the
  replay endpoint, which the stand-in ingest does not serve): covered by the unit tests only.
- API 24-29 ANR watchdog, API 34 memory warning, backend E2E (needs Docker), playback of replay chunks in the dashboard.

## 4. Screenshot replay

Targets: **<= 25 MB of chunks for 5 minutes of continuous scrolling** at `STANDARD`, 2 fps (the governor aims at <= 5 MB
per minute), and host-app jank (`FrameMetrics`, frames > 16 ms) up **<= 2 percentage points** versus replay disabled.
Not yet measured on a device: the table is empty until someone runs it.

Procedure: sample app, debug build, `ReplayConfig(enabled = true, sampleRate = 1.0, framesPerSecond = 2.0,
quality = ReplayQuality.STANDARD)` against a backend that accepts screenshot chunks. Scroll the Feed for 5 minutes, then sum the queued/uploaded chunk bytes (the `size_bytes` of
the `chunk-url` calls, or the files in `files/artracker/replay/` when offline). For the jank figure run a Macrobenchmark
scroll of the Feed with and without replay (`FrameTimingMetric`, `frameOverrunMs`) and compare the share of frames over 16 ms.
`adb logcat -s ARTracker:D` shows `replay capture took ... us` per capture (main-thread cost, budget 16 ms in screenshot
mode) and `Replay watchdog: fps lowered to ...` when the watchdog fires.

| Device / API | Quality | Minutes | Total chunk MB | Jank delta (pp) | Watchdog fired? |
| --- | --- | --- | --- | --- | --- |
| OPPO CPH2387, API 34, 60 Hz, debug build, `ScreenshotBudgetTest` | STANDARD, 2 fps | 5 | **16.91** (457 frames; limit 25) | **-10.74** (baseline 76.98 %, 13000/16888 frames over 16 ms; with replay 66.24 %, 11443/17275) | not recorded |
| same, second run (Phase 4, `ec9ace3`) | STANDARD, 2 fps | 5 | **16.95** (457 frames; limit 25) | **-1.60** (baseline 76.94 %, 12821/16664; with replay 75.33 %, 12760/16938) | no (`fps lowered` absent from the log) |

The two runs agree on data (16.91 and 16.95 MB) and disagree on the jank delta (-10.74 and -1.60 points): both are far
below the +2 limit, but the spread between them shows the metric is dominated by noise here, not by replay.

Read of the result:

- **Data budget: met.** 16.91 MB in 5 minutes of continuous scrolling, inside the 25 MB limit (the governor targets 5 MB
  per minute).
- **Jank: not worse with replay (limit +2 points), with a caveat.** The comparison is the share of frames whose
  `FrameMetrics.TOTAL_DURATION` exceeds 16 ms, same scrolling with replay stopped and then with screenshot replay. On
  this 60 Hz debug build that metric is saturated even without replay (77 % of frames), because the pipelined total
  duration of a frame is often above one 16.7 ms period; so the number shows "no measurable extra cost", not a
  release-build jank rate. A sharper run would count main-thread work only (layout + draw + animation durations) on a
  release-like build. The one-minute run before this one gave 4.09 MB.
- Not done here: the manual checklist of `docs/e2e-manual-test.md` section 6b (fonts, icons, photos, dialogs, bottom sheets
  against a screen recording) and playing a recording in the dashboard.

Reproduce: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, `adb install -r -t` both APKs, then
`adb shell am instrument -w -r -e class id.art.tracker.e2e.ScreenshotBudgetTest -e durationMs 300000 id.art.tracker.test/id.art.tracker.PlaygroundTestRunner`
(it prints a `budget_report` line with the uploaded MB and both jank figures).

## 4. Session profiling overhead (target: average CPU differs by <= 1 % of one core)

Status: **the target is MET on a physical device (+0.04 points, Phase 4, below); on the emulator it was NOT MET (+3.27) and
the cause of that emulator figure is still undetermined, see "Physical device run".**
The numbers below are an emulator on a laptop host, which is noisy and not a substitute for the device run the plan asks
for. Do not lower the target: repeat on a physical device before release, and treat a miss there as an issue.

What is being measured: the cost of `PerformanceConfig(enabled = true)` in the foreground (one `/proc` read per
interval, the frame-metrics callbacks, a coroutine wake-up, and the extra `perf.samples` items that are persisted, gzipped
and sent). The sample app turns it on with its debug toggle (`performance = PerformanceConfig(enabled = debug)`).

Procedure used (what was actually run): `Medium_Phone` AVD (API 30, x86, 4 cores), debug build of the sample app on its
default SINK endpoint (local on the device), the app idle in the foreground on `MainActivity` with the screen kept on,
session replay on in every run (it is the sample's default, so it is part of the baseline). Each run: force-stop, launch,
30 s warm-up, then `adb shell top -b -d 5 -n 60 -p <pid>` (about 5 minutes); the first `top` iteration (since process
start) is dropped, leaving 59 samples; the figure is the mean `%CPU` of one core. Deviation from the plan: `top` was used
for BOTH sides instead of the SDK's own `cpu` samples for the "on" side, so that the two sides are measured the same way.
"Off" was a temporary local build with `performance = PerformanceConfig(enabled = false)`; it was not committed.

| Run | Config | Mean CPU (% of one core) | Max over the run |
| --- | --- | --- | --- |
| A1 | perf on, frames on | 6.58 | 20.2 |
| B1 | perf off | 3.33 | 26.0 |
| B2 | perf off | 2.80 | 7.8 |
| A2 | perf on, frames on | 6.09 | 13.8 |
| C | perf on, `trackFrames = false` | 5.59 | 14.4 |

Reading: perf on averaged 6.34 %, perf off 3.07 %, a difference of about **3.3 % of one core, above the 1 % target**.
The two pairs agree with each other (3.25 and 3.29), so it is not just one noisy run, but each configuration was run
twice and the emulator is shared with the host. With frame tracking off (C) the cost only drops to 5.59 %, so the frame
callbacks are not the main part (about 0.75 points, within the run-to-run spread). The cause of the rest is **not
determined**. Candidates, none verified: the extra items per interval going through the persistence, gzip and send path
(10 samples per item, one flush every 10 s); the battery and thermal binder calls on the emulator; one-off JIT of the new
code. A profile (Perfetto or a method trace) of a physical device run would settle it.

| Device / API | Run A: profiling on (% of one core) | Run B: profiling off (% of one core) | Difference |
| --- | --- | --- | --- |
| Emulator, API 30 (2 runs each) | 6.34 | 3.07 | **+3.27, target missed** |
| OPPO CPH2387, API 34 (2 runs each, see below) | 1.72 | 1.68 | **+0.04, target met** |

### Physical device run (Phase 4)

Procedure: debug sample at `ec9ace3`; two APKs differing only in `PerformanceConfig(enabled = ...)` in
`PlaygroundSettings.toConfig` (the "off" APK was a temporary local edit, reverted, not committed); profile `LOCAL` against
`scripts/mock-ingest.py` through `adb reverse`; data wiped before each run; the sample idle in the foreground on its home
screen, replay on (the sample default) in both; 30 s warm-up, then 300 s; CPU = `utime + stime` ticks of the process from
`/proc/<pid>/stat` before and after (the same pid at both reads) divided by seconds, so both sides are measured the same way, % of one core;
`KEYCODE_WAKEUP` every 30 s to keep the screen on. Order A B A B, to spread drift.

| Run | Config | Ticks (10 ms) in about 300 s | Mean CPU (% of one core) |
| --- | --- | --- | --- |
| A1 | perf on | 510 | 1.694 |
| B1 | perf off | 514 | 1.701 |
| A2 | perf on | 527 | 1.750 |
| B2 | perf off | 499 | 1.657 |

Reading: on = 1.722 %, off = 1.679 %, a difference of **+0.043 points, inside the run-to-run spread (the two "off" runs
alone differ by 0.044)**. That is well below the 1 % target. The "on" side did record profiling: the stand-in ingest
received `perf.samples` items only after the "on" runs (1 after A1, none new after B1, 2 after A2, none new after B2).
So the +3.3 % seen on the emulator does **not** reproduce on the device; the emulator figure was probably the emulator
(shared host, x86 JIT, binder calls on a virtual battery), but that was not verified, and it was not profiled, so its
cause remains undetermined. Not run: Perfetto, `trackFrames = false` on the device, a release build.

### `app_start` on the device

Five cold starts of the debug sample sent `screen_view`, `app_foreground`, `app_background` and `perf.samples` but **no
`app_start`**. The log shows why: process start 13:07:45.4, `init` ran at 13:07:48.0 (a 2.6 s gap before `Application.onCreate`:
debug, non-AOT, ColorOS), the first Activity was created at 13:07:49.5, 1.45 s after `init`, over
`AppStartTracker.MAX_FIRST_CREATE_DELAY_MS` (1000 ms), so the tracker finished without reporting, by design. On a slow
device or debug build with a long gap between `init` and the first Activity the event is never sent. A later check
on an API 30 emulator showed the same guard dropping 2 to 3 of 5 launches (first Activity 1.1 to 6 s after `init`). Two environments
failing on the one guard made 1 s too tight, so `MAX_FIRST_CREATE_DELAY_MS` is now **10 s** (test
`aSlowDeviceFirstActivityAfterInit_isStillACountedColdStart`). The foreground check at `init` still excludes pushes, services and
WorkManager. Every silent drop now logs why (`app_start not reported: ...`, debug logging). `app_start` against a trusted launch
time, and a rerun of the five launches on the physical device with the new threshold, stay **unverified**. (Profiling overhead on the device: see "App start profiling overhead".)

## App start profiling overhead

`AppStartConfig(profiling = true)` samples the main thread stack from `init` to the first frame (`StartupProfiler`). Targets:
the mean cost of one sample <= 500 us, and the median `am start -W` `TotalTime` with profiling on within 5 % of the median
with it off.

**Device:** Samsung SM-S921B, Android 17 (API 37), charging at 77-92 %, 28 degrees C. **Build:** release variant of the sample
app (`isMinifyEnabled = false`, signed with the debug key to install it), the SDK at this commit. Both runs have SDK debug
logging on, to read the profiler's own log line, and the sample's default settings (replay and performance monitoring on,
sink/offline endpoint profile, so nothing leaves the device); the two builds differ **only** in `profiling` (`false` / `true`,
`profileSampleRate = 1.0`). One warm-up launch, then 10 cold starts each, `adb shell am force-stop id.art.tracker`, 3 s
pause, `adb shell am start -W -n id.art.tracker/.MainActivity`.

| Run | `TotalTime` per launch (ms) | Median | Min / max |
| --- | --- | --- | --- |
| Profiling off | 210, 206, 227, 204, 198, 205, 206, 193, 208, 223 | **206 ms** | 193 / 227 |
| Profiling on | 200, 188, 193, 208, 211, 205, 199, 205, 200, 204 | **202 ms** | 188 / 211 |

The median differs by -4 ms (-1.9 %): within the 5 % target, and below the run-to-run spread of either set (about 30 ms), so
this measurement cannot tell the profiler from noise; it shows no cost large enough to see in 10 launches.

The profiler's own log line (`startup profile: N samples, interval X ms, mean cost Y us`) for the 10 launches with profiling on:

| Launch | Samples | Final interval | Mean cost per sample |
| --- | --- | --- | --- |
| 1 | 12 | 10 ms | 507 us |
| 2 | 8 | 20 ms | 1563 us |
| 3 | 12 | 10 ms | 411 us |
| 4 | 13 | 10 ms | 203 us |
| 5 | 8 | 20 ms | 1598 us |
| 6 | 13 | 10 ms | 186 us |
| 7 | 12 | 10 ms | 237 us |
| 8 | 12 | 10 ms | 485 us |
| 9 | 12 | 10 ms | 246 us |
| 10 | 12 | 10 ms | 339 us |

**The mean-cost acceptance criterion was NOT met.** The plan's protocol is one run of 10 cold starts with the mean cost
<= 500 us; run 1 above gave **578 us** (median launch 375 us, 7 of 10 launches under 500 us). Two launches (2 and 5) cost about
1.6 ms per sample, over the 1 ms back-off threshold, so the interval doubled to 20 ms for them (the adaptive interval doing
what it is for). The median `TotalTime` criterion (<= 5 %) was met: -1.9 %.

Because a launch takes only 8 to 13 samples, one slow read weighs a lot in a launch mean, so the same build was launched 20
more times (same device and steps, no code change). This was done **after** the miss and is supplementary evidence, not a
replacement for the protocol; pooling runs is not what the plan defines as acceptance.

| Run | Mean of launch means | Median launch | Launches under 500 us | Launches over 1 ms |
| --- | --- | --- | --- | --- |
| 1 (the protocol) | 578 us | 375 us | 7 / 10 | 2 (1563, 1598 us) |
| 2 (after the miss) | 440 us | 277 us | 9 / 10 | 1 (1993 us) |
| 3 (after the miss) | 278 us | 235 us | 9 / 10 | 0 |
| Runs 1-3 pooled (30 launches) | 432 us | 265 us | 25 / 30 | 3 |

The spread says the number is dominated by rare slow reads (3 of 30 launches cost 1.6 to 2 ms per sample: a
`Thread.getStackTrace` of the main thread while it was busy class-loading, a CPU frequency step; not investigated), and one
run alone ranges from 278 to 578 us. The sampler was **accepted with the criterion unmet**: the repository owner was asked
and chose "accept as is" (a waiver of the plan's criterion, 2026-11-19; the plan itself was not changed). Reasons: profiling is opt-in (`profiling = false` by default), the typical launch is well under
500 us, and the end-to-end `TotalTime` cost is not measurable in 10 launches. Whoever owns the target can change it,
re-measure on a mid-range device, or tune the sampler. Not measured: a mid-range or low-end device (slow reads would be more
frequent there), profiling with a longer startup (a splash, a heavy `Application.onCreate`), Perfetto, the cost of the
profile upload.

**Where the cost comes from (investigated after the miss, same device).** A temporary build logged the cost of every single
read (not committed). Over 15 launches: 209 samples, mean 353 us, median 227 us; **13 samples (6.2 %) cost 1 to 3.3 ms and
make up 34 % of the total cost**; without them the mean is 248 us. The slow reads are isolated (only 2 pairs in a row, 0 to 3
per launch), so the mean of a 13-sample launch swings with whether one landed in it. Two levers were considered:

- *Back off after one slow read.* Not built: with isolated spikes it would mostly take fewer cheap samples, which raises
  the mean per sample (reasoning from the distribution, not measured).
- *A higher priority for the sampler thread* (`THREAD_PRIORITY_FOREGROUND`, to cut wake-up latency). Built and measured, then
  **removed**: in an interleaved A/B of 20 launches per arm, reads of 1 ms or more were 7.0 % of samples with it against 3.5 %
  without, the worst read 10.7 ms against 2.8 ms, and the mean 364 us against 223 us per sample.

No code change was made: the spikes look like thread-suspension latency of the platform, which the sampler cannot control.
These investigation builds log one line per sample (outside the timed region) and are not the acceptance protocol; their
unmodified-sampler results (mean of launch means 349 us over 15 launches, 307 us over the 20 baseline launches of the A/B)
are context, like runs 2 and 3, not a substitute for run 1.

To repeat it: build the sample twice (release, `signingConfig` set to the debug key, `AppStartConfig(profiling = false)` and
`AppStartConfig(profiling = true, profileSampleRate = 1.0)`), then for each build

```
adb install -r app-release.apk
adb shell am force-stop id.art.tracker && adb shell am start -W -n id.art.tracker/.MainActivity   # warm-up
adb logcat -c
for i in $(seq 10); do adb shell am force-stop id.art.tracker; sleep 3; adb shell am start -W -n id.art.tracker/.MainActivity | grep TotalTime; sleep 3; done
adb logcat -d -s ARTracker:V | grep "startup profile"    # needs debug = true
```

## Device context fields

The samples carry `ram_free_mb`, `storage_free_mb` and `net`, and the `device` block carries `os_version` and `storage_mb`.
Reading them costs one `ActivityManager.getMemoryInfo`, one `StatFs` and a volatile read per tick on the sampling
scope; the network type comes from a callback, so no `ConnectivityManager` call is made per sample. This overhead is
NOT measured separately: the section 4 measurement predates these fields.

## 6. Replay idle lifecycle (Active / Idle / Dormant), 30 min untouched

Status (2026-11-16): **measured once on the emulator (API 30, x86), debug build of the sample at commit `6ec62a8`**
plus the docs change; NOT on a physical device, and there is **no "before" run** of the old build, so no before/after
comparison exists here (the spec's "up to 1 800 renders" for the old behavior is its estimate, not a measurement).

Procedure: `:app:installDebug`, sample app (replay `enabled = true`, `sampleRate = 1.0`, 2 fps, `STANDARD`, profile `SINK`),
Lab -> Replay -> switch "Animasi" on (an infinite Compose animation, the carousel worst case), the switch tap was the
last touch (14:25:28), device kept awake (`svc power stayon true`), nothing touched for 31 min, then
`adb logcat -d -s ARTracker:D` and one more touch.

| Metric | Target | Measured |
| --- | --- | --- |
| Renders (`replay capture took` lines) in 31 min | <= 90 | **37** |
| Last render | at most ~210 s after the last touch | 14:28:21, i.e. 2 min 53 s after it (DORMANT starts at 3 min) |
| Renders between dormancy and the next touch (about 28 min) | 0 | **0** |
| Chunks uploaded | <= 20 | **NOT verified.** The sink summary (button "Ringkasan screenshot", shown as a toast) needs a touch, which wakes capture, so it cannot be read at the 31 min mark without disturbing it. Second run (below): 24 chunks / 96 frames at read time, but that includes about 4 minutes of my own scrolling before the read, so it neither proves nor disproves <= 20 |
| Session after the touch at 31 min | new session | **PASS**: header `Sesi 01a1e30e` before, `Sesi 01a1e35b` after (rotation on user input) |

Notes: on the emulator one capture took 280-470 ms against the 16 ms budget, so the watchdog had already lowered the
rate to 0.5 fps in the first seconds (the watchdog lines are in the log); the 37 renders therefore include that
slowdown and a device would render more in the Active 30 s. The 30 min animation was never replayed in a viewer.
The unit test `carouselIdle_stopsCapturingAfterDormant` simulates the same 30 min on a fake clock (no frame after dormancy).
`session_end` / `session_start` were not read from the wire: the rotation is read from the id shown by the sample.

Second run (emulator API 30 again, final code at `93f5068`, which adds keyboard / hardware-key input to the capture
lifecycle): 59 renders (the log is cleared at setup, so it includes about 50 s of setup), last render 2 min 52 s after the
animation was switched on, none afterwards, and the session id changed after the touch (`01a1e71e` -> `01a1e73b`). The first
read of the sink summary was 1 chunk / 6 frames (right after setup). A third run meant to read the chunk count with a
single quick gesture was started and then **abandoned** (it takes 31 min per run), and a "before" run of `main` was **never
taken**, so the plan's before/after table is not complete. Both are open.
