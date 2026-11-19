#!/usr/bin/env bash
# Measures what the SDK costs a host app, with definitions meant to be copied by the iOS SDK:
#   1. raw artifact bytes        (release AAR, and classes.jar inside it)
#   2. dex method ids            (d8 of classes.jar: methods the SDK adds before the host app's R8)
#   3. release APK delta         (scripts/consumer-check built twice with R8 on: -PsdkMode=none vs full)
#
#   scripts/measure-size.sh [--repo <dir>]
#
# Prints ONE markdown table on stdout (progress goes to stderr). Anything that cannot be measured is printed as
# `NOT measured: <reason>` instead of a number. Exit code is non-zero if a build step fails.
# Needs: JDK 17, the Android SDK (ANDROID_HOME or local.properties); d8 and dexdump from build-tools for the method count.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

REPO_DIR="$ROOT/build/measure-m2"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '2,11p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

log() { echo "== $*" >&2; }
fail() { echo "FAIL: $*" >&2; exit 1; }
size_of() { stat -f%z "$1" 2>/dev/null || stat -c%s "$1"; }

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && -f local.properties ]]; then
  sdk_dir="$(grep -E '^sdk\.dir=' local.properties | head -n1 | cut -d= -f2- || true)"
  [[ -n "$sdk_dir" ]] && export ANDROID_HOME="$sdk_dir"
fi
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

VERSION="$(./gradlew -q :artracker-sdk:printSdkVersion | tail -n1 | tr -d '[:space:]')"
[[ -n "$VERSION" ]] || fail "could not read the SDK version"
log "Measuring id.art.tracker:artracker-android:$VERSION at $(git rev-parse --short HEAD)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# --- 1. artifact bytes -----------------------------------------------------------------------------------------
log "Building the release AAR and publishing it to $REPO_DIR (your ~/.m2 is untouched)"
./gradlew -q :artracker-sdk:exportReleaseAar >&2
DIST_AAR="$ROOT/artracker-sdk/build/dist/artracker-android-$VERSION.aar"
[[ -s "$DIST_AAR" ]] || fail "missing $DIST_AAR"
rm -rf "$REPO_DIR"
./gradlew -q :artracker-sdk:publishToMavenLocal -Dmaven.repo.local="$REPO_DIR" >&2
AAR_BYTES="$(size_of "$DIST_AAR")"

unzip -q -o "$DIST_AAR" classes.jar -d "$WORK/aar"
CLASSES_BYTES="$(size_of "$WORK/aar/classes.jar")"

# --- 2. dex method ids -----------------------------------------------------------------------------------------
METHODS="NOT measured: d8 or dexdump not found in the Android SDK build-tools"
if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/build-tools" ]]; then
  BT="$(ls -d "$SDK_ROOT"/build-tools/*/ 2>/dev/null | sort -V | tail -n1 || true)"
  PLATFORM="$(ls -d "$SDK_ROOT"/platforms/android-*/ 2>/dev/null | sort -V | tail -n1 || true)"
  if [[ -n "$BT" && -x "${BT}d8" && -x "${BT}dexdump" && -n "$PLATFORM" ]]; then
    log "Counting methods with d8 / dexdump from $BT"
    mkdir -p "$WORK/dex"
    # The unresolved okhttp3 / androidx.work desugaring warnings are expected: they are compileOnly / separate libraries.
    "${BT}d8" --lib "${PLATFORM}android.jar" --output "$WORK/dex" "$WORK/aar/classes.jar" >&2 2>"$WORK/d8.log" || {
      cat "$WORK/d8.log" >&2; fail "d8 failed"; }
    # Through a file, not `dexdump | grep -m1`: grep closing the pipe would SIGPIPE dexdump and trip pipefail.
    "${BT}dexdump" -f "$WORK/dex/classes.dex" >"$WORK/dexdump.txt"
    count="$(grep method_ids_size "$WORK/dexdump.txt" | head -n1 | sed -E 's/[^0-9]*([0-9]+).*/\1/' || true)"
    if [[ "$count" =~ ^[0-9]+$ ]]; then METHODS="$count"; else METHODS="NOT measured: could not read method_ids_size"; fi
  fi
fi

# --- 3. release APK delta --------------------------------------------------------------------------------------
build_apk() { # <mode> -> prints the APK byte size
  local mode="$1"
  # `clean` first: stale classes from the other mode would otherwise be dexed into this APK.
  ./gradlew -q -p scripts/consumer-check clean :app:assembleRelease -PsdkMode="$mode" \
    -PartrackerRepo="$REPO_DIR" -PartrackerVersion="$VERSION" >&2
  local apk
  apk="$(ls scripts/consumer-check/app/build/outputs/apk/release/*.apk | head -n1)"
  [[ -s "$apk" ]] || fail "no release APK for sdkMode=$mode"
  size_of "$apk"
}
log "Release APK without the SDK (R8 on)"
APK_WITHOUT="$(build_apk none)"
log "Release APK with the SDK (R8 on, consumer rules from the AAR)"
APK_WITH="$(build_apk full)"
APK_DELTA=$((APK_WITH - APK_WITHOUT))

cat <<EOF
| Metric | Value |
| --- | --- |
| AAR bytes | $AAR_BYTES |
| classes.jar bytes | $CLASSES_BYTES |
| SDK dex methods | $METHODS |
| Release APK bytes without SDK | $APK_WITHOUT |
| Release APK bytes with SDK | $APK_WITH |
| APK delta bytes | $APK_DELTA |
EOF
