# Manual end-to-end test: sample app against a local backend

Status: NOT yet run. This checklist is Task 24 Step 3; record the result in the PR description.

Prerequisites: Docker, an Android emulator (API 30+ for the ANR step), the `artracker` backend repo next to this one.

## 1. Start the backend and create a key

1. `cd ../artracker && docker compose up -d`
2. Log in to the dashboard, create a project and a public key of the form `art_live_...`.
3. Note the project id and the backend port.

## 2. Configure and install the sample

1. Add to the ROOT `local.properties` of this repo (never commit it):
   ```
   artracker.endpoint=http://10.0.2.2:<port>
   artracker.key=art_live_...
   ```
2. `./gradlew :app:installDebug` on the emulator. (Cleartext to `10.0.2.2` and `localhost` is allowed in debug builds only.)

## 3. Events, logs and screens

1. Open the app, go to Login and press *Log in*, go to Payment and press *Pay now*, then open Diagnostics and press *Track event*, *Log WARN*, *HTTP 200*, *HTTP 404*, *HTTP timeout (3 s)*, *Flush*.
2. Query: `GET /api/v1/projects/:id/events?kind=all&limit=50` with the **dashboard login JWT**:
   `Authorization: Bearer <access token>`. This is a dashboard endpoint: the `art_live_...` ingest key (the
   `X-Artracker-Key` header) is rejected there, so do not reuse it. To get the token, log in to the dashboard in a
   browser, open the developer tools, and copy the access token: either from the `Authorization: Bearer ...` request
   header of any `/api/v1/...` call in the Network tab, or from the login response (`POST /api/v1/auth/login`, if your
   backend version uses that route; check the Network tab for the actual path), or from the browser storage where the
   dashboard keeps it (Application tab). Tokens expire; log in again if you get `401`.
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
3. Confirm `replay_chunk` items exist for the session.
4. Fetch the events JSON and grep it for the typed secrets, for example `curl -H "Authorization: Bearer $TOKEN" ... | grep -c 'hunter2-secret'`. Expected: `0`. Card digits and the payment total must not appear either (the total is masked by `Modifier.artrackerMask()`).
5. Open the Legacy XML screen and type into the password and number fields; the tagged containers (`artracker-mask`) must be masked in the chunks too.

## 7. Extras

- Diagnostics panel refreshes every second (pending items, last send result).
- *Opt out* toggle: after enabling it nothing new is recorded or sent; queued items are dropped.
