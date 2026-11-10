# Validating the consumer rules with R8 (Task 26)

`artracker-sdk/consumer-rules.pro` ships in the AAR as `proguard.txt`. Two checks were done; the third is NOT run.

## 1. Sample app, R8-minified release build (RUN, passes)

The sample app has OkHttp, Compose, Fragment and Navigation, so this checks that the rules parse, and that R8 does not
fail or merge the isolated holder classes. Temporary change, not committed:

```sh
# app/build.gradle.kts: release { isMinifyEnabled = true }
# app/proguard-rules.pro: append
#   -printseeds build/outputs/mapping/release/seeds.txt
#   -printusage build/outputs/mapping/release/usage.txt
#   -printconfiguration build/outputs/mapping/release/configuration.txt
./gradlew :app:assembleRelease
grep -E "^id\.art\.tracker\.(lifecycle\.(FragmentSupport|NavBinder|FragmentTracker)|replay\.capture\.(ComposeWalker|ReflectiveSemanticsAccess)|replay\.screenshot\.(ComposeMaskCollector|ComposeMaskCollectorFactory)|privacy\.(ComposeMaskRules|ComposeMaskRulesKt)) ->" app/build/outputs/mapping/release/mapping.txt
```

The minified runs were done with the temporary local edits above (never committed; `git checkout app/build.gradle.kts
app/proguard-rules.pro` reverts them). To reproduce: apply the edits with
`sed -i '' 's/isMinifyEnabled = false/isMinifyEnabled = true/' app/build.gradle.kts` and append the three `-print*` lines to
`app/proguard-rules.pro`, then run `./gradlew :app:assembleRelease`.

Result: `BUILD SUCCESSFUL`; every holder above is a separate, renamed class in `mapping.txt`; `@ARTrackerScreenName` and
`FlushWorker` keep their names; unused public API (`ARTrackerConfig$Builder`) is removed, so the public API is
shrinkable. The app was **not run** (no emulator), so "the minified app still sends events" is NOT verified.

## 2. Host WITHOUT OkHttp / Compose / Fragment / Navigation, stand-alone R8 on the AAR (RUN, passes)

Not an AGP build: R8 (`com.android.tools.r8.R8` from `build-tools/<v>/lib/d8.jar`) is run on the AAR's `classes.jar`
with only the SDK's real dependencies on the classpath. The four optional libraries are absent. Entry points are
simulated with keep rules for the public API.

```sh
unzip -o artracker-android-0.1.0.aar -d aar            # from the isolated m2 repo, see CHANGELOG
# host.pro
#   -keep class id.art.tracker.ARTracker { public *; }          (and ARTrackerConfig, ReplayConfig, PrivacyConfig,
#   NetworkConfig, privacy.ARTrackerMask)
#   -dontwarn androidx.lifecycle.** androidx.room.** androidx.sqlite.** androidx.work.** androidx.annotation.**
#   -dontwarn com.google.common.** org.jetbrains.annotations.** id.art.tracker.sdk.R*     (reduced classpath only)
java -cp $ANDROID_HOME/build-tools/36.0.0/lib/d8.jar com.android.tools.r8.R8 --release --min-api 24 \
  --lib $ANDROID_HOME/platforms/android-36/android.jar \
  --classpath <kotlin-stdlib> --classpath <coroutines-core-jvm> --classpath <coroutines-android> \
  --classpath <work-runtime classes.jar> --classpath <lifecycle-process classes.jar> --classpath <core classes.jar> \
  --pg-conf host.pro --pg-conf aar/proguard.txt --output out aar/classes.jar
```

Results:

- with `aar/proguard.txt`: exit 0, `classes.dex` produced, no "Missing class" error;
- negative control with an empty rules file: exit 1, "Missing class androidx.fragment.app.FragmentActivity /
  androidx.navigation.NavController / androidx.compose.ui.text.AnnotatedString", so the `-dontwarn` rules are what
  makes it pass;
- `ARTrackerOkHttpInterceptor` is removed from the output when the host does not reference it (that is why its keep rule
  uses `allowshrinking`); the Fragment/Navigation/Compose holders stay as separate classes and are only ever loaded
  behind `ClassAvailability`.

This proves R8 does not fail and does not merge the holders. It does NOT prove that the resulting app runs without
`NoClassDefFoundError` on a device (no emulator): that is the remaining risk.

## 3. Real AGP build of an app without those libraries, installed and run (NOT RUN)

Create a minimal app that depends only on `id.art.tracker:artracker-android:0.1.0`, enable `isMinifyEnabled`, build
`assembleRelease`, install it on a device and call `ARTracker.init(...)`, `track(...)`, `screen(...)`; check logcat for
`NoClassDefFoundError` / `ClassNotFoundException` and that the item reaches the backend. Not done.
