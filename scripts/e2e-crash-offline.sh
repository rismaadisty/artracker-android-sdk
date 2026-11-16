#!/usr/bin/env bash
# Acceptance criterion #2 of ARCHITECTURE.md section 1, on a real device or emulator: a fatal crash while the SDK cannot
# reach its endpoint is kept on disk and sent by the next launch once the endpoint is reachable.
#
#   1. the app data is cleared (run-as, see clear_data), then the sample runs on the OFFLINE profile (an endpoint that never resolves) and is crashed through the
#      debug-only intent extra `artracker.crash_now`;
#   2. the crash file must be on disk (files/artracker/crash/*.json) and nothing may have reached the ingest;
#   3. the sample is started again on the LOCAL profile, pointing at a stand-in ingest on this host (scripts/mock-ingest.py,
#      reached with `adb reverse`); within TIMEOUT seconds a `log` item with level `fatal` and name `crash` must arrive.
#
# No airplane mode and no change to the device's network settings: "offline" is an endpoint that cannot be resolved.
# Everything is undone at the end (the app data is cleared, adb reverse --remove). Use the real backend by passing EVENTS_URL.
#
# Usage: scripts/e2e-crash-offline.sh SERIAL [PORT] [TIMEOUT_SECONDS]
set -euo pipefail

SERIAL=${1:?usage: $0 SERIAL [PORT] [TIMEOUT_SECONDS]}
PORT=${2:-18080}
TIMEOUT=${3:-90}
PKG=id.art.tracker
HERE=$(cd "$(dirname "$0")" && pwd)
ADB=(adb -s "$SERIAL")
EVENTS_URL=${EVENTS_URL:-http://127.0.0.1:$PORT/events}

step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

MOCK_PID=""
cleanup() {
  [ -n "$MOCK_PID" ] && kill "$MOCK_PID" 2>/dev/null || true
  "${ADB[@]}" reverse --remove "tcp:$PORT" >/dev/null 2>&1 || true
  clear_data >/dev/null 2>&1 || true
}
trap cleanup EXIT

# `pm clear` is refused to adb on some vendors' builds (CLEAR_APP_USER_DATA): the sample is debuggable, so its own shell
# removes the data instead. The first launch after this pays for dex loading, like after a pm clear.
clear_data() {
  "${ADB[@]}" shell am force-stop "$PKG"
  "${ADB[@]}" shell "run-as $PKG sh -c 'rm -rf shared_prefs databases files no_backup cache'"
}

# Writes the sample's settings while the app is stopped (the app reads them at start).
set_profile() { # PROFILE LOCAL_ENDPOINT
  "${ADB[@]}" shell am force-stop "$PKG"
  "${ADB[@]}" shell "run-as $PKG mkdir -p shared_prefs"
  "${ADB[@]}" shell "run-as $PKG sh -c 'cat > shared_prefs/playground_settings.xml'" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="endpointProfile">$1</string>
  <string name="localEndpoint">$2</string>
</map>
XML
}

events_json() { curl -fsS "$EVENTS_URL" 2>/dev/null || echo '[]'; }
count_crashes() {
  events_json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(sum(1 for i in d if i.get("name")=="crash" and i.get("level")=="fatal"))'
}

step "start the stand-in ingest on 127.0.0.1:$PORT"
if [ "$EVENTS_URL" = "http://127.0.0.1:$PORT/events" ]; then
  python3 "$HERE/mock-ingest.py" "$PORT" &
  MOCK_PID=$!
  sleep 1
  kill -0 "$MOCK_PID" 2>/dev/null || fail "the stand-in ingest did not start (port $PORT busy?)"
fi
[ "$(count_crashes)" = "0" ] || fail "the ingest already holds a crash item: use a fresh one"

step "1. clean state, OFFLINE profile, launch, crash"
"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP # a screen that timed out pauses the Activity: the crash intent would wait
clear_data
set_profile OFFLINE "http://127.0.0.1:$PORT"
"${ADB[@]}" shell am start -W -n "$PKG/.MainActivity" >/dev/null
# The first launch after an install or a clear is slow (dex loading): wait until the Activity is really resumed, then let
# init, session_start and the first frames happen.
for _ in $(seq 1 60); do
  "${ADB[@]}" shell dumpsys activity activities | grep -q "ResumedActivity.*$PKG" && break
  sleep 1
done
sleep 4
# --activity-single-top: the app is already on top, and an intent that equals the launcher's (extras do not count) is dropped
"${ADB[@]}" shell am start --activity-single-top -n "$PKG/.MainActivity" --ez artracker.crash_now true >/dev/null
# The assertion is the crash FILE, not the death of the process: after a fatal exception some vendors' builds keep the
# process alive for a while (it has already been written and handed to the previous handler). The next set_profile
# force-stops whatever is left.
CRASH_FILES=0
for _ in $(seq 1 30); do
  CRASH_FILES=$("${ADB[@]}" shell "run-as $PKG ls files/artracker/crash" 2>/dev/null | tr -d '\r' | grep -c '\.json$' || true)
  [ "$CRASH_FILES" -ge 1 ] && break
  sleep 1
done
if [ "$CRASH_FILES" -lt 1 ]; then
  echo "pid now: $("${ADB[@]}" shell pidof "$PKG" | tr -d '\r')"
  echo "wakefulness: $("${ADB[@]}" shell dumpsys power | grep -m1 mWakefulness= | tr -d '\r')"
  "${ADB[@]}" logcat -d | grep -E "FATAL EXCEPTION|artracker.crash_now" | tail -3 | cut -c1-160 || true
  fail "no crash file: the crash trigger is missing from this build (debug only), or it did not run"
fi
[ -z "$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r')" ] || echo "note: the process was still alive after the crash (vendor crash handling); it is force-stopped next"

step "2. the crash is on disk and nothing reached the ingest"
FILES=$("${ADB[@]}" shell "run-as $PKG ls files/artracker/crash" 2>/dev/null | tr -d '\r' | grep -c '\.json$' || true)
[ "$FILES" -ge 1 ] || fail "no crash file under files/artracker/crash"
echo "crash files on disk: $FILES"
[ "$(count_crashes)" = "0" ] || fail "a crash item reached the ingest while the endpoint was unreachable"

step "3. relaunch on the LOCAL profile; the crash must arrive within ${TIMEOUT}s"
"${ADB[@]}" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
set_profile LOCAL "http://127.0.0.1:$PORT"
START=$(date +%s)
"${ADB[@]}" shell am start -W -n "$PKG/.MainActivity" >/dev/null
while :; do
  if [ "$(count_crashes)" -ge 1 ]; then
    echo "PASS: crash item received after $(( $(date +%s) - START )) s"
    exit 0
  fi
  [ $(( $(date +%s) - START )) -lt "$TIMEOUT" ] || fail "no crash item within ${TIMEOUT}s"
  sleep 2
done
