# Demo app baseline

Recorded on 2026-11-07 on branch `feature/demo-playground` (from `feature/android-sdk` at 922c64a).

## Automated suite (before any playground change)

Command: `./gradlew :artracker-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`

| Module | Tests | Skipped | Failures | Errors |
| --- | --- | --- | --- | --- |
| `:artracker-sdk` | 654 | 0 | 0 | 0 |
| `:app` | 5 | 0 | 0 | 0 |

`BUILD SUCCESSFUL`. The SDK test count above (654) is the reference for "SDK unchanged" in Task 15.

## Device baseline

**NOT run.** `adb devices` lists no device, and this machine has no Android system image or AVD
(`~/Library/Android/sdk/system-images` does not exist). Consequences:

- Nothing in this baseline proves the existing app works on a device. README's status table still applies.
- The `local.properties` endpoint was not neutralised because the app was never launched. Never launch the
  pre-playground build with the production endpoint in `local.properties`: it sends to that host on every start.
- Device-only verification steps of the playground plan (Tasks 2, 3, 5, 6, 8, 9, 10, 11, 12, 13 on-device steps, the
  instrumented suite of Task 14, and the manual pass of Task 15) are deferred until a device or emulator exists.

## Final results (after the playground)

Command: `./gradlew :artracker-sdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`

| Module | Tests | Skipped | Failures | Errors |
| --- | --- | --- | --- | --- |
| `:artracker-sdk` | 654 (unchanged from the baseline above) | 0 | 0 | 0 |
| `:app` | 238 | 0 | 0 | 0 |

`BUILD SUCCESSFUL`. Safety checks: `git diff feature/android-sdk -- artracker-sdk` is empty; `releaseRuntimeClasspath` contains
no `mockwebserver`; no app source or doc mentions an external HTTP echo service (the one match is the test that guards it);
the real endpoint/key `BuildConfig` fields are read only by `SampleApplication`/`Bootstrap` (boot) and `PlaygroundHost` (the
Settings screen's classification and "key present" flag; the key value is never displayed or logged).

## Not verified (no device or emulator was available)

- `:app:connectedDebugAndroidTest` (`IngestFlowTest`, `ScenarioFlowTest`, `TouchFlowTest`, `SafetyTest`) compiles and packages
  but was **never run**. This includes the proof that a synthetic tap is recorded as touchstart/touchend by the SDK's real
  `TouchRecorder`, that the SDK's private file names match `QueueWiper`, and the Retry-After flow.
- Every "run on a device" step of Tasks 2, 3, 5, 6, 8, 9, 10, 11, 12, 13 and the manual pass of Task 15, including playback of an
  exported replay in `tools/replay-viewer/index.html`, and the restart through `RestartActivity` (`:restart` process).
- Performance on a real device (composing the 2955-node tree, replay walk cost).
