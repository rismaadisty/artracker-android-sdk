# Manual end-to-end test: sample app against a local backend

> **Update (playground):** the sample app now starts on a loopback **sink** (profile `SINK`), not on the endpoint in
> `local.properties`. To follow this checklist against a real backend, use Settings: profile `LOCAL` (local backend, uses the
> key from `local.properties`) or `BACKEND` (remote; needs the typed confirmation and lasts one process start).
> Most of sections 3, 6 and 7 are now covered by automated tests: see `app/src/androidTest` (`IngestFlowTest`,
> `ScenarioFlowTest`, `TouchFlowTest`, `SafetyTest`), and the Inspector/Replay-lab export for `tools/replay-viewer`.
> Mapping: section 3 -> Event/Network labs and scenario `ecommerce_checkout`; 4 -> Crash lab (use `OFFLINE`); 5 -> Crash lab
> ANR; 6 -> Privacy lab, Replay lab and the chunk export; 7 -> Identity lab (opt-out switch) and Replay lab.
> **Always manual:** the 401 kill-switch (it persists for an hour and would poison automated runs), the offline crash then
> relaunch, and playback of replay chunks in the dashboard.

Status (Phase 4, 2026-11-16): **partly run on a physical device** (OPPO CPH2387, Android 14), **not run against the real backend**
(no Docker here). The whole checklist below still needs a backend; what was run instead, and what was not:

| Item | Result |
| --- | --- |
| Offline crash then relaunch (acceptance criterion #2, section 4) | **PASS, 5 of 5 runs**, `scripts/e2e-crash-offline.sh SERIAL` (6-7 s each): crash on the `OFFLINE` profile (debug-only intent extra `artracker.crash_now`, delivered with `am start --activity-single-top`), crash file on disk, nothing reached the stand-in ingest, then the relaunch on `LOCAL` delivered a `crash` item with level `fatal`. The ingest is `scripts/mock-ingest.py`, not the real backend, so server-side storage and the dashboard are untested. |
| Instrumented `IngestFlowTest` (redaction, 503 `Retry-After` retry) | **2 of 2 pass** after two test-only fixes. Both tests failed identically on the pre-Phase-1 baseline `feaddeb` (checked in a temporary worktree), so these were not regressions: the redaction test now uses the deny-listed key `card` in a `payment_note` value, and the retry test waits for an empty queue inside a live Activity before it arms the 503. |
| Screenshot budget, 5 min (section 6b data part) | PASS: 16.95 MB of chunks (limit 25), jank delta -1.60 points (limit +2); details in `artracker-sdk/PERF.md`. |
| 401 kill-switch (section 3) | **PASS on the device** (`scripts/e2e-killswitch-401.sh SERIAL`, one run): the stand-in ingest answers 401 to every POST; the SDK sent exactly 1 POST, wrote `disabled_until` about 3600 s ahead, sent nothing more in the following 75 s, and a force-stop + relaunch also sent nothing (the trip is persisted). Step 3's foreground/background round trip was weak in that run (`am start` was delivered to the already-running Activity), step 4 is the stronger check; no negative control (same script against a 202 ingest) was run. Not tested: the real backend's 401 body, and the one-hour expiry. |
| ANR (section 5), API 24-29 watchdog | **NOT run**: no API 24-29 device; the API 34 ANR path was not exercised either. |
| Replay checklist 6b (fonts, icons, photos, dialogs against a recording), playback in the dashboard | **NOT run.** |
| Backend E2E (sections 1-3, 7) | **NOT run**: needs Docker and the `artracker` backend repo. |

Device notes for whoever repeats this: ColorOS refuses `pm clear` to adb (the scripts wipe data through `run-as`), the
process can linger after a crash, and a screen that timed out pauses the Activity, so the scripts send `KEYCODE_WAKEUP` first.

Prerequisites: Docker, an Android emulator (API 30+ for the ANR step), the `artracker` backend repo next to this one.

## 1. Start the backend and create a key

1. `cd ../artracker && docker compose up -d`
2. Log in to the dashboard, create a project and a public key of the form `art_live_...`.
3. Note the project id and the backend port.

## 2. Configure and install the sample

1. Add to the ROOT `local.properties` of this repo (never commit it). They are only used by profiles `LOCAL` and `BACKEND`:
   ```
   artracker.endpoint=http://10.0.2.2:<port>
   artracker.key=art_live_...
   ```
2. `./gradlew :app:installDebug` on the emulator. (Cleartext to `10.0.2.2` and `localhost` is allowed in debug builds only.)
3. Open *Pengaturan*, choose profile `LOCAL`, press *Terapkan & restart*.

## 3. Events, logs and screens

1. Open the app, go to Login and press *Log in*, go to Payment and press *Pay now*, then open Diagnostics and press *Track event*, *Log WARN*, *HTTP 200*, *HTTP 404*, *HTTP timeout (3 s)*, *Flush*.
2. Query: `GET /api/v1/projects/:id/events?kind=all&limit=50` with the **dashboard login JWT**:
   `Authorization: Bearer <access token>`. This is a dashboard endpoint: the `art_live_...` ingest key (the
   `X-Artracker-Key` header) is rejected there, so do not reuse it. The backend has OAuth login only (there is no
   password login route). To get the token: log in to the dashboard through OAuth, open the browser dev tools, and copy
   the access token from the `Authorization: Bearer ...` header of any dashboard API request in the Network tab.
   Alternatively, `POST /api/v1/auth/refresh`, sent with the refresh cookie that the OAuth callback set (the callback
   itself only sets the cookie and redirects to `/dashboard`), returns JSON with `access_token` and `expires_at`. Access
   tokens expire; copy a fresh one if you get `401`.
   ```sh
   curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:<port>/api/v1/projects/<id>/events?kind=all&limit=50"
   ```
3. Expect `session_start`, `login_clicked`, `checkout_clicked` (with `total` 120000), `screen_view` items (home, login, payment, diagnostics), and `http_request` items. The 404 one has level `warn`.

## 4. Offline crash recovery (acceptance criterion #2)

1. Enable airplane mode.
2. Diagnostics > *Crash now* (the app dies with `RuntimeException: Sample crash`).
3. Relaunch the app, then disable airplane mode.
4. Within 60 s a `log` item with level `fatal` and name `crash` appears in the events API.

## 5. ANR (API 30+)

1. Diagnostics > *ANR 10 s*; when the system dialog appears choose *Close app*.
2. Relaunch the app.
3. An `anr` item appears in the events API.

## 6. Session replay and password privacy

1. Replay is on by default in the sample (`sampleRate = 1.0`); use Diagnostics > *Start replay* if it was stopped.
2. Open Login, type an email and a password (for example `hunter2-secret`), open Payment, type digits into card/CVV, then Flush.
3. Confirm `replay_sessions` and `replay_chunks` rows exist for the session (replay no longer travels as `replay_chunk` ingest items).
4. Fetch the events JSON and grep it for the typed secrets, for example `curl -H "Authorization: Bearer $TOKEN" ... | grep -c 'hunter2-secret'`. Expected: `0`. Card digits and the payment total must not appear either (the total is masked by `Modifier.artrackerMask()`).
5. Open the Legacy XML screen and type into the password and number fields; the tagged containers (`artracker-mask`) must be masked in the uploaded replay too.
6. A focused Compose text field must not mask its whole screen. Open a Compose screen with a text field (Login), tap the email field so it has focus and the keyboard is open, type a few characters and wait several seconds. In the replay frames recorded while the field had focus, only the text field (and other sensitive nodes) shows the grey cross-stripe pattern; labels, buttons and the rest of the page stay visible. Before the fix, every frame taken while a Compose field had focus was one grey block over the whole Compose host.
7. Popups inherit masking. In the Privacy lab open `popup_window`, `dropdown_menu` and `view_autocomplete_masked` (type `f` in the last one): the whole popup shows as the grey cross-stripe pattern in the replay frames. The overflow menu of a toolbar and a bottom sheet on the Payment screen stay visible. Note: the sample app resolves Compose UI 1.9.0 at runtime (a transitive constraint, not the 1.7.2 of its BOM), so this run says nothing about Compose 1.7; the 1.7 fallback of the `ContentType` rules is covered by unit tests only.

### 6b. Screenshot replay

1. The sample app records screenshots (quality `STANDARD`, 2 fps). In Settings pick `HIGH` for this checklist and set `replayFps` to `4`, then tap *Terapkan & restart*. Use a real device (API 26+). The backend must accept screenshot chunks; the local SINK profile does, and its Replay lab can make it answer `403 screenshot_disabled` (*Sink menerima screenshot*), *Ringkasan screenshot* shows what it received and *Ekspor screenshot* writes the last frames as JPEG files to the app's files folder. The code equivalent is `ReplayConfig(quality = ReplayQuality.HIGH, framesPerSecond = 4.0)`.
2. Use Login, Feed, Payment, a dialog and a bottom sheet for a minute, background the app and open the session in the dashboard. Compare it with a device screen recording: fonts, icons, photos, colors, dialogs, bottom sheets and system-bar colors should match; navigation transitions and scrolling should show as motion at 4 fps. The keyboard shows as a grey "keyboard" band.
3. Password and number fields, `artrackerMask()` areas and a WebView must show the grey cross-stripe pattern, and no typed secret may be readable in any frame. A screen with `FLAG_SECURE` must be fully masked.
4. Switch the project's screenshot replay off on the server (`403 screenshot_disabled` on `chunk-url`): the running session must stop recording (no wireframe fallback exists), the queued chunks must disappear, and the SDK must keep sending ordinary telemetry (no kill switch).
5. Machine-checked part of steps 2-3 (run on a device, passed on an OPPO CPH2387, API 34): `id.art.tracker.e2e.ScreenshotScreensTest` compares an uploaded frame with `UiAutomation.takeScreenshot()` for fonts (bold, italic monospace, serif), vector icons, a gradient picture, colors and a dialog with its dim (8/255, 93 % of grid samples); `ScreenshotFlowTest` does the same for flat colors plus a masked band. What stays for a person: motion at 4 fps, bottom sheets, real photos and the dashboard itself. Run with `adb shell am instrument -w -r -e class id.art.tracker.e2e.ScreenshotScreensTest id.art.tracker.test/id.art.tracker.PlaygroundTestRunner` (frames are left in the app's external files folder as `screens-*-frame.png` and `screens-*-device.png`).
6. On API 24-25 expect the `View.draw` path: no `SurfaceView`/video content, everything else the same.

## 7. Extras

- Diagnostics panel refreshes every second (pending items, last send result).
- *Opt out* toggle: after enabling it nothing new is recorded or sent; queued items are dropped.
