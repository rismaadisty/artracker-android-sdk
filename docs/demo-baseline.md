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
