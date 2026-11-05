# ARTracker Android SDK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Membangun modul `:artracker-sdk` (Android library, Kotlin) yang mengirim event, log, crash, telemetri HTTP, dan session replay (wireframe → rrweb) ke `POST /api/v1/ingest`, beserta sample app `:app` untuk mengujinya.

**Architecture:** Facade `ARTracker` → `ARTrackerCore` → pipeline single-thread (Channel → redaksi → SQLite write-ahead → batch gzip → `HttpURLConnection`). Crash ditulis sinkron ke file dan dikirim pada launch berikutnya. Replay membaca hierarki View/semantics Compose di main thread menjadi `Wireframe` yang sudah di-mask, lalu diff, encode rrweb, dan chunk di background.

**Tech Stack:** Kotlin (AGP 9 built-in Kotlin), Coroutines, WorkManager, lifecycle-process, SQLiteOpenHelper. OkHttp/Fragment/Compose/Navigation sebagai `compileOnly`. Test: JUnit4, Robolectric, MockWebServer, org.json (test only), kotlinx-coroutines-test, work-testing.

**Spec:** `artracker-sdk/ARCHITECTURE.md` (wajib dibaca bersama plan ini). Kontrak backend: §2 spec; referensi silang `../artracker-web-sdk/CONTRACT.md`.

> **Catatan scope:** spec mencakup beberapa subsistem. Plan ini dibagi menjadi 7 milestone (A–G) yang masing-masing menghasilkan software yang bisa dites. Milestone A–E bisa dirilis sebagai `0.1.0` tanpa replay. F (replay) bisa dipisah menjadi plan sendiri bila perlu.

## Global Constraints

- Semua kode Kotlin SDK berada di package `id.art.tracker` dan sub-package-nya (`core`, `transport`, `privacy`, `telemetry`, `lifecycle`, `replay`). Dilarang memakai `id.art.tracker.ui`.
- `android.namespace` modul SDK = `id.art.tracker.sdk` (hanya untuk `R`/`BuildConfig`). Namespace & package `:app` **tidak diubah** (`id.art.tracker`).
- Facade publik: `object ARTracker` dengan `ARTracker.init(context, config)`; setiap fungsi publik `@JvmStatic`.
- `minSdk 24`, `compileSdk 36`, Java 11 target. `explicitApi()` aktif di SDK.
- Setiap entry point publik tidak boleh melempar exception ke app (dibungkus `runCatching`); sebelum `init()` = no-op.
- Endpoint: `{endpoint}/api/v1/ingest`, header `X-Artracker-Key`, `Content-Type: application/json`, `Content-Encoding: gzip`, `User-Agent: artracker-android/<version>`.
- API key wajib berprefix `art_live_` dan panjangnya 32–128 karakter; `art_sec_` ditolak.
- Envelope: `{sent_at, sdk:{name:"artracker-android", version}, context:{environment, app_version, sdk_platform:"android"}, items}`.
- Item: `type` ∈ `event|log|session`; `level` hanya untuk `log` ∈ `debug|info|warn|error|fatal`; `name` ≤255, `message` ≤4096, `session_id`/`user_id` ≤128; `id` UUIDv7 dibuat sekali; `timestamp` ISO-8601 UTC milidetik (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).
- Batch ≤50 item dan ≤128 KB uncompressed; `attributes` per item ≤24 KB.
- Antrean SQLite maks 5 MB / 5000 baris; item >7 hari dibuang; prioritas: 0 = crash/fatal, 1 = log error, 2 = event/session/log lain, 3 = `replay_chunk`. Prioritas 0 tidak pernah di-evict.
- Flush foreground tiap 10 000 ms; maks 5 batch/detik saat mengosongkan antrean.
- Backoff: base 2 000 ms, maks 300 000 ms, full jitter; `401` → kill-switch 3 600 000 ms.
- Session idle timeout default 1 800 000 ms (30 menit); batas keras 24 jam.
- Replay default `enabled=false`, `sampleRate=0.1`, `framesPerSecond=1.0` (rentang 0.5–4.0); budget walk 8 ms; maks 2000 node, kedalaman 50; chunk flush tiap 5 000 ms atau ≥20 KB.
- Privasi default: `maskAllInputs=true`, `maskAllText=false`; teks mask = `*` sepanjang teks (maks 32); token redaksi = `[redacted]`.
- Breadcrumbs: ring buffer 30; stack trace maks 16 KB; maks 10 error identik (fingerprint) per sesi.
- Tidak ada dependensi Room, kotlinx.serialization, atau `android.util.JsonWriter`; OkHttp, Fragment, Compose, dan Navigation hanya `compileOnly` dan dicek saat runtime lewat `ClassAvailability`.

## Review Focus

1. **App tanpa OkHttp/Compose/Fragment/Navigation di classpath**: SDK tetap jalan, fitur terkait diam-diam nonaktif, tidak ada `NoClassDefFoundError`. Test: `ClassAvailabilityTest` + `ARTrackerCoreTest.init_withoutOptionalDeps_doesNotTouchOptionalClasses` (Task 13, 14).
2. **Props siklik atau sangat dalam di `track()`** (mis. map yang berisi dirinya sendiri): tidak StackOverflow, dipotong dengan penanda. Test: `JsonTest.cyclicMap_isCutAtMaxDepth` (Task 2) dan `AttributeRedactorTest.depthBeyond6_isReplacedWithMarker` (Task 5).
3. **API publik dipanggil dari banyak thread sekaligus, sebelum `init()`, atau setelah kill-switch**: tidak blok, tidak crash, tidak ada item hilang di bawah kapasitas. Test: `ARTrackerFacadeTest.concurrentTrack_fromEightThreads_allItemsQueued` dan `beforeInit_allApisAreNoOps` (Task 13).
4. **App sudah memasang `UncaughtExceptionHandler` lain (Crashlytics)**: handler lama dipanggil tepat sekali dan tetap dipanggil walau penulisan crash gagal. Test: `CrashHandlerTest.previousHandler_calledExactlyOnce_evenWhenWriteFails` (Task 15).
5. **Hierarki sangat besar (RecyclerView ribuan item, layar kompleks)**: capture dibatasi 2000 node dan watchdog menurunkan fps. Test: `ViewWalkerTest.over2000Nodes_isCappedWithSummary` (Task 19) dan `FrameSchedulerTest.slowCaptures_halveFps` (Task 21).

---

## File Structure

```
settings.gradle.kts                                  (modify: include :artracker-sdk)
gradle/libs.versions.toml                            (modify: lib & plugin baru)
app/build.gradle.kts                                 (modify: dependency, BuildConfig dari local.properties)
artracker-sdk/
├─ build.gradle.kts, consumer-rules.pro, proguard-rules.pro
├─ src/main/AndroidManifest.xml                      (INTERNET, ACCESS_NETWORK_STATE)
├─ src/main/res/values/ids.xml                       (artracker_mask, artracker_unmask)
└─ src/main/java/id/art/tracker/
   ├─ ARTracker.kt, ARTrackerConfig.kt, TelemetryItem.kt, LogLevel.kt, Diagnostics.kt
   ├─ core/      SdkInfo, Json, Iso8601, UuidV7, Clock, KeyValueStore, ClassAvailability,
   │             ItemFactory, SessionManager, IdentityStore, DeviceContext, KillSwitch, ARTrackerCore, SdkLog
   ├─ privacy/   AttributeRedactor, UrlScrubber, MaskingPolicy, ViewMaskRules, ComposeMaskRules, ARTrackerMask
   ├─ transport/ ItemQueue, EventStore, PayloadEncoder, IngestClient, SendResult, Backoff, Flusher,
   │             EventPipeline, BackgroundScheduler, FlushWorker, TransportConfigStore
   ├─ lifecycle/ AppLifecycleObserver, ActivityTracker, FragmentTracker, ScreenTracker, NavTracker
   ├─ telemetry/ Breadcrumbs, Stacktrace, ErrorDeduper, CrashStore, CrashHandler, CrashRecovery,
   │             ExitInfoCollector, okhttp/ARTrackerOkHttpInterceptor, okhttp/HttpRecord
   └─ replay/    model/Wireframe, capture/{RootViewLocator,ViewWalker,ComposeWalker,FrameScheduler},
                 diff/WireframeDiffer, rrweb/RrwebEncoder, touch/TouchRecorder, ReplayChunker,
                 Sampling, ReplayController
artracker-sdk/src/test/java/id/art/tracker/...       (cermin struktur main)
app/src/main/java/id/art/tracker/sample/...           (layar demo; sub-package baru, namespace app tetap)
tools/replay-viewer/index.html                        (validasi rrweb-player lokal)
```

Perintah test standar (dari root `artracker-android-sdk/`):
`./gradlew :artracker-sdk:testDebugUnitTest --tests "<FQCN>"`

---

## Milestone A — Fondasi

### Task 0: Branch kerja

- [ ] **Step 1:** `git checkout -b feature/android-sdk` (dari `main`). File `artracker-sdk/ARCHITECTURE.md` dan plan ini ikut commit pertama di Task 1.

### Task 1: Scaffold modul `:artracker-sdk` + integrasi build

**Files:**
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts` (root: `alias(libs.plugins.android.library) apply false`), `app/build.gradle.kts`
- Create: `artracker-sdk/build.gradle.kts`, `artracker-sdk/consumer-rules.pro`, `artracker-sdk/src/main/AndroidManifest.xml`, `artracker-sdk/src/main/java/id/art/tracker/core/SdkInfo.kt`
- Test: `artracker-sdk/src/test/java/id/art/tracker/core/SdkInfoTest.kt`

**Interfaces:**
- Produces: `internal object SdkInfo { const val NAME = "artracker-android"; val VERSION: String /* = BuildConfig.SDK_VERSION */ }`; `id.art.tracker.sdk.BuildConfig.SDK_VERSION = "0.1.0"`.

Catalog yang ditambahkan (versi minimum; naikkan bila resolusi gagal): `android-library` plugin (`agp`), `kotlinx-coroutines-android` + `-test` 1.9.0, `androidx-work-runtime-ktx` + `androidx-work-testing` 2.10.0, `androidx-lifecycle-process` (`lifecycleRuntimeKtx`), `okhttp` + `mockwebserver` 4.12.0, `robolectric` 4.14.1, `androidx-test-core` 1.6.1, `org-json` 20240303, `androidx-fragment-ktx` 1.8.5, `androidx-navigation-runtime-ktx` 2.8.4.

- [ ] **Step 1: Baseline.** Jalankan `./gradlew :app:assembleDebug` **sebelum** perubahan apa pun. Expected: BUILD SUCCESSFUL. Bila gagal karena Kotlin 2.0.21 vs built-in Kotlin AGP 9 (risiko R2), naikkan `kotlin` di catalog ke versi yang diminta pesan error, lalu ulangi. Catat versi akhir di commit message.
- [ ] **Step 2: Tulis test gagal** `SdkInfoTest.name_isArtrackerAndroid` (`assertEquals("artracker-android", SdkInfo.NAME)`) dan `version_matchesBuildConfig` (`assertEquals(BuildConfig.SDK_VERSION, SdkInfo.VERSION)`, import `id.art.tracker.sdk.BuildConfig`).
- [ ] **Step 3: Buat modul.** `artracker-sdk/build.gradle.kts`: plugin `android.library` + `kotlin.compose` (hanya untuk test Compose di Task 20); `android { namespace = "id.art.tracker.sdk"; compileSdk 36; minSdk 24; buildFeatures { buildConfig = true; compose = true }; defaultConfig { buildConfigField("String","SDK_VERSION","\"0.1.0\""); consumerProguardFiles("consumer-rules.pro") }; testOptions.unitTests.isIncludeAndroidResources = true }`; `kotlin { explicitApi() }` (bila extension tidak tersedia dengan built-in Kotlin, pakai `compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")`). Dependency: `implementation` coroutines, work-runtime-ktx, lifecycle-process, core-ktx; `compileOnly` okhttp, fragment-ktx, navigation-runtime-ktx, `platform(compose-bom)` + compose-ui; `testImplementation` junit, robolectric, androidx-test-core, mockwebserver, okhttp, org-json, coroutines-test, work-testing, fragment-ktx, compose ui-test-junit4, ui-test-manifest, activity-compose, material3. Manifest: `INTERNET`, `ACCESS_NETWORK_STATE`. `include(":artracker-sdk")` di settings. `app`: `implementation(project(":artracker-sdk"))`.
- [ ] **Step 4: Verifikasi.** `./gradlew :artracker-sdk:testDebugUnitTest --tests "id.art.tracker.core.SdkInfoTest" :app:assembleDebug`. Expected: test PASS, build sukses, **tanpa** error duplicate class `R`/`BuildConfig`.
- [ ] **Step 5: Commit** `chore(sdk): scaffold artracker-sdk module` (sertakan `artracker-sdk/ARCHITECTURE.md` dan `docs/superpowers/plans/`).

### Task 2: Json encoder, ISO-8601, UUIDv7, Clock

**Files:**
- Create: `core/Json.kt`, `core/Iso8601.kt`, `core/UuidV7.kt`, `core/Clock.kt`
- Test: `core/JsonTest.kt`, `core/Iso8601Test.kt`, `core/UuidV7Test.kt`

**Interfaces:**
- Produces:
  - `internal object Json { const val MAX_DEPTH = 32; fun encode(value: Any?): String; fun write(value: Any?, out: Appendable) }` mendukung `null, Boolean, Number (NaN/Infinity → null), String, Char, Enum (name), Map<*,*> (key toString), Iterable, Array, Date (ISO), RawJson`; selain itu `toString()`. Kedalaman > `MAX_DEPTH` ditulis sebagai string `"[max_depth]"`.
  - `@JvmInline internal value class RawJson(val json: String)` (ditulis apa adanya; dipakai untuk payload item yang sudah ter-serialisasi).
  - `internal object Iso8601 { fun format(epochMs: Long): String }`
  - `internal object UuidV7 { fun generate(nowMs: Long = System.currentTimeMillis(), random: java.util.Random = SecureRandom()): String }`
  - `internal fun interface Clock { fun nowMs(): Long; companion object { val SYSTEM: Clock } }`

- [ ] **Step 1: Test gagal.**
  - `JsonTest.escapesControlAndQuotes`: `Json.encode("a\"b\\c\n\u0001")` == `"\"a\\\"b\\\\c\\n\\u0001\""`.
  - `JsonTest.nanAndInfinity_becomeNull`: `Json.encode(listOf(Double.NaN, Float.POSITIVE_INFINITY))` == `"[null,null]"`.
  - `JsonTest.nestedMapAndList`: `Json.encode(mapOf("a" to listOf(1, true, null)))` == `"{\"a\":[1,true,null]}"`.
  - `JsonTest.rawJson_isWrittenVerbatim`: `Json.encode(listOf(RawJson("{\"x\":1}")))` == `"[{\"x\":1}]"`.
  - `JsonTest.cyclicMap_isCutAtMaxDepth`: `val m = HashMap<String, Any?>(); m["self"] = m` → `Json.encode(m)` tidak melempar dan mengandung `"[max_depth]"`.
  - `Iso8601Test.epochZero` → `"1970-01-01T00:00:00.000Z"`; `Iso8601Test.millisPreserved`: `format(1762331598050)` == `"2025-11-05T08:33:18.050Z"`.
  - `UuidV7Test.versionAndVariant`: karakter index 14 == `'7'`, index 19 ∈ `"89ab"`, panjang 36.
  - `UuidV7Test.timestampPrefix`: `generate(nowMs = 0x0123456789AB)` diawali `"01234567-89ab-7"`.
  - `UuidV7Test.sortableAcrossMillis`: `generate(1000) < generate(1001)` (perbandingan string).
- [ ] **Step 2:** Jalankan `--tests "id.art.tracker.core.*"` → FAIL (unresolved reference).
- [ ] **Step 3: Implementasi.** `Iso8601` memakai `SimpleDateFormat` UTC per-thread (`ThreadLocal`) karena `java.time` butuh desugaring di API 24. `UuidV7`: 48 bit ms + versi 7 + 12 bit acak + variant `10` + 62 bit acak.
- [ ] **Step 4:** Jalankan ulang → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add json encoder, iso8601 and uuidv7`.

### Task 3: Konfigurasi publik & validasi

**Files:**
- Create: `ARTrackerConfig.kt` (berisi juga `ReplayConfig`, `PrivacyConfig`, `NetworkConfig`), `core/SdkLog.kt`
- Test: `ARTrackerConfigTest.kt`

**Interfaces:**
- Produces:
  - `public class ARTrackerConfig @JvmOverloads constructor(public val endpoint: String, public val apiKey: String, public val environment: String = "production", public val appVersion: String? = null, public val debug: Boolean = false, public val replay: ReplayConfig = ReplayConfig(), public val privacy: PrivacyConfig = PrivacyConfig(), public val network: NetworkConfig = NetworkConfig(), public val flushIntervalMs: Long = 10_000, public val sessionTimeoutMs: Long = 1_800_000, public val beforeSend: BeforeSend? = null)` + `public class Builder(endpoint, apiKey)` dengan setter fluent untuk setiap field dan `build()`.
  - `public fun interface BeforeSend { public fun process(item: TelemetryItem): TelemetryItem? }` (`TelemetryItem` dari Task 4; definisikan `BeforeSend` di Task 4 bila urutan compile mengharuskan).
  - `public class ReplayConfig @JvmOverloads constructor(enabled = false, sampleRate = 0.1, framesPerSecond = 1.0)`
  - `public class PrivacyConfig @JvmOverloads constructor(maskAllInputs = true, maskAllText = false, sensitiveViewClasses: Set<Class<out View>> = emptySet())`
  - `public class NetworkConfig @JvmOverloads constructor(captureHeaders = false, ignoreHosts: Set<String> = emptySet(), sampleRate = 1.0, captureOnlyErrors = false)`
  - `internal fun ARTrackerConfig.validate(): List<String>` (kosong = valid)
  - `internal fun ReplayConfig.effectiveFps(): Double` (clamp 0.5..4.0)
  - `internal object SdkLog { var enabled: Boolean; fun d(msg: String); fun w(msg: String, t: Throwable? = null) }` (tag `ARTracker`, aman dipanggil di JVM test: bungkus `android.util.Log` dengan `runCatching`)

- [ ] **Step 1: Test gagal.**
  - `validConfig_hasNoErrors`: endpoint `https://art.exlm.my.id`, key `"art_live_" + "a".repeat(43)` → `validate()` kosong.
  - `secretKey_isRejected`: key `art_sec_…` → salah satu error mengandung `"art_live_"`.
  - `keyLength_bounds`: panjang 31 dan 129 → error; 32 dan 128 → valid.
  - `endpoint_mustBeHttp`: `"ftp://x"` dan `""` → error.
  - `flushInterval_below1000_isError`.
  - `sampleRate_outside0to1_isError` (replay dan network).
  - `fps_isClamped`: `ReplayConfig(framesPerSecond = 10.0).effectiveFps()` == 4.0; `0.1` → 0.5.
  - `javaBuilder_matchesDefaults`: `Builder(e, k).build().replay.enabled == false` dan `privacy.maskAllInputs == true`.
- [ ] **Step 2–4:** run FAIL → implement → run PASS (`--tests "id.art.tracker.ARTrackerConfigTest"`).
- [ ] **Step 5: Commit** `feat(sdk): add public config and validation`.

### Task 4: Model item & ItemFactory

**Files:**
- Create: `TelemetryItem.kt`, `LogLevel.kt`, `core/ItemFactory.kt`
- Test: `core/ItemFactoryTest.kt`

**Interfaces:**
- Consumes: `UuidV7`, `Clock`, `Json`, `Iso8601` (Task 2).
- Produces:
  - `public enum class ItemType(internal val wire: String) { EVENT("event"), LOG("log"), SESSION("session") }`
  - `public enum class LogLevel(internal val wire: String) { DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error"), FATAL("fatal") }`
  - `public data class TelemetryItem(val id: String, val type: ItemType, val name: String, val level: LogLevel?, val message: String?, val timestampMs: Long, val sessionId: String, val userId: String?, val attributes: Map<String, Any?>)`
  - `internal fun TelemetryItem.toJson(): String`: key `id,type,name,level?,message?,timestamp,session_id,user_id?,attributes`. `level` hanya ditulis untuk `LOG`.
  - `internal fun TelemetryItem.priority(): Int`: 0 bila `level == FATAL`, 1 bila `ERROR`, 3 bila `name == "replay_chunk"`, selain itu 2.
  - `internal class ItemFactory(private val clock: Clock, private val sessionId: () -> String, private val userId: () -> String?)` dengan `fun event(name: String, attrs: Map<String, Any?> = emptyMap()): TelemetryItem`, `fun log(level: LogLevel, name: String, message: String?, attrs: Map<String, Any?> = emptyMap()): TelemetryItem`, `fun session(name: String, attrs: Map<String, Any?> = emptyMap()): TelemetryItem`. Truncation name 255, message 4096, sessionId/userId 128.

- [ ] **Step 1: Test gagal.**
  - `event_hasUuidV7AndTimestampFromClock`: `FakeClock(1000)` → `timestampMs == 1000`, `id[14] == '7'`.
  - `longName_truncatedTo255`, `longMessage_truncatedTo4096`, `longUserId_truncatedTo128`.
  - `toJson_eventOmitsLevel`: parse dengan `org.json.JSONObject`: `!has("level")`, `getString("type") == "event"`, `getString("timestamp") == "1970-01-01T00:00:01.000Z"`.
  - `toJson_logIncludesLevel`: `getString("level") == "error"`.
  - `priority_ordering`: fatal 0, error 1, event 2, `session("replay_chunk")` 3, `log(INFO…)` 2.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add telemetry item model and factory`.

### Task 5: AttributeRedactor & UrlScrubber (port dari web SDK)

**Files:**
- Create: `privacy/AttributeRedactor.kt`, `privacy/UrlScrubber.kt`
- Test: `privacy/AttributeRedactorTest.kt`, `privacy/UrlScrubberTest.kt`
- Referensi: `../artracker-web-sdk/src/privacy/redactor.ts`, `urlScrubber.ts`, dan `__tests__`-nya. Regex, `SHORT_WORDS`, `LONG_SUBSTRINGS`, validasi NIK, dan aturan Luhn **disalin** agar perilaku sama persis.

**Interfaces:**
- Produces:
  - `internal object AttributeRedactor { const val REDACTED = "[redacted]"; const val MAX_DEPTH = 6; const val MAX_NODES = 1000; const val MAX_BYTES = 24 * 1024; fun redact(attrs: Map<String, Any?>): Map<String, Any?>; fun redactString(s: String): String }`
  - `internal object UrlScrubber { fun scrub(url: String): String }`

- [ ] **Step 1: Test gagal.** Port seluruh kasus dari `artracker-web-sdk/src/privacy/__tests__/redactor*.test.ts` dan `urlScrubber*.test.ts` menjadi test JUnit dengan input/output identik. Tambahan khusus Android:
  - `sensitiveKey_redacted`: `{"password":"x","userToken":"y","name":"Budi"}` → password dan userToken `[redacted]`, name tetap.
  - `luhnCard_redacted_nonLuhnKept`: `"4111 1111 1111 1111"` mengandung `[redacted]`; `"1234 5678 9012 3456"` (bukan Luhn) mengikuti perilaku web untuk NIK/kartu.
  - `depthBeyond6_isReplacedWithMarker`: map bersarang 8 level → level ke-7 bukan map lagi (penanda sama dengan web).
  - `overBudget_isTrimmedTo24KB`: map 100 key × 1 KB → `Json.encode(result).length <= 24*1024` dan `result["_truncated"] == true`.
  - `UrlScrubberTest.tokenParam`: `https://a.com/p?token=abc&q=1` → `token=[redacted]`, `q=1` tetap.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): port attribute redactor and url scrubber from web sdk`.

---

## Milestone B — Core State

### Task 6: KeyValueStore, SessionManager, IdentityStore, KillSwitch

**Files:**
- Create: `core/KeyValueStore.kt` (interface + `SharedPrefsStore` + `InMemoryStore` di test fixtures), `core/SessionManager.kt`, `core/IdentityStore.kt`, `core/KillSwitch.kt`
- Test: `core/SessionManagerTest.kt`, `core/IdentityStoreTest.kt`, `core/KillSwitchTest.kt`, `testutil/InMemoryStore.kt`, `testutil/FakeClock.kt`

**Interfaces:**
- Produces:
  - `internal interface KeyValueStore { fun getString(k: String): String?; fun putString(k: String, v: String?); fun getLong(k: String, def: Long): Long; fun putLong(k: String, v: Long) }`; `internal class SharedPrefsStore(context: Context, name: String) : KeyValueStore` (`commit()` untuk key kill-switch, `apply()` selain itu).
  - `internal class SessionManager(store: KeyValueStore, clock: Clock, timeoutMs: Long, maxDurationMs: Long = 86_400_000, listener: Listener)` dengan `interface Listener { fun onSessionStart(id: String); fun onSessionEnd(id: String, durationMs: Long) }`, `fun currentId(): String`, `fun onForeground()`, `fun onBackground()`, `fun rotate()`.
  - `internal class IdentityStore(store: KeyValueStore)` dengan `val anonymousId: String`, `var userId: String?`, `var traits: Map<String, Any?>` (hanya di memori), `fun reset()`.
  - `internal class KillSwitch(store: KeyValueStore, clock: Clock)` dengan `fun isActive(): Boolean` (= opted-out atau `now < disabledUntil`), `fun trip(durationMs: Long = 3_600_000)`, `var optedOut: Boolean` (persist).

- [ ] **Step 1: Test gagal.**
  - `SessionManagerTest.firstCall_startsSession` (listener start 1×).
  - `sameIdWithinTimeout`: background, maju 29 menit, foreground → id sama.
  - `newIdAfterTimeout`: maju 30 menit + 1 ms → id baru, `onSessionEnd` dipanggil untuk id lama.
  - `hardCap24h`: tetap foreground, maju 24 jam + 1 ms, `currentId()` → id baru.
  - `restoredAcrossInstances`: instance kedua dengan store yang sama dalam timeout → id sama, tanpa `onSessionStart`.
  - `rotate_endsAndStarts`.
  - `IdentityStoreTest.anonymousId_stable`, `reset_clearsUserAndRegeneratesAnonymousId`.
  - `KillSwitchTest.trip_activeForOneHour` (aktif di +59 menit, tidak aktif di +60 menit + 1 ms), `optOut_persists`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add session, identity and kill switch`.

### Task 7: DeviceContext & ClassAvailability

**Files:**
- Create: `core/DeviceContext.kt`, `core/ClassAvailability.kt`
- Test: `core/DeviceContextTest.kt` (Robolectric), `core/ClassAvailabilityTest.kt`

**Interfaces:**
- Produces:
  - `internal class DeviceContext(context: Context, configuredAppVersion: String?)` dengan `val appVersion: String` (config → `PackageInfo.versionName` → `"unknown"`), `fun sessionStartAttributes(): Map<String, Any?>` berkunci tepat: `platform, os_version, api_level, manufacturer, model, app_version, app_build, screen (map w,h,density), locale, tz, network, is_emulator, sdk_version`; `fun envelopeContext(environment: String): Map<String, Any?>` = `{environment, app_version, sdk_platform:"android"}`.
  - `internal object ClassAvailability { val okHttp: Boolean; val fragment: Boolean; val compose: Boolean; val navigation: Boolean; internal fun has(className: String): Boolean }` (lazy `Class.forName(…, false, loader)`).

- [ ] **Step 1: Test gagal.**
  - `DeviceContextTest.sessionStartAttributes_hasAllKeys` (set kunci sama persis dengan daftar di atas) dan `platform == "android"`.
  - `envelopeContext_containsOnlyThreeKeys`.
  - `appVersion_prefersConfig`.
  - `ClassAvailabilityTest.missingClass_isFalse`: `has("does.not.Exist") == false`; `has("java.lang.String") == true`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add device context and optional dependency detection`.

---

## Milestone C — Transport & Offline

### Task 8: ItemQueue & EventStore (SQLite)

**Files:**
- Create: `transport/ItemQueue.kt`, `transport/EventStore.kt`
- Test: `transport/EventStoreTest.kt` (Robolectric)

**Interfaces:**
- Produces:
  - `internal data class StoredItem(val id: String, val priority: Int, val createdAtMs: Long, val payloadJson: String) { val sizeBytes: Int }`
  - `internal data class Batch(val id: String, val items: List<StoredItem>)`
  - `internal interface ItemQueue { fun insert(items: List<StoredItem>); fun claimBatch(maxItems: Int = 50, maxBytes: Int = 131_072): Batch?; fun complete(batchId: String); fun release(batchId: String); fun pendingCount(): Int; fun resetOrphanedBatches(); fun purgeOlderThan(cutoffMs: Long) }`
  - `internal class EventStore(context: Context, clock: Clock, maxBytes: Long = 5L * 1024 * 1024, maxRows: Int = 5000, dbName: String = "artracker.db") : ItemQueue`. Skema persis seperti spec §9.2. Insert memakai `INSERT OR IGNORE`. `complete` = DELETE baris batch. `release` = `batch_id = NULL, attempts = attempts + 1`.

- [ ] **Step 1: Test gagal.**
  - `claim_ordersByPriorityThenAge`.
  - `claim_respects50Items` dan `claim_respects128KB` (item 10 KB × 20 → batch berisi 12 item; item pertama selalu diambil walau >128 KB sendirian).
  - `claimedItems_notClaimedTwice`.
  - `release_makesItemsClaimableAndIncrementsAttempts`.
  - `complete_deletes`.
  - `duplicateId_ignored`.
  - `eviction_dropsReplayFirstAndNeverPriority0`: maxRows 5, isi 3 prioritas-0 + 3 prioritas-3, lalu insert 1 prioritas-2 → semua prioritas-0 tetap ada, jumlah baris = 5.
  - `orphanedBatches_resetOnStart`.
  - `purgeOlderThan_keepsPriority0`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add sqlite write-ahead event store`.

### Task 9: PayloadEncoder (envelope + gzip)

**Files:**
- Create: `transport/PayloadEncoder.kt`
- Test: `transport/PayloadEncoderTest.kt`

**Interfaces:**
- Consumes: `StoredItem` (Task 8), `Json`/`RawJson`/`Iso8601` (Task 2).
- Produces: `internal class PayloadEncoder(private val context: () -> Map<String, Any?>)` dengan `fun envelopeJson(items: List<StoredItem>, sentAtMs: Long): String` dan `fun encodeGzip(items: List<StoredItem>, sentAtMs: Long): ByteArray`. Stream ke `GZIPOutputStream` lewat `OutputStreamWriter(UTF-8)` tanpa String perantara.

- [ ] **Step 1: Test gagal.**
  - `gzip_hasMagicBytes`: dua byte pertama `0x1f, 0x8b`.
  - `roundTrip`: gunzip → `JSONObject`: `sent_at` sesuai `Iso8601`, `sdk.name == "artracker-android"`, `sdk.version == SdkInfo.VERSION`, `context.sdk_platform == "android"`, `items.length() == 3`, id item ke-0 sama dengan input.
  - `gzipAndPlain_produceSameJson`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add gzip envelope encoder`.

### Task 10: IngestClient, SendResult, Backoff

**Files:**
- Create: `transport/SendResult.kt`, `transport/IngestClient.kt`, `transport/Backoff.kt`
- Test: `transport/IngestClientTest.kt` (MockWebServer, JVM murni), `transport/BackoffTest.kt`

**Interfaces:**
- Produces:
  - `internal sealed interface SendResult { object Accepted; data class Drop(val status: Int); object Unauthorized; object TooLarge; data class RetryAfter(val delayMs: Long?); data class NetworkError(val cause: Throwable) }` (semua implement `SendResult`).
  - `internal fun interface Sender { fun send(gzipBody: ByteArray): SendResult }`
  - `internal class IngestClient(endpoint: String, apiKey: String, connectTimeoutMs: Int = 10_000, readTimeoutMs: Int = 15_000) : Sender`. URL = `endpoint.trimEnd('/') + "/api/v1/ingest"`. Mapping: 2xx → Accepted; 401/403 → Unauthorized; 413 → TooLarge; 429/503 → `RetryAfter(header detik × 1000 atau null)`; 4xx lain → `Drop`; 5xx lain → `RetryAfter(null)`; `IOException` → NetworkError.
  - `internal class Backoff(baseMs: Long = 2_000, maxMs: Long = 300_000, random: java.util.Random = java.util.Random())` dengan `fun delayFor(attempt: Int): Long` (full jitter `random(0..min(max, base·2^attempt))`) dan `fun reset()`.

- [ ] **Step 1: Test gagal.**
  - `sendsRequiredHeaders`: `X-Artracker-Key`, `Content-Encoding: gzip`, `Content-Type: application/json`, `User-Agent` diawali `artracker-android/`, path `/api/v1/ingest`, method POST.
  - `endpointWithTrailingSlash_noDoubleSlash`.
  - Status mapping: `202 → Accepted`, `400 → Drop(400)`, `401 → Unauthorized`, `413 → TooLarge`, `429 + Retry-After: 7 → RetryAfter(7000)`, `503 + Retry-After: 5 → RetryAfter(5000)`, `429 tanpa header → RetryAfter(null)`, `500 → RetryAfter(null)`.
  - `serverDown_isNetworkError` (`server.shutdown()` lalu kirim).
  - `BackoffTest.neverExceedsMax` (attempt 0..30) dan `attempt0_withinBase`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add ingest http client and backoff`.

### Task 11: Flusher (orkestrasi kirim)

**Files:**
- Create: `transport/Flusher.kt`, `transport/BackgroundScheduler.kt` (interface saja)
- Test: `transport/FlusherTest.kt` (fake `ItemQueue` in-memory di `testutil/FakeQueue.kt`, fake `Sender`)

**Interfaces:**
- Consumes: `ItemQueue`, `PayloadEncoder`, `Sender`, `SendResult`, `Backoff`, `KillSwitch`, `Clock`.
- Produces:
  - `internal fun interface BackgroundScheduler { fun scheduleFlush() }`
  - `internal enum class FlushOutcome { EMPTY, SENT, DROPPED, PAUSED, DISABLED, FAILED }`
  - `internal class Flusher(queue: ItemQueue, encoder: PayloadEncoder, sender: Sender, killSwitch: KillSwitch, clock: Clock, backoff: Backoff, scheduler: BackgroundScheduler)` dengan `fun flushOnce(): FlushOutcome`, `suspend fun flushAll(maxBatchesPerSecond: Int = 5): FlushOutcome` (berhenti pada outcome selain SENT/DROPPED), dan `val pausedUntilMs: Long`.
  - Aturan: kill-switch aktif → `DISABLED` tanpa claim; `now < pausedUntil` → `PAUSED`; Accepted → `complete` + `backoff.reset()`; Drop → `complete` (buang); Unauthorized → `release` + `killSwitch.trip()` → `DISABLED`; TooLarge → bila batch >1 item: `release`, claim berikutnya memakai `maxItems = size/2`; bila 1 item: `complete` (buang) → `DROPPED`; RetryAfter → `release`, `pausedUntil = now + (delay ?: backoff)` → `PAUSED`; NetworkError → `release`, `pausedUntil = now + backoff`, `scheduler.scheduleFlush()` → `FAILED`.

- [ ] **Step 1: Test gagal.** Satu test per aturan di atas, plus:
  - `tooLarge_splitsUntilSent`: sender menolak batch >2 item dengan TooLarge → 5 item akhirnya terkirim semua dalam ≥3 request.
  - `flushAll_respectsRateLimit`: 12 batch dengan `FakeClock` + `runTest` virtual time → ≥2 detik virtual.
  - `ids_stableAcrossRetry`: NetworkError lalu Accepted → id item di request kedua sama dengan request pertama.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add flusher with retry, split and kill switch`.

### Task 12: EventPipeline, FlushWorker, TransportConfigStore

**Files:**
- Create: `transport/EventPipeline.kt`, `transport/FlushWorker.kt`, `transport/WorkManagerScheduler.kt`, `transport/TransportConfigStore.kt`
- Test: `transport/EventPipelineTest.kt`, `transport/FlushWorkerTest.kt` (Robolectric + work-testing)

**Interfaces:**
- Consumes: `TelemetryItem.toJson/priority`, `AttributeRedactor`, `BeforeSend`, `ItemQueue`, `Flusher`.
- Produces:
  - `internal class EventPipeline(scope: CoroutineScope, queue: ItemQueue, flusher: Flusher, clock: Clock, beforeSend: BeforeSend?, flushIntervalMs: Long)` dengan `fun enqueue(item: TelemetryItem): Boolean` (non-blocking; `Channel(1000, DROP_OLDEST)`), `fun startTimer()`, `fun stopTimer()`, `fun requestFlush()`, `@VisibleForTesting suspend fun awaitIdle()`.
  - Urutan per item di consumer: `beforeSend` (null → buang; exception → item asli dipakai + log) → `AttributeRedactor.redact` **kecuali** `name == "replay_chunk"` → `StoredItem`. Persist berkelompok: setiap 20 item atau 1 000 ms. `requestFlush()` dipicu segera bila ada item prioritas 0 atau `pendingCount() >= 50`.
  - `internal class WorkManagerScheduler(context: Context) : BackgroundScheduler` → `enqueueUniqueWork("artracker-flush", ExistingWorkPolicy.KEEP, OneTimeWorkRequest<FlushWorker>` dengan `NetworkType.CONNECTED`, backoff `EXPONENTIAL` 30 s).
  - `internal object FlusherRegistry { @Volatile var current: Flusher? }`: diisi oleh core di Task 13, sehingga Task 12 tidak bergantung pada `ARTrackerCore`.
  - `internal class FlushWorker(ctx, params) : CoroutineWorker`: memakai `FlusherRegistry.current`; bila null, membangun Flusher dari `TransportConfigStore`; tidak ada config → `Result.success()`. Outcome `FAILED`/`PAUSED` → `Result.retry()`.
  - `internal class TransportConfigStore(store: KeyValueStore)` dengan `fun save(endpoint: String, apiKey: String, environment: String, appVersion: String)` dan `fun load(): Saved?`.

- [ ] **Step 1: Test gagal.**
  - `EventPipelineTest.redactionApplied`: props `{"password":"x"}` → payload tersimpan mengandung `[redacted]`.
  - `beforeSendNull_drops`; `beforeSendThrows_keepsOriginal`.
  - `replayChunk_skipsRedactor`: attributes berisi `"a@b.com"` tetap utuh untuk `replay_chunk`.
  - `fatal_triggersImmediateFlush` (fake Flusher/Sender mencatat panggilan dalam virtual time <1 s).
  - `enqueue_neverBlocks`: 5000 enqueue saat consumer ditahan → semua `enqueue` kembali, antrean ≤1000.
  - `FlushWorkerTest.schedule_createsUniqueWorkWithConnectedConstraint` (`WorkManagerTestInitHelper`, cek `WorkInfo` & constraint).
  - `worker_withoutCoreOrConfig_succeeds`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add event pipeline and workmanager flush`.

---

## Milestone D — Public API & Lifecycle

### Task 13: ARTrackerCore & facade `ARTracker`

**Files:**
- Create: `core/ARTrackerCore.kt`, `ARTracker.kt`, `Diagnostics.kt`
- Test: `ARTrackerFacadeTest.kt` (Robolectric + MockWebServer)

**Interfaces:**
- Consumes: semua komponen Task 2–12.
- Produces:
  - `public object ARTracker` dengan `@JvmStatic`: `init(context: Context, config: ARTrackerConfig)`, `identify(userId: String, traits: Map<String, Any?> = emptyMap())`, `track(name: String, properties: Map<String, Any?> = emptyMap())`, `screen(name: String)`, `log(level: LogLevel, message: String, attributes: Map<String, Any?> = emptyMap())`, `captureException(throwable: Throwable, attributes: Map<String, Any?> = emptyMap())` (diisi di Task 15), `flush()`, `reset()`, `optOut(enabled: Boolean)`, `startReplay()`, `stopReplay()` (diisi di Task 23), `isInitialized(): Boolean`, `diagnostics(): Diagnostics`, dan `@VisibleForTesting internal fun resetForTest()`.
  - `public data class Diagnostics(val initialized: Boolean, val sessionId: String?, val pendingItems: Int, val lastSendResult: String?, val disabled: Boolean)`
  - `internal class ARTrackerCore` dengan `companion object { fun instanceOrNull(): ARTrackerCore? }`, serta properti `items: ItemFactory`, `pipeline: EventPipeline`, `flusher: Flusher`, `session: SessionManager`, `identity: IdentityStore`, `device: DeviceContext`, `config: ARTrackerConfig`, `app: Application`, `scope: CoroutineScope`.
  - Core mengisi `FlusherRegistry.current` (Task 12) saat init dan mengosongkannya di `resetForTest()`.
  - `init`: `validate()` gagal → log + no-op. Bagian sinkron hanya membuat objek dan mendaftarkan hook (crash handler & lifecycle di Task 14–15). Membuka DB, `resetOrphanedBatches`, `purgeOlderThan(now − 7 hari)`, `TransportConfigStore.save`, dan flush awal berjalan di `scope`. `identify` memancarkan event `$identify` (traits sebagai attributes). `reset()` menghapus user dan memutar sesi. Session start/end memancarkan `session_start` (attributes `DeviceContext.sessionStartAttributes()`) / `session_end` (`duration_ms`).

- [ ] **Step 1: Test gagal.**
  - `beforeInit_allApisAreNoOps`: panggil semua fungsi publik sebelum `init` → tidak ada exception, `isInitialized() == false`.
  - `invalidConfig_staysUninitialized`.
  - `init_isIdempotent`: init kedua dengan key berbeda diabaikan.
  - `trackAndFlush_reachesServer`: init dengan endpoint MockWebServer, `track("checkout_clicked", mapOf("total" to 120000))`, `flush()`, `awaitIdle()` → request diterima; payload gunzip memuat item `name == "checkout_clicked"`, `attributes.total == 120000`, dan item `session_start`.
  - `identify_setsUserIdOnLaterItems` dan memancarkan `$identify`.
  - `reset_clearsUserAndChangesSession`.
  - `concurrentTrack_fromEightThreads_allItemsQueued`: 8 thread × 100 `track` → setelah `awaitIdle`, `diagnostics().pendingItems + terkirim == 800 + item sesi`.
  - `optOut_stopsSending`.
  - `init_mainThreadCost_under50msInRobolectric` (indikatif; anggaran sesungguhnya 2 ms diukur di Task 25).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add ARTracker facade and core wiring`.

### Task 14: Lifecycle (app, activity, fragment, navigation)

**Files:**
- Create: `lifecycle/ScreenTracker.kt`, `lifecycle/AppLifecycleObserver.kt`, `lifecycle/ActivityTracker.kt`, `lifecycle/FragmentTracker.kt`, `lifecycle/NavTracker.kt`; modify `ARTracker.kt` (tambah `trackNavController`), `core/ARTrackerCore.kt` (registrasi).
- Test: `lifecycle/ScreenTrackerTest.kt`, `lifecycle/AppLifecycleObserverTest.kt`, `lifecycle/ActivityTrackerTest.kt` (Robolectric), `lifecycle/FragmentTrackerTest.kt` (Robolectric)

**Interfaces:**
- Produces:
  - `internal class ScreenTracker(emit: (TelemetryItem) -> Unit, items: ItemFactory)` dengan `fun onScreen(name: String, type: String)`: memancarkan `event`/`screen_view` `{screen, previous, type}`; nama sama berturut-turut tidak dipancarkan ulang. Menyimpan `currentScreen: String?` (dipakai breadcrumbs & replay).
  - `internal class AppLifecycleObserver(onForeground: () -> Unit, onBackground: () -> Unit) : DefaultLifecycleObserver`. Core: foreground → `session.onForeground()`, event `app_foreground`, `pipeline.startTimer()`; background → event `app_background`, `stopTimer()`, `requestFlush()`, `scheduler.scheduleFlush()`, `session.onBackground()`.
  - `internal class ActivityTracker(screens: ScreenTracker, listener: WindowListener?) : Application.ActivityLifecycleCallbacks` dengan `interface WindowListener { fun onActivityResumed(a: Activity); fun onActivityPaused(a: Activity) }` (dipakai replay di Task 23). Nama layar = `@ARTrackerScreenName` (anotasi publik, `RUNTIME`, target CLASS) bila ada, selain itu `simpleName`. Fragment callback didaftarkan di `onActivityCreated` hanya bila `ClassAvailability.fragment && a is FragmentActivity`.
  - `public annotation class ARTrackerScreenName(val value: String)`
  - `internal class FragmentTracker(screens: ScreenTracker) : FragmentManager.FragmentLifecycleCallbacks()` (`onFragmentResumed` → `type "fragment"`).
  - `ARTracker.trackNavController(navController: Any)`: parameter bertipe `Any` agar facade tidak mereferensikan kelas Navigation. Cast di `NavTracker` hanya bila `ClassAvailability.navigation`; route/label destination → `onScreen(…, "compose")`.

- [ ] **Step 1: Test gagal.**
  - `ScreenTrackerTest.emitsWithPrevious`, `duplicateSuppressed`.
  - `AppLifecycleObserverTest.stopThenStart_callsCallbacks` (`TestLifecycleOwner` atau panggil langsung).
  - `ActivityTrackerTest.resumedActivity_emitsScreenView` (`Robolectric.buildActivity(…).setup()`), `annotation_overridesName`.
  - `FragmentTrackerTest.fragmentResume_emitsFragmentScreen`.
  - `ARTrackerCoreTest.init_withoutOptionalDeps_doesNotTouchOptionalClasses`: dengan `ClassAvailability` di-override (`@VisibleForTesting internal var overrides`) menjadi false semua, init + resume activity tidak memanggil kode fragment/compose/nav (diverifikasi lewat counter).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add lifecycle and screen tracking`.

---

## Milestone E — Telemetry

### Task 15: Breadcrumbs, Crash, captureException

**Files:**
- Create: `telemetry/Breadcrumbs.kt`, `telemetry/Stacktrace.kt`, `telemetry/ErrorDeduper.kt`, `telemetry/CrashStore.kt`, `telemetry/CrashHandler.kt`, `telemetry/CrashRecovery.kt`; modify `ARTrackerCore.kt`, `ARTracker.kt` (`captureException`), `ScreenTracker` (breadcrumb).
- Test: masing-masing `*Test.kt`; `telemetry/CrashRecoveryTest.kt` (Robolectric)

**Interfaces:**
- Produces:
  - `internal class Breadcrumbs(capacity: Int = 30)` dengan `fun add(type: String, message: String, data: Map<String, Any?> = emptyMap())` (thread-safe) dan `fun snapshot(): List<Map<String, Any?>>` (`{ts, type, message, data}`).
  - `internal object Stacktrace { fun format(t: Throwable, maxBytes: Int = 16_384): String; fun fingerprint(t: Throwable): String }`. Format mencakup rantai `Caused by:`; fingerprint = SHA-1 hex dari nama kelas exception + 5 frame teratas (`className.method`), **tanpa** message dan nomor baris.
  - `internal class ErrorDeduper(maxPerFingerprint: Int = 10)` dengan `fun shouldReport(sessionId: String, fingerprint: String): Boolean`.
  - `internal class CrashStore(dir: File)` dengan `fun writeSync(id: String, itemJson: String)` (tulis `<id>.tmp` → `renameTo(<id>.json)`), `fun pending(): List<File>`, `fun read(f: File): String?`, `fun delete(f: File)`.
  - `internal class CrashHandler(previous: Thread.UncaughtExceptionHandler?, store: CrashStore, buildItemJson: (Thread, Throwable) -> Pair<String, String>) : Thread.UncaughtExceptionHandler` dan `companion fun install(…)`. Item crash: `log`/`fatal`, `name "crash"`, `message "<Class>: <msg>"`, attributes `{thread, exception_class, stacktrace, fingerprint, breadcrumbs, screen, app_version, mem_free_bytes}`.
  - `internal object CrashRecovery { fun recover(store: CrashStore, queue: ItemQueue) }`: payload dimasukkan langsung sebagai `StoredItem` prioritas 0 (sudah JSON final), lalu file dihapus. Dipanggil di awal job background `init` dan diikuti `requestFlush()`.
  - `captureException` → `log`/`error`, `name "exception"`, `attributes.handled = true`, melalui `ErrorDeduper`.

- [ ] **Step 1: Test gagal.**
  - `BreadcrumbsTest.ringBuffer_keepsLast30`.
  - `StacktraceTest.includesCauseChain`, `truncatedTo16KB` (exception dengan 5000 frame), `fingerprint_ignoresMessage` (dua exception, message berbeda, call site sama → fingerprint sama).
  - `ErrorDeduperTest.eleventhIdentical_isSuppressed`.
  - `CrashStoreTest.write_isAtomic_noTmpLeft`.
  - `CrashHandlerTest.writesFileThenDelegates`.
  - `CrashHandlerTest.previousHandler_calledExactlyOnce_evenWhenWriteFails` (`buildItemJson` melempar).
  - `CrashHandlerTest.reentrantCrash_delegatesImmediately`.
  - `CrashRecoveryTest.crashFile_becomesPriority0Item_andFileDeleted`.
  - `CrashRecoveryTest.offlineCrash_sentOnNextInit` (acceptance criteria #2, Robolectric): init #1 dengan server mati → panggil handler secara langsung → `resetForTest()` → init #2 dengan MockWebServer hidup → `awaitIdle()` → request memuat item `level "fatal"`, `name "crash"`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add crash capture with offline recovery`.

### Task 16: ExitInfoCollector (ANR / native crash, API 30+)

**Files:**
- Create: `telemetry/ExitInfoCollector.kt`; modify `ARTrackerCore.kt` (dipanggil di job background `init`)
- Test: `telemetry/ExitInfoCollectorTest.kt` (Robolectric `@Config(sdk = [30])` dan `[29]`)

**Interfaces:**
- Produces: `internal class ExitInfoCollector(context: Context, store: KeyValueStore, items: ItemFactory)` dengan `fun collect(): List<TelemetryItem>`. Reason → name: `REASON_ANR → "anr"`, `REASON_CRASH_NATIVE → "native_crash"`, `REASON_LOW_MEMORY → "low_memory_kill"`; level `FATAL` untuk anr/native_crash dan `WARN` untuk low_memory. Attributes `{reason, description, importance, pss, rss, exit_timestamp, trace (ANR, ≤16 KB)}`. Timestamp terakhir yang diproses disimpan di key `exitinfo_last_ts`.

- [ ] **Step 1: Test gagal.** Dengan `ShadowActivityManager.addApplicationExitInfo(ApplicationExitInfoBuilder…)`:
  - `anr_becomesFatalItem`.
  - `secondCollect_returnsNothingNew`.
  - `api29_returnsEmpty`.
  - `crashJava_isIgnored` (sudah ditangani CrashHandler).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): collect anr and native crash exit reasons`.

### Task 17: ARTrackerOkHttpInterceptor

**Files:**
- Create: `telemetry/okhttp/HttpRecord.kt`, `telemetry/okhttp/ARTrackerOkHttpInterceptor.kt`
- Test: `telemetry/okhttp/ARTrackerOkHttpInterceptorTest.kt` (MockWebServer + OkHttpClient, JVM)

**Interfaces:**
- Produces:
  - `internal data class HttpRecord(val method: String, val url: String, val host: String, val status: Int?, val durationMs: Long, val requestBytes: Long?, val responseBytes: Long?, val protocol: String?, val error: String?)` dengan `fun level(): LogLevel` (`null`/≥500 → ERROR, 4xx → WARN, selain itu INFO).
  - `internal fun interface HttpReporter { fun report(record: HttpRecord) }`. Default `CoreHttpReporter` meneruskan ke core: item `log`, `name "http_request"`, attributes `{method, url (UrlScrubber), host, status, duration_ms, req_bytes, res_bytes, protocol, error?}`, ditambah breadcrumb `http`. Menghormati `NetworkConfig.sampleRate`, `captureOnlyErrors`, `ignoreHosts`, serta host endpoint SDK.
  - `public class ARTrackerOkHttpInterceptor : okhttp3.Interceptor` dengan konstruktor publik tanpa argumen dan `internal constructor(reporter: HttpReporter, clock: Clock)`.

- [ ] **Step 1: Test gagal.**
  - `success_reportsInfo`, `notFound_reportsWarn`, `serverError_reportsError`.
  - `ioException_isRethrown_andReported`: server diputus (`SocketPolicy.DISCONNECT_AT_START`) → exception yang sama sampai ke caller, record `error != null`, `status == null`.
  - `responseBody_stillReadableByCaller` (body `"hello"` terbaca utuh).
  - `sensitiveQuery_isScrubbed`.
  - `ignoredHost_notReported` dan `sdkEndpointHost_notReported`.
  - `noCore_isNoOp` (interceptor publik dipakai tanpa `init`).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add okhttp network telemetry interceptor`.

---

## Milestone F — Privacy UI & Replay

### Task 18: Wireframe model, MaskingPolicy, ViewMaskRules, API mask View

**Files:**
- Create: `replay/model/Wireframe.kt`, `privacy/MaskingPolicy.kt`, `privacy/ViewMaskRules.kt`, `privacy/ARTrackerMask.kt` (bagian View), `src/main/res/values/ids.xml`
- Test: `privacy/ViewMaskRulesTest.kt` (Robolectric)

**Interfaces:**
- Produces:
  - `internal enum class WireframeType { CONTAINER, TEXT, INPUT, BUTTON, IMAGE, WEB, SURFACE, COMPOSE_BLOCK }`
  - `internal data class Wireframe(val id: Int, val x: Int, val y: Int, val width: Int, val height: Int, val type: WireframeType, val text: String? = null, val textColor: Int? = null, val textSizeSp: Float? = null, val background: Int? = null, val alpha: Float = 1f, val masked: Boolean = false, val summarized: Boolean = false, val children: List<Wireframe> = emptyList())` (koordinat dalam dp, relatif ke layar)
  - `internal enum class MaskDecision { NONE, MASK, BLOCK }`
  - `internal class MaskingPolicy(config: PrivacyConfig)` dengan `fun forView(view: View, parentMasked: Boolean): MaskDecision` dan `companion fun maskText(s: CharSequence): String` (`"*".repeat(min(len, 32))`).
  - `internal object ViewMaskRules` dengan `fun isPassword(v: View): Boolean`, `fun isSensitiveInput(v: View): Boolean` (number/phone class, autofill hints sesuai spec §7.1), `fun explicitMask(v: View): Boolean?` (`true` = mask, `false` = unmask, `null` = tidak ada; membaca `R.id.artracker_mask` lalu tag string `"artracker-mask"` / `"artracker-unmask"`), `fun isBlocked(v: View): Boolean` (WebView, SurfaceView, TextureView, VideoView, nama kelas mengandung `MapView`).
  - Publik: `public fun View.artrackerMask()` dan `public fun View.artrackerUnmask()` (`@file:JvmName("ARTrackerMask")`).
  - Urutan keputusan: password → MASK (abaikan unmask); `isBlocked` → BLOCK; explicit false → NONE; explicit true / `parentMasked` / kelas sensitif → MASK; EditText && (`maskAllInputs` || `isSensitiveInput`) → MASK; `maskAllText` && TextView → MASK; selain itu NONE.

- [ ] **Step 1: Test gagal.**
  - `passwordEditText_masked_evenWithUnmask`.
  - `numberAndPhone_masked`, `autofillCreditCard_masked`.
  - `plainEditText_maskedWhenMaskAllInputs_trueByDefault`, `plainEditText_notMaskedWhenMaskAllInputsFalse`.
  - `xmlStringTag_masksChildrenViaParent` (`parentMasked = true` → child TextView MASK).
  - `resourceIdTag_and_extension_mask`.
  - `unmask_overridesMaskAllInputs_forNonPassword`.
  - `sensitiveClass_masked`.
  - `webView_blocked`.
  - `maskAllText_masksTextView`.
  - `maskText_lengthCappedAt32`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add view privacy masking engine`.

### Task 19: RootViewLocator & ViewWalker

**Files:**
- Create: `replay/capture/RootViewLocator.kt`, `replay/capture/ViewWalker.kt`
- Test: `replay/capture/ViewWalkerTest.kt` (Robolectric)

**Interfaces:**
- Consumes: `Wireframe`, `MaskingPolicy`.
- Produces:
  - `internal object RootViewLocator { fun roots(fallback: Activity?): List<View> }` (reflection `android.view.WindowManagerGlobal#getInstance().mViews` dalam `runCatching`; fallback `listOfNotNull(fallback?.window?.decorView)`).
  - `internal class ViewWalker(policy: MaskingPolicy, composeWalker: ComposeWalkerApi?, maxNodes: Int = 2000, maxDepth: Int = 50)` dengan `fun walk(root: View): Wireframe?`.
  - `internal fun interface ComposeWalkerApi { fun walk(view: View, parentMasked: Boolean): Wireframe? }` (implementasi di Task 20; null bila `!ClassAvailability.compose`).
  - Aturan: lewati `visibility != VISIBLE`, ukuran 0, atau alpha 0. TextView yang di-mask → `text = maskText(...)`, `masked = true`. BLOCK → node tanpa anak/teks, `masked = true`. ImageView → `IMAGE` tanpa bitmap. Saat batas node tercapai, subtree sisa menjadi satu node `summarized = true`.

- [ ] **Step 1: Test gagal.**
  - `textAndPassword`: LinearLayout > TextView("Hello") + EditText(password, "secret") → node TEXT "Hello" dan INPUT "******", `masked`; `Json.encode(wireframe-as-map)` **tidak** mengandung `"secret"` (helper `Wireframe.toDebugString()`).
  - `goneView_excluded`.
  - `boundsInDp`: density 2.0, view 200×100 px di (20,40) → `x=10, y=20, width=100, height=50`.
  - `over2000Nodes_isCappedWithSummary`: 2500 TextView → total node ≤2001 dan ada `summarized`.
  - `imageView_hasNoBitmapData`.
  - `rootViewLocator_fallsBackToDecorView`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add view hierarchy walker`.

### Task 20: Compose — ComposeMaskRules, Modifier mask, ComposeWalker

**Files:**
- Create: `privacy/ComposeMaskRules.kt`, `privacy/ARTrackerComposeMask.kt`, `replay/capture/ComposeWalker.kt`
- Test: `replay/capture/ComposeWalkerTest.kt` (Robolectric + `createAndroidComposeRule<ComponentActivity>()`)

**Interfaces:**
- Produces:
  - `public fun Modifier.artrackerMask(): Modifier` dan `public fun Modifier.artrackerUnmask(): Modifier` (`semantics { this[ARTrackerMaskKey] = true/false }`, `@file:JvmName("ARTrackerComposeMask")`).
  - `internal val ARTrackerMaskKey: SemanticsPropertyKey<Boolean>`
  - `internal class ComposeWalker(policy: MaskingPolicy, semanticsAccess: SemanticsAccess = ReflectiveSemanticsAccess) : ComposeWalkerApi`
  - `internal fun interface SemanticsAccess { fun rootNode(view: View): SemanticsNode? }`. Implementasi `ReflectiveSemanticsAccess` memakai `(view as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode` dalam `runCatching`.
  - Aturan: `Password` → MASK; `ARTrackerMaskKey` true → MASK (subtree), false → unmask (kecuali password); `EditableText` && `maskAllInputs` → MASK; `Text` → TEXT; `Role.Button` → BUTTON; `Role.Image` → IMAGE. Bila `rootNode` null/exception → satu node `COMPOSE_BLOCK` seukuran view, `masked = true`.

- [ ] **Step 1: Test gagal.**
  - `text_isCaptured` (`Text("Hello")`).
  - `passwordSemantics_masked` (`Modifier.semantics { password() }`).
  - `artrackerMaskModifier_masksSubtree` (`Column(Modifier.artrackerMask()) { Text("Rp 1.000.000") }` → tidak ada `"Rp 1.000.000"`).
  - `textField_maskedByDefault`.
  - `reflectionFailure_yieldsComposeBlock` (`SemanticsAccess { throw IllegalStateException() }`).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add compose semantics walker and mask modifier`.

### Task 21: FrameScheduler

**Files:**
- Create: `replay/capture/FrameScheduler.kt`
- Test: `replay/capture/FrameSchedulerTest.kt` (Robolectric, `ShadowLooper`)

**Interfaces:**
- Produces: `internal class FrameScheduler(handler: Handler, clock: Clock, baseFps: Double, budgetMs: Long = 8, capture: () -> Unit)` dengan `fun start()`, `fun stop()`, `fun markDirty()`, `fun requestSoon(delayMs: Long = 100)`, `fun attach(root: View)` / `fun detach(root: View)` (memasang/melepas `ViewTreeObserver.OnDrawListener` yang hanya memanggil `markDirty()`), serta `val currentFps: Double`. Waktu capture diukur di dalam scheduler.
- Aturan: tick tiap `1000 / currentFps` ms; capture hanya bila dirty. Durasi >8 ms pada 3 tick berturut-turut → fps dibagi 2 (minimum 0.5). Setelah 30 s tanpa capture lambat → kembali ke `baseFps`. `stop()` membatalkan semua callback.

- [ ] **Step 1: Test gagal.**
  - `noDirty_noCapture`.
  - `dirty_capturesOncePerTick`.
  - `requestSoon_capturesAfter100ms`.
  - `slowCaptures_halveFps` (`FakeClock` maju 10 ms di dalam `capture`).
  - `recoversAfter30s`.
  - `stop_cancelsPending`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add replay frame scheduler with budget watchdog`.

### Task 22: WireframeDiffer & RrwebEncoder

**Files:**
- Create: `replay/diff/WireframeDiffer.kt`, `replay/rrweb/RrwebEncoder.kt`
- Test: `replay/diff/WireframeDifferTest.kt`, `replay/rrweb/RrwebEncoderTest.kt` (JVM)

**Interfaces:**
- Produces:
  - `internal data class WireframeDiff(val adds: List<Pair<Int /*parentId*/, Wireframe>>, val removes: List<Pair<Int /*parentId*/, Int /*id*/>>, val updates: List<Wireframe> /*bounds/style/alpha*/, val texts: List<Pair<Int, String>>)` dengan `val isEmpty: Boolean`.
  - `internal object WireframeDiffer { fun diff(prev: Wireframe, next: Wireframe): WireframeDiff }` (cocok berdasarkan `id`).
  - `internal class RrwebEncoder` (stateful per sesi replay; memetakan `Wireframe.id` → id node rrweb berurutan mulai 10; 1 = document, 2 = html, 3 = head, 4 = body) dengan `fun meta(href: String, widthDp: Int, heightDp: Int, ts: Long): Map<String, Any?>` (type 4), `fun fullSnapshot(root: Wireframe, ts: Long): Map<String, Any?>` (type 2; `div` dengan atribut `style` `position:absolute;left:Xpx;top:Ypx;width:Wpx;height:Hpx;…`, teks sebagai child node type 3), `fun mutation(diff: WireframeDiff, ts: Long): Map<String, Any?>?` (type 3, source 0; null bila kosong), `fun touch(kind: TouchKind, xDp: Int, yDp: Int, ts: Long): Map<String, Any?>` (`DOWN` → type 3 source 2 type 7; `UP` → source 2 type 9; `MOVE` → source 6 `{positions:[{x,y,id:4,timeOffset:0}]}`), dan `fun reset()`.
  - `internal enum class TouchKind { DOWN, MOVE, UP }`
  - Bentuk node serialized mengikuti tipe `serializedNodeWithId` rrweb 2.x (`type` 0 = Document, 2 = Element, 3 = Text; `childNodes`, `attributes`, `tagName`, `id`); offset full snapshot `{left:0, top:0}`. Href scrub via `UrlScrubber`.

- [ ] **Step 1: Test gagal.**
  - `diff_textChange`, `diff_addAndRemove`, `diff_boundsChangeIsUpdate`, `diff_identical_isEmpty`.
  - `fullSnapshot_structure`: root `type == 2`, `data.node.type == 0`, `html > body` ada, div pertama memiliki `style` berisi `"left:10px"` dan `"top:20px"`.
  - `fullSnapshot_maskedTextStaysMasked`.
  - `mutation_textsUseMappedIds`: id di `texts[].id` sama dengan id hasil full snapshot sebelumnya.
  - `touch_values`: DOWN `{type:3, data:{source:2, type:7, x, y}}`, UP type 9, MOVE source 6.
  - `golden`: snapshot JSON untuk layout tetap dibandingkan dengan `src/test/resources/rrweb/golden_full_snapshot.json` (dibuat saat test pertama hijau, lalu divalidasi manual di Task 25).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): encode wireframes as rrweb events`.

### Task 23: ReplayChunker, Sampling, TouchRecorder, ReplayController

**Files:**
- Create: `replay/ReplayChunker.kt`, `replay/Sampling.kt`, `replay/touch/TouchRecorder.kt`, `replay/ReplayController.kt`; modify `ARTrackerCore.kt`, `ARTracker.kt` (`startReplay`/`stopReplay`), `ActivityTracker` (WindowListener → controller)
- Test: `replay/ReplayChunkerTest.kt`, `replay/SamplingTest.kt`, `replay/touch/TouchRecorderTest.kt`, `replay/ReplayControllerTest.kt` (Robolectric)

**Interfaces:**
- Produces:
  - `internal class ReplayChunker(maxBytes: Int = 24 * 1024, flushAtBytes: Int = 20 * 1024)` dengan `fun add(event: Map<String, Any?>, isFull: Boolean): List<Map<String, Any?>>` (mengembalikan attributes chunk yang siap kirim) dan `fun drain(): List<Map<String, Any?>>`. Attributes chunk: `{seq, start, end, full_snapshot, platform:"android", events}`; `seq` naik per chunk. Event tunggal >24 KB → `childNodes` terdalam dipangkas hingga muat, ditandai `oversized: true`.
  - `internal object Sampling { fun isSampled(sessionId: String, rate: Double): Boolean }` (port persis `artracker-web-sdk/src/core/sampling.ts`: FNV-1a 32-bit atas `s[i].code` (UTF-16 code unit, sama dengan `charCodeAt`), offset `0x811c9dc5`, prime `0x01000193`, unsigned, dibagi `2^32`; `rate >= 1` → true, `rate <= 0` → false).
  - `internal class TouchRecorder(delegate: Window.Callback, onTouch: (TouchKind, Float, Float) -> Unit) : Window.Callback by delegate`. Hanya `dispatchTouchEvent` yang dioverride (catat lalu kembalikan hasil delegate). MOVE di-throttle 50 ms. `companion fun wrap(window: Window, …)` idempoten (tidak membungkus dua kali).
  - `internal class ReplayController(core: ARTrackerCore, walker: ViewWalker, encoder: RrwebEncoder, chunker: ReplayChunker, scheduler: FrameScheduler, mainHandler: Handler) : ActivityTracker.WindowListener` dengan `fun start(manual: Boolean)`, `fun stop()`, `fun onAppBackground()`, `fun onAppForeground()`. Capture (main thread): `RootViewLocator.roots` → `walker.walk`. Diff/encode/chunk di `Dispatchers.Default`. Chunk dikirim via `pipeline.enqueue(items.session("replay_chunk", attrs))`. Full snapshot dibuat saat start, saat layar berganti, saat rotasi/ukuran berubah, dan tiap 120 000 ms. Chunk di-flush tiap 5 000 ms. Aktif hanya bila `config.replay.enabled && Sampling.isSampled(sessionId, rate)` atau `manual`.

- [ ] **Step 1: Test gagal.**
  - `ReplayChunkerTest.chunksUnder24KB` (500 event acak; setiap `Json.encode(chunk).length <= 24*1024`), `seqIncrements`, `oversizedEvent_isTrimmedAndFlagged`.
  - `SamplingTest.deterministic`, `rateApprox` (10 000 UUIDv7, rate 0.1 → 0.08..0.12), `rate0_never_rate1_always`.
  - `SamplingTest.matchesWebSdk`: 3 vektor `(sessionId, expectedBucket)` yang dihitung dari implementasi web dan ditulis ke test.
  - `TouchRecorderTest.forwardsDelegateResult` (delegate mengembalikan false → recorder juga false), `recordsDownAndUp`, `moveThrottled`, `wrap_isIdempotent`.
  - `ReplayControllerTest.enabledAndSampled_emitsFullSnapshotChunk`: config replay enabled, `sampleRate = 1.0`, activity Robolectric dengan TextView → idle looper 1 s + 5 s → pipeline menerima `replay_chunk` dengan `full_snapshot == true`, `platform == "android"`.
  - `disabled_emitsNothing`, `background_stopsCapture`, `manualStart_ignoresSampling`.
  - `passwordNeverInChunk`: activity dengan EditText password `"hunter2"` → tidak ada chunk yang mengandung `"hunter2"`.
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(sdk): add session replay controller, chunking and touch capture`.

---

## Milestone G — Sample App, Pengerasan, Rilis

### Task 24: Sample app

**Files:**
- Modify: `app/build.gradle.kts` (`buildFeatures.buildConfig = true`; `buildConfigField` `ARTRACKER_ENDPOINT` & `ARTRACKER_KEY` dari `local.properties`, default `""`; `implementation` okhttp, fragment-ktx, navigation-compose, appcompat), `app/src/main/AndroidManifest.xml` (`android:name=".sample.SampleApplication"`, `networkSecurityConfig` hanya di debug lewat `src/debug/res/xml/network_security_config.xml` + manifest debug), `MainActivity.kt` (host NavHost).
- Create: `app/src/main/java/id/art/tracker/sample/SampleApplication.kt`, `sample/screens/{LoginScreen,PaymentScreen,FeedScreen,DiagnosticsScreen}.kt`, `sample/legacy/{LegacyActivity,LegacyFragment}.kt` + `res/layout/activity_legacy.xml` (berisi `android:tag="artracker-mask"`), `sample/SampleHttp.kt` (OkHttpClient + interceptor)
- Test: `app/src/test/java/id/art/tracker/sample/SampleApplicationTest.kt` (Robolectric: `ARTracker.isInitialized()` true bila key diisi, false + tidak crash bila key kosong)

**Interfaces:**
- Consumes: API publik SDK saja (tidak memakai `internal`).
- Isi layar: Login (email + password `PasswordVisualTransformation` + `Modifier.semantics { password() }`), Payment (nomor kartu/CVV `KeyboardType.Number`, nominal dibungkus `Modifier.artrackerMask()`), Feed (LazyColumn 500 item), Legacy XML Activity + Fragment, Diagnostics (tombol: `track`, `identify`, `log`, HTTP 200/404/timeout via `https://httpbin.org`, *Crash now* (`throw RuntimeException("Sample crash")`), *ANR 10 s* (`Thread.sleep` di main), *Flush*, *Start/Stop replay*, serta panel `ARTracker.diagnostics()` yang refresh tiap 1 s).

- [ ] **Step 1:** Tulis `SampleApplicationTest` (dua kasus di atas) → FAIL.
- [ ] **Step 2:** Implementasi app → `./gradlew :app:testDebugUnitTest :app:assembleDebug` PASS.
- [ ] **Step 3: E2E manual terhadap backend lokal** (catat hasil di deskripsi PR):
  1. `cd ../artracker && docker compose up -d`; login dashboard, buat project, dan buat key `art_live_…`.
  2. Isi `local.properties`: `artracker.endpoint=http://10.0.2.2:<port>` dan `artracker.key=…`; install ke emulator.
  3. Tekan tombol track/http/log → `GET /api/v1/projects/:id/events?kind=all&limit=50` menampilkan `session_start`, `checkout_clicked`, `http_request` (status 404 = `warn`), dan `screen_view`.
  4. Aktifkan mode pesawat → *Crash now* → buka app → matikan mode pesawat → dalam ≤60 s muncul item `log/fatal` `crash` (**acceptance criteria #2**).
  5. *ANR 10 s* → force close dari dialog → relaunch (API 30+) → muncul item `anr`.
  6. Replay aktif → buka Login, ketik password → chunk `replay_chunk` ada dan tidak memuat teks password (`grep` pada attributes hasil API).
- [ ] **Step 4: Commit** `feat(sample): add demo screens wired to artracker sdk`.

### Task 25: Performa, validasi rrweb, fuzz API

**Files:**
- Create: `tools/replay-viewer/index.html` (memuat `rrweb-player@2.0.0-alpha.18` dari jsDelivr; input file JSON berisi array attributes `replay_chunk`; menggabungkan `events` urut `seq`), `artracker-sdk/src/test/java/id/art/tracker/PublicApiFuzzTest.kt`; modify `ViewWalker`/`FrameScheduler` (log durasi capture di `SdkLog.d` saat `debug`).

- [ ] **Step 1: Fuzz test gagal → lulus.** `PublicApiFuzzTest`: untuk 1000 iterasi seed tetap, panggil API publik acak dengan nama kosong/`"\u0000"`/string 1 MB, props berisi `NaN`, map siklik, `ByteArray`, objek kustom, dan `null`. Expected: tidak ada exception dan setiap payload tersimpan bisa di-parse `JSONObject`.
- [ ] **Step 2: Validasi rrweb.** Ekspor chunk dari E2E Task 24 → buka `tools/replay-viewer/index.html` → layar Login/Feed terputar, kotak password berisi `*`, titik sentuh terlihat. Bila gagal, perbaiki `RrwebEncoder` dan perbarui golden file Task 22.
- [ ] **Step 3: Ukur (emulator API 34 + satu perangkat fisik kelas menengah bila ada).**
  - `init` di main thread: `adb logcat -s ARTracker` (log durasi) → target ≤2 ms (median 5 cold start).
  - Walk replay pada Feed: p95 ≤8 ms.
  - Background 10 menit: `adb shell dumpsys cpuinfo | grep id.art.tracker` → <1% (**acceptance criteria #1**); Perfetto trace tidak menunjukkan thread `ARTracker` aktif selain satu run WorkManager.
  - Ukuran AAR rilis (`ls -l artracker-sdk/build/outputs/aar/`) dicatat di CHANGELOG.
- [ ] **Step 4:** Bila target terlewati, catat angka & penyebab sebagai issue, jangan menurunkan target diam-diam.
- [ ] **Step 5: Commit** `test(sdk): add api fuzzing, replay viewer and perf notes`.

### Task 26: Publikasi & dokumentasi

**Files:**
- Modify: `artracker-sdk/build.gradle.kts` (`maven-publish`; `android { publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } } }`; publication `groupId = "id.art.tracker"`, `artifactId = "artracker-android"`, `version = "0.1.0"`), `artracker-sdk/consumer-rules.pro` (keep `id.art.tracker.ARTracker`, `ARTrackerConfig*`, `ARTrackerOkHttpInterceptor`, anotasi `ARTrackerScreenName`, field reflection `WindowManagerGlobal`; `-dontwarn okhttp3.**`, `androidx.compose.**`, `androidx.fragment.**`, `androidx.navigation.**`), root `README.md`
- Create: `artracker-sdk/CHANGELOG.md`

- [ ] **Step 1:** `./gradlew :artracker-sdk:publishToMavenLocal` → file ada di `~/.m2/repository/id/art/tracker/artracker-android/0.1.0/` (`.aar`, `.pom`, `-sources.jar`). POM **tidak** mencantumkan okhttp/compose/fragment/navigation sebagai dependency.
- [ ] **Step 2:** `./gradlew :app:assembleRelease` dengan `isMinifyEnabled = true` sementara → sukses, dan app rilis tetap mengirim event (smoke test manual sekali).
- [ ] **Step 3:** README: instalasi, `ARTracker.init`, OkHttp, masking View/XML/Compose, replay opt-in, ProGuard, dan tabel taksonomi event (salin §11 spec). CHANGELOG `0.1.0`.
- [ ] **Step 4:** Perbarui §14 `ARCHITECTURE.md` (centang) dan tambahkan bagian "Deviasi dari dokumen ini" bila ada.
- [ ] **Step 5: Commit** `chore(sdk): publishing setup and docs for 0.1.0`.

---

## Pemetaan Spec → Task

| Spec | Task |
| --- | --- |
| §2 kontrak & reaksi status | 9, 10, 11 |
| §4 core, lifecycle, session, threading | 6, 7, 12, 13, 14 |
| §5 public API | 3, 4, 13, 14, 15, 23 |
| §6 replay | 19–23, 25 |
| §7.1 masking UI | 18, 19, 20 |
| §7.2 redaksi atribut | 5, 12 |
| §8 crash, ANR, network, breadcrumbs | 15, 16, 17 |
| §9 transport & offline | 8–12 |
| §10 integrasi app | 1, 24 |
| §12 strategi pengujian | seluruh task + 25 |
| Acceptance #1 (CPU background) | 14 (stop saat background), 21, 25 |
| Acceptance #2 (crash offline) | 15 (`offlineCrash_sentOnNextInit`), 24 (E2E) |
