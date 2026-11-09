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
writes it to `artracker-sdk/build/replay-export/` (`replay-chunks.json` as chunk attributes, rebuilt from the upload
batches; the old `replay-items.json` is no longer written because replay is not an ingest item):

```sh
./gradlew :artracker-sdk:testDebugUnitTest --tests "id.art.tracker.replay.ReplayChunkExportTest"
node tools/replay-viewer/validate-chunks.mjs artracker-sdk/build/replay-export/replay-chunks.json --forbid hunter2-secret
```

### From a sample app run

Debug builds of the sample app run a loopback sink that also plays the Session Replay API (`chunk-url`, `PUT`,
`chunk-ack`): the *Replay lab* export writes the last 50 uploaded batches as `replay-chunks.json` in the chunk-attributes
format below. The Inspector does not list replay uploads (they are not ingest items).

The SDK no longer sends replay as `replay_chunk` items to `/api/v1/ingest`, so exporting them from the events API (the
old `curl ... | jq 'select(.name == "replay_chunk")'` recipe) only works for data recorded by an earlier SDK version. A
backend that stored replay through the new API serves it from its own replay endpoints, which this tool does not fetch.
When you have the dashboard's JSON, the accepted formats below still apply.

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
