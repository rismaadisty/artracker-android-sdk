# Performance measurement notes (Task 25)

Status: **only the AAR size has been measured.** Every other result below is **NOT MEASURED**: it needs an emulator or a
device, and the machine this was prepared on has neither. Nothing here is an estimate. Fill the tables in on a device
and keep the targets as they are: if one is missed, record the real number and the cause as an issue instead of
lowering the target.

Targets come from the issue #12 acceptance criteria and `ARCHITECTURE.md` section 1 / 6.

## What was added for measuring

Both hooks only run when `ARTrackerConfig.debug = true` (they are behind `SdkLog.enabled`; with `debug = false` the
cost is one volatile boolean read, no allocation, no log call). The sample app is built with `debug = true`.

| Log line (tag `ARTracker`, priority D) | Where | Meaning |
| --- | --- | --- |
| `init synchronous part took <N> us on thread <name>` | `ARTracker.init`, after the object graph is built and `start()` returned | Everything `init` does on the calling thread (config validation, `ARTrackerCore` construction incl. the `SharedPreferences` handles, crash handler install, lifecycle registration). The database, the purge, crash recovery and the first flush are on the background thread and are not included, by design. `thread main` confirms it ran on the main thread. |
| `replay capture took <N> us (budget 8 ms, fps <f>)` | `FrameScheduler`, after each capture that did not throw | Wall time of one capture on the main thread: `RootViewLocator` + `ViewWalker` for every root (the capture callback). The diff / encode / chunk work happens on a background thread and is not part of this number. |

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

| Run | Emulator API 34 (us) | Mid-range device (us) |
| --- | --- | --- |
| 1 | NOT MEASURED | NOT MEASURED |
| 2 | NOT MEASURED | NOT MEASURED |
| 3 | NOT MEASURED | NOT MEASURED |
| 4 | NOT MEASURED | NOT MEASURED |
| 5 | NOT MEASURED | NOT MEASURED |
| **Median** | **NOT MEASURED** | **NOT MEASURED** |

## 2. Replay walk on the Feed screen (target: p95 <= 8 ms)

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

p95 must be <= 8000 us. The `FrameScheduler` watchdog already lowers the frame rate after three consecutive captures
over the 8 ms budget (`Replay watchdog: fps lowered to ...` in the same log), so also record whether that line appears.
For a jank view, run a Macrobenchmark scroll of the Feed with and without replay (`FrameTimingMetric`).

| Device | Samples | p50 (us) | p95 (us) | max (us) | Watchdog fired? |
| --- | --- | --- | --- | --- | --- |
| Emulator API 34 | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |
| Mid-range device | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED | NOT MEASURED |

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
| Sample with SDK | NOT MEASURED | NOT MEASURED |
| Baseline (no `init`) | NOT MEASURED | n/a |
| **SDK share (difference)** | **NOT MEASURED** | |

## 4. Release AAR size (MEASURED)

Measured on 2026-11-06 with `./gradlew :artracker-sdk:assembleRelease` and `ls -l artracker-sdk/build/outputs/aar/`:

| Artifact | Bytes |
| --- | --- |
| `artracker-sdk-release.aar` | **398621** (about 389 KiB), final 0.1.0 build after the last review fixes |
| `classes.jar` inside it (uncompressed) | 419951 |

The AAR is not minified (library modules ship the compiled classes; the host app's R8 shrinks it). The 0.1.0 size is recorded in
`artracker-sdk/CHANGELOG.md`.

## 5. Method count (optional, NOT MEASURED)

```sh
unzip -o artracker-sdk/build/outputs/aar/artracker-sdk-release.aar classes.jar -d /tmp/aar
$ANDROID_HOME/build-tools/<version>/d8 --lib $ANDROID_HOME/platforms/android-36/android.jar --output /tmp/aar-dex /tmp/aar/classes.jar
# then count methods in /tmp/aar-dex/classes.dex, e.g. with: dexdump -f /tmp/aar-dex/classes.dex | grep method_ids_size
```

| Metric | Value |
| --- | --- |
| Methods in the SDK dex (before the host app's R8) | NOT MEASURED |

## Not measured here (summary)

- `init` main-thread cost (section 1).
- Replay walk p95 on the Feed screen (section 2).
- Background CPU over 10 minutes and the Perfetto check (section 3), which is acceptance criterion #1.
- Method count (section 5).
- Playback of the exported chunks in `rrweb-player` (`tools/replay-viewer/index.html`); only the structure was checked,
  by `validate-chunks.mjs`.
