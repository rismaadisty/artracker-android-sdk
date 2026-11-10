#!/usr/bin/env bash
# Validates the release publication in an isolated local Maven repository BEFORE anything is sent to GitHub Packages.
#
#   scripts/verify-publication.sh [--repo <dir>] [--skip-consumer] [--legacy-consumer]
#
# 1. publishes id.art.tracker:artracker-android:<version> with `publishToMavenLocal -Dmaven.repo.local=<dir>`
#    (default <dir>: build/verify-m2; ~/.m2 is never touched),
# 2. checks the files: .aar, .pom, .module, -sources.jar, -javadoc.jar,
# 3. checks the POM: coordinates, packaging, and that its dependencies are exactly the expected runtime ones
#    (no optional compileOnly library such as OkHttp, Fragment, Navigation or Compose leaks into it),
# 4. checks the Gradle module metadata against the POM,
# 5. checks the AAR: manifest, classes.jar, consumer ProGuard rules, and that BuildConfig carries the same version,
# 6. builds a throw-away consumer app (scripts/consumer-check) that resolves the artifact ONLY from <dir>, compiles a
#    call into the public API and builds a minified release (R8 + consumer rules). With --legacy-consumer it also does
#    it with AGP 8 + Kotlin 2.0.21 (slower, downloads another toolchain).
#
# Exit code 0 only when every check passed. Needs: JDK 17, the Android SDK (ANDROID_HOME or local.properties), python3.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

REPO_DIR="$ROOT/build/verify-m2"
RUN_CONSUMER=1
RUN_LEGACY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO_DIR="$(cd "$(dirname "$2")" 2>/dev/null && pwd)/$(basename "$2")"; shift 2 ;;
    --skip-consumer) RUN_CONSUMER=0; shift ;;
    --legacy-consumer) RUN_LEGACY=1; shift ;;
    -h|--help) sed -n '2,19p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

GROUP="id.art.tracker"
ARTIFACT="artracker-android"
fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok:   $*"; }

# Android SDK location for the consumer build (the root build reads local.properties itself).
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && -f local.properties ]]; then
  sdk_dir="$(grep -E '^sdk\.dir=' local.properties | head -n1 | cut -d= -f2- || true)"
  [[ -n "$sdk_dir" ]] && export ANDROID_HOME="$sdk_dir"
fi

VERSION="$(./gradlew -q :artracker-sdk:printSdkVersion | tail -n1 | tr -d '[:space:]')"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || fail "unexpected SDK version '$VERSION'"
echo "== Verifying $GROUP:$ARTIFACT:$VERSION in $REPO_DIR"

# --- 1. publish into an isolated repository ---------------------------------------------------------------------
rm -rf "$REPO_DIR"
./gradlew :artracker-sdk:publishToMavenLocal -Dmaven.repo.local="$REPO_DIR"

DIR="$REPO_DIR/${GROUP//.//}/$ARTIFACT/$VERSION"
[[ -d "$DIR" ]] || fail "publication directory missing: $DIR"

# --- 2. files ------------------------------------------------------------------------------------------------------
for ext in aar pom module; do
  [[ -s "$DIR/$ARTIFACT-$VERSION.$ext" ]] || fail "missing or empty $ARTIFACT-$VERSION.$ext"
done
for classifier in sources javadoc; do
  [[ -s "$DIR/$ARTIFACT-$VERSION-$classifier.jar" ]] || fail "missing or empty $ARTIFACT-$VERSION-$classifier.jar"
done
ok "aar, pom, module, sources and javadoc jars are present"

# --- 3 + 4. POM and Gradle module metadata ---------------------------------------------------------------------------
python3 -I - "$DIR/$ARTIFACT-$VERSION.pom" "$DIR/$ARTIFACT-$VERSION.module" "$GROUP" "$ARTIFACT" "$VERSION" <<'PY'
import json, sys
import xml.etree.ElementTree as ET

pom_path, module_path, group, artifact, version = sys.argv[1:6]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(pom_path).getroot()

def text(path):
    node = root.find(path, ns)
    return node.text.strip() if node is not None and node.text else None

errors = []
for path, want in (("m:groupId", group), ("m:artifactId", artifact), ("m:version", version), ("m:packaging", "aar")):
    got = text(path)
    if got != want:
        errors.append(f"POM {path}: expected {want!r}, got {got!r}")
for path in ("m:name", "m:description", "m:url", "m:scm/m:url", "m:scm/m:connection"):
    value = text(path) or ""
    if not value or "TODO" in value:
        errors.append(f"POM {path} is empty or still a TODO placeholder: {value!r}")

deps = {}
for dep in root.findall("m:dependencies/m:dependency", ns):
    key = (dep.find("m:groupId", ns).text, dep.find("m:artifactId", ns).text)
    deps[key] = (dep.find("m:version", ns).text, dep.find("m:scope", ns).text)

# Runtime dependencies the AAR needs (SDK build.gradle.kts, `implementation`). Update together with that file.
expected = {
    ("org.jetbrains.kotlin", "kotlin-stdlib"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android"),
    ("androidx.work", "work-runtime-ktx"),
    ("androidx.lifecycle", "lifecycle-process"),
    ("androidx.core", "core-ktx"),
}
if set(deps) != expected:
    errors.append(f"POM dependencies differ. missing={sorted(expected - set(deps))} unexpected={sorted(set(deps) - expected)}")
for key, (ver, scope) in deps.items():
    if not ver or "$" in ver or ver in ("unspecified", "+"):
        errors.append(f"POM dependency {key} has no concrete version: {ver!r}")
# The stdlib must stay on the language level the AAR is compiled with (see build.gradle.kts `coreLibrariesVersion`),
# otherwise hosts on Kotlin 2.0.x fail to compile against it.
stdlib = deps.get(("org.jetbrains.kotlin", "kotlin-stdlib"))
if stdlib and not stdlib[0].startswith("2.0."):
    errors.append(f"kotlin-stdlib {stdlib[0]} is newer than the pinned Kotlin 2.0 language level: Kotlin 2.0.x hosts would break")

# Gradle module metadata must agree with the POM.
module = json.load(open(module_path))
component = module["component"]
if (component["group"], component["module"], component["version"]) != (group, artifact, version):
    errors.append(f"module component mismatch: {module['component']}")
runtime = next((v for v in module["variants"] if v["name"].endswith("RuntimePublication")), None)
if runtime is None:
    errors.append("module metadata has no runtime variant")
else:
    module_deps = {(d["group"], d["module"]) for d in runtime.get("dependencies", [])}
    if module_deps != expected:
        errors.append(f"module runtime dependencies differ from the expected set: {sorted(module_deps ^ expected)}")

if errors:
    print("\n".join("FAIL: " + e for e in errors), file=sys.stderr)
    sys.exit(1)
print("ok:   POM and module metadata: coordinates, packaging, project info, dependencies:")
for (g, a), (ver, scope) in sorted(deps.items()):
    print(f"        {g}:{a}:{ver} ({scope})")
PY

# --- 5. AAR contents ------------------------------------------------------------------------------------------------
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
AAR="$DIR/$ARTIFACT-$VERSION.aar"
unzip -q "$AAR" -d "$WORK/aar"
for entry in AndroidManifest.xml classes.jar proguard.txt R.txt; do
  [[ -s "$WORK/aar/$entry" ]] || fail "AAR has no $entry"
done
unzip -q "$WORK/aar/classes.jar" -d "$WORK/classes"
[[ -f "$WORK/classes/id/art/tracker/ARTracker.class" ]] || fail "classes.jar has no id/art/tracker/ARTracker.class"
grep -q "ARTrackerScreenName" "$WORK/aar/proguard.txt" || fail "consumer ProGuard rules are not packaged (proguard.txt)"
grep -aq "$VERSION" "$WORK/classes/id/art/tracker/sdk/BuildConfig.class" \
  || fail "BuildConfig.SDK_VERSION does not contain $VERSION (published version and runtime version differ)"
ok "AAR: manifest, classes.jar ($(find "$WORK/classes" -name '*.class' | wc -l | tr -d ' ') classes), consumer rules, BuildConfig version $VERSION"

# --- 6. consumer build ----------------------------------------------------------------------------------------------
if [[ "$RUN_CONSUMER" == 1 ]]; then
  echo "== Consumer app (AGP 9, minified release) resolving only from $REPO_DIR"
  ./gradlew -p scripts/consumer-check -PartrackerRepo="$REPO_DIR" -PartrackerVersion="$VERSION" \
    :app:assembleDebug :app:assembleRelease
  ok "consumer app compiles against the published AAR and R8 accepts the consumer rules"
  if [[ "$RUN_LEGACY" == 1 ]]; then
    echo "== Consumer app (AGP 8.13 + Kotlin 2.0.21)"
    ./gradlew -p scripts/consumer-check -PconsumerProfile=legacy -PartrackerRepo="$REPO_DIR" -PartrackerVersion="$VERSION" \
      :app:assembleDebug :app:assembleRelease
    ok "a Kotlin 2.0.21 consumer compiles against the published AAR"
  fi
else
  echo "skip: consumer build (--skip-consumer)"
fi

echo "== All checks passed for $GROUP:$ARTIFACT:$VERSION. Nothing was sent to a remote repository."
