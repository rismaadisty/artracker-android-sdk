# Replay viewer

Plays the session replay recorded by the Android SDK in a browser, with the same `rrweb-player` build the dashboard
uses, and checks the recording's structure without a browser.

| File | What it is |
| --- | --- |
| `index.html` | Self-contained page. Loads `rrweb-player@2.0.0-alpha.18` (CSS + JS pinned, from jsDelivr). Needs internet access to the CDN. |
| `validate-chunks.mjs` | Node script (no dependencies, Node 18+): structural checks of the rrweb events, no browser. |
| `validate-chunks.test.mjs` | Self-test of that script: `node --test tools/replay-viewer/validate-chunks.test.mjs`. |

## 1. Get the chunks

### From a test run (no device, no backend)

`ReplayChunkExportTest` records a Login to Feed session with the real walker, encoder, chunker and controller and
writes it to `artracker-sdk/build/replay-export/` (`replay-chunks.json` as chunk attributes, `replay-items.json` as
backend-style items):

```sh
./gradlew :artracker-sdk:testDebugUnitTest --tests "id.art.tracker.replay.ReplayChunkExportTest"
node tools/replay-viewer/validate-chunks.mjs artracker-sdk/build/replay-export/replay-chunks.json --forbid hunter2-secret
```

### From a sample app run (backend)

Run the sample app with replay enabled against the backend (see `docs/e2e-manual-test.md`), then export the chunks
with the dashboard JWT. The SDK has no on-device dump (the `ARTracker` debug log does not contain replay data):

```sh
curl -s -H "Authorization: Bearer $DASHBOARD_JWT" \
  "http://localhost:<port>/api/v1/projects/<project-id>/events?kind=session&limit=100" \
  | jq '[.items[] | select(.name == "replay_chunk")]' > chunks.json
```

Notes on that endpoint: it returns `{"items": [...]}`, newest first, at most 100 items per call, and there is no
filter by name or session, so `kind=session` also returns `session_start` / `session_end` (the `jq` filter drops
them). The viewer and the validator accept the response as is, plus any mix of sessions: a file holding several
sessions gets a session picker (viewer) or one report per session (validator). If the 100-item window starts in the
middle of a session, the first chunk (the one with the Meta and the full snapshot) is missing and both tools say so;
export again right after a short run.

Accepted formats (all three work in both tools):

- an array of `replay_chunk` attributes: `[{ "seq": 0, "start": 1762..., "end": ..., "full_snapshot": true, "platform": "android", "events": [...] }]`
- an array of backend items: `[{ "kind": "session", "name": "replay_chunk", "session_id": "...", "attributes": { ...chunk... } }]`
- the backend response object: `{ "items": [ ...items... ] }`

## 2. Play it

Open `tools/replay-viewer/index.html` in a browser (double-click is enough, no server needed) and pick the JSON file.
Chunks are sorted by `start`, then `seq`, and their `events` concatenated. The page refuses to play (with a message)
unless the first events include a Meta (type 4) and a FullSnapshot (type 2); the player size comes from the Meta
event.

What to look for: the Login and Feed screens play back as grey/white boxes with their text, the password and e-mail
inputs show only `*`, taps appear as pointer events, and a screen change starts a new snapshot.

## 3. Validate the structure without a browser

```sh
node tools/replay-viewer/validate-chunks.mjs <file.json> [--forbid <text>]...
```

Replays the rrweb node tree event by event and fails (exit code 1, problems listed) on: a missing `timestamp` or
`type`; first events other than Meta then FullSnapshot; duplicate node ids within a snapshot; elements without a
`tagName` or with non-string attribute values; mutation `removes` / `adds` / `texts` / `attributes` that point at
nodes that do not exist (unknown `parentId`, `nextId`, text change on a non-text node); touch events on missing nodes;
text inside a `data-artracker-masked` element that is not all `*`; and any `--forbid` string appearing anywhere in the
recording (pass the password you typed). Timestamps that go backwards are only warned about: rrweb sorts by timestamp.

This proves the structure, not the pixels: whether `rrweb-player` renders it correctly still has to be checked by
opening it in `index.html`.
