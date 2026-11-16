#!/usr/bin/env bash
# Manual item "401 kill-switch" of docs/e2e-manual-test.md, on a device: an ingest that answers 401 must make the SDK stop
# sending (kill-switch trip, one hour), and the trip must survive a restart of the app.
#
#   1. data wiped, sample on the LOCAL profile pointing at scripts/mock-ingest.py PORT 401 (every ingest POST gets 401);
#   2. within TIMEOUT s the ingest sees its first POST and the SDK writes `disabled_until` (about now + 1 h) to artracker_transport;
#   3. after that, more app events (an app-background / foreground round trip) must NOT produce another POST for HOLD s;
#   4. a restart of the app must still send nothing (the trip is persisted).
# The trip lasts an hour and would poison later runs, so the app data is wiped at the end.
#
# Usage: scripts/e2e-killswitch-401.sh SERIAL [PORT] [TIMEOUT_SECONDS] [HOLD_SECONDS]
set -euo pipefail
SERIAL=${1:?usage: $0 SERIAL [PORT] [TIMEOUT_SECONDS] [HOLD_SECONDS]}
PORT=${2:-18084}; TIMEOUT=${3:-60}; HOLD=${4:-45}
PKG=id.art.tracker; HERE=$(cd "$(dirname "$0")" && pwd); ADB=(adb -s "$SERIAL")
step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
MOCK_PID=""
clear_data() { "${ADB[@]}" shell am force-stop "$PKG"; "${ADB[@]}" shell "run-as $PKG sh -c 'rm -rf shared_prefs databases files no_backup cache'"; }
cleanup() { [ -n "$MOCK_PID" ] && kill "$MOCK_PID" 2>/dev/null || true; "${ADB[@]}" reverse --remove "tcp:$PORT" >/dev/null 2>&1 || true; clear_data >/dev/null 2>&1 || true; }
trap cleanup EXIT
requests() { curl -fsS "http://127.0.0.1:$PORT/requests" | python3 -c 'import json,sys; print(json.load(sys.stdin)["count"])'; }
disabled_until() { "${ADB[@]}" shell "run-as $PKG cat shared_prefs/artracker_transport.xml 2>/dev/null" | sed -n 's/.*name="disabled_until" value="\([0-9]*\)".*/\1/p'; }
launch() {
  "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP
  "${ADB[@]}" shell am start -W -n "$PKG/.MainActivity" >/dev/null
  for _ in $(seq 1 60); do "${ADB[@]}" shell dumpsys activity activities | grep -q "ResumedActivity.*$PKG" && break; sleep 1; done
}

step "start the ingest that answers 401 on 127.0.0.1:$PORT"
python3 "$HERE/mock-ingest.py" "$PORT" 401 & MOCK_PID=$!; sleep 1
kill -0 "$MOCK_PID" 2>/dev/null || fail "the stand-in ingest did not start (port $PORT busy?)"
"${ADB[@]}" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null

step "1. clean state, LOCAL profile, launch"
clear_data
"${ADB[@]}" shell "run-as $PKG mkdir -p shared_prefs"
"${ADB[@]}" shell "run-as $PKG sh -c 'cat > shared_prefs/playground_settings.xml'" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name="endpointProfile">LOCAL</string><string name="localEndpoint">http://127.0.0.1:$PORT</string></map>
XML
launch
"${ADB[@]}" shell input keyevent KEYCODE_HOME # the background flush sends what was recorded

step "2. first POST gets 401, the SDK trips"
until=""; for _ in $(seq 1 "$TIMEOUT"); do
  until=$(disabled_until); [ -n "$until" ] && [ "$(requests)" -ge 1 ] && break; sleep 1
done
[ -n "$until" ] || fail "no disabled_until in artracker_transport.xml within ${TIMEOUT}s (requests=$(requests))"
NOW=$(python3 -c 'import time; print(int(time.time()*1000))')
LEFT=$(( (until - NOW) / 1000 ))
echo "requests so far: $(requests); disabled for another ${LEFT}s"
[ "$LEFT" -gt 3000 ] && [ "$LEFT" -le 3700 ] || fail "the trip should last about an hour, found ${LEFT}s"

step "3. $HOLD s of app activity: no further POST"
BEFORE=$(requests)
"${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 5
"${ADB[@]}" shell input keyevent KEYCODE_HOME; sleep $((HOLD - 5))
AFTER=$(requests)
[ "$AFTER" = "$BEFORE" ] || fail "the SDK sent $((AFTER - BEFORE)) more POST(s) while tripped"

step "4. restart: the trip is persisted"
"${ADB[@]}" shell am force-stop "$PKG"; launch; sleep 15
"${ADB[@]}" shell input keyevent KEYCODE_HOME; sleep 15
FINAL=$(requests)
[ "$FINAL" = "$BEFORE" ] || fail "the restarted app sent $((FINAL - BEFORE)) POST(s) during the trip"
echo "PASS: $BEFORE ingest POST(s) in total, all before the trip, none for $((HOLD + 30)) s after it, trip survives a restart"
