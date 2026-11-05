# Technical Architecture Plan — `artracker-sdk` (Android)

> Status: **Disetujui** (Q1–Q5, lihat §13) — 2026-11-05. Belum ada kode yang ditulis.
> Implementation plan: `docs/superpowers/plans/2026-11-05-artracker-android-sdk.md`.
> Sumber requirement: issue [`tiofani03/artracker#12`](https://github.com/tiofani03/artracker/issues/12) (bagian Android).
> Kontrak backend ditelusuri langsung dari `artracker/backend/internal/{handler,ingest,auth}` dan diselaraskan
> dengan `artracker-web-sdk/CONTRACT.md` agar kedua SDK menghasilkan data yang sama bentuknya.

---

## 1. Tujuan, Batasan & Acceptance Criteria

| Acceptance criteria (issue #12) | Cara pemenuhan | Cara verifikasi |
| --- | --- | --- |
| Overhead CPU/baterai minimal (<1% CPU di background) | Saat app di background: perekam replay, timer flush, dan listener sentuh **berhenti total**. Yang tersisa hanya satu `OneTimeWorkRequest` WorkManager dengan constraint jaringan | Perfetto/`dumpsys cpuinfo` di sample app selama 10 menit di background; Macrobenchmark untuk foreground |
| Crash fatal terekam & terkirim pada peluncuran berikutnya bila offline | Crash ditulis **sinkron ke file** di `UncaughtExceptionHandler` (tanpa jaringan, tanpa SQLite), lalu dipindah ke antrean SQLite dan dikirim saat `init()` berikutnya / saat jaringan kembali | Tes instrumentasi: mode pesawat → crash → relaunch online → item `log/fatal` tiba di `GET /projects/:id/events` |

Subtask issue yang dicakup: `init(applicationContext, config)`, crash collector, OkHttp interceptor,
Compose/View hierarchy + touch gesture, WorkManager + offline SQLite. Publikasi Gradle (repo
`tiofani03/artracker-android`) juga dicakup di Fase 7.

### Prinsip

1. **SDK tidak boleh membuat app pelanggan crash atau lambat.** Setiap entry point publik dibungkus `runCatching`; kegagalan internal hanya dicatat di debug log.
2. **Main thread hanya melakukan yang wajib di main thread**: membaca properti View/semantics (snapshot ringan). Diff, serialisasi, redaksi, gzip, I/O, dan jaringan berjalan di worker thread.
3. **Dependensi minimal.** Tanpa Room, tanpa kotlinx.serialization, tanpa OkHttp sebagai dependensi wajib (lihat §9).
4. **Privasi secara default.** Masking terjadi saat capture, sebelum data meninggalkan model snapshot, sehingga teks sensitif tidak pernah masuk memori antrean.

---

## 2. Kontrak Backend (ringkasan yang relevan untuk Android)

| Aspek | Nilai | Sumber |
| --- | --- | --- |
| Endpoint | `POST {endpoint}/api/v1/ingest` → `202 {"status":"accepted","queued":n}` | `handler/routes.go`, `handler/ingest.go` |
| Auth | Header `X-Artracker-Key: art_live_…` (fallback `?api_key=` tidak diperlukan di Android). SDK mobile **hanya** memakai key `art_live_*` (`public_client`). Validasi awal opsional: `GET /api/v1/sdk/validate` | `auth/apikey.go`, `handler/apikey.go` |
| Kompresi | `Content-Encoding: gzip` (magic byte `1f 8b`), opsional | `ingest/decompress.go` |
| Batas keras | 512 KB compressed, 2 MB uncompressed, 500 item/batch, `attributes` 32 KB (lebih dari itu **diganti diam-diam** dengan `{"_truncated":true}`), `name` 255, `message` 4096, `session_id`/`user_id` 128 | `ingest/model.go` |
| `type` valid | `event`, `log`, `session`; selain itu **di-drop diam-diam** | `ingest/sanitize.go` |
| `level` valid (`log`) | `debug, info, warn, error, fatal` | `ingest/sanitize.go` |
| `context` yang disimpan | Hanya `environment` dan `app_version` (alias `release_version`). Field lain **hilang**, jadi metadata perangkat disalin ke `attributes` item `session_start` | `ingest/sanitize.go` |
| Timestamp | Kirim `sent_at` (envelope) **dan** `timestamp` (item) agar backend mengoreksi jam perangkat yang meleset (drift 0–7 hari) | `ingest/sanitize.go#normalizeTimestamp` |
| Rate limit | 50 req/s dan 1000 event/s **per project** (dibagi semua device). Habis → `429` + `Retry-After` | `ingest/ratelimit.go` |
| Dedup | **Tidak ada** di server. SDK membuat UUIDv7 sekali per item dan memakainya ulang saat retry | `CONTRACT.md §12` |
| Replay | Belum ada endpoint `/replay/...` di backend walau issue #8 sudah ditutup. Chunk replay dikirim sebagai `type:"session"`, `name:"replay_chunk"`, sama seperti web SDK. Player di dashboard memakai **rrweb** | `grep replay backend` kosong; `frontend/src/pages/DashboardPage.tsx` |

**Aturan SDK (sama dengan web):** maksimum **50 item / 128 KB uncompressed per request**, soft cap **24 KB per `attributes`**.

> Catatan: di Android tidak ada CORS, jadi header `Retry-After` **bisa dibaca**. Ini kelebihan dibanding web SDK.

### Reaksi terhadap kode respons

| Status | Reaksi |
| --- | --- |
| `202` | Hapus batch dari SQLite, reset backoff |
| `400` | Drop permanen (bug SDK), catat di debug log |
| `401` | **Kill-switch**: hentikan pengiriman dan perekaman; persist flag `disabledUntil = now + 1 jam` agar tidak memicu badai request saat relaunch |
| `413` | Belah batch jadi dua; satu item yang tetap terlalu besar di-drop |
| `429` / `503` | Hormati `Retry-After`, jeda global, batch tetap pending |
| IOException / offline | Batch tetap di SQLite; backoff eksponensial + jitter (2s → 5 menit); jadwalkan WorkManager dengan `NetworkType.CONNECTED` |

---

## 3. Peta Modul

```
artracker-sdk/src/main/java/id/art/tracker/
├─ ARTracker.kt                 Facade publik (object, @JvmStatic)
├─ ARTrackerConfig.kt           Config + builder (Kotlin DSL & Java builder), ReplayConfig, PrivacyConfig
├─ core/
│   ├─ ARTrackerCore.kt         Graph internal; dibuat sekali oleh init()
│   ├─ SessionManager.kt        session_id (UUIDv7), idle timeout, persist di SharedPreferences
│   ├─ IdentityStore.kt         user_id, traits, anonymous_id
│   ├─ DeviceContext.kt         model, OS, app version, screen, locale, network type
│   ├─ ItemFactory.kt           Builder RawItem (id UUIDv7, timestamp, session/user)
│   ├─ SdkDispatchers.kt        Executor & CoroutineScope internal
│   └─ KillSwitch.kt            disabledUntil (401), opt-out
├─ lifecycle/
│   ├─ AppLifecycleObserver.kt  ProcessLifecycleOwner → foreground/background
│   ├─ ActivityTracker.kt       ActivityLifecycleCallbacks → screen_view, window attach
│   ├─ FragmentTracker.kt       FragmentLifecycleCallbacks (opsional, compileOnly)
│   └─ ComposeNavTracker.kt     helper NavController → screen_view (opsional)
├─ replay/
│   ├─ ReplayController.kt      Start/stop, sampling, mengatur jadwal capture
│   ├─ capture/
│   │   ├─ RootViewLocator.kt   Semua root window (Activity, Dialog, PopupWindow)
│   │   ├─ ViewWalker.kt        View hierarchy → Wireframe (main thread, read-only)
│   │   ├─ ComposeWalker.kt     SemanticsOwner → Wireframe
│   │   └─ FrameScheduler.kt    Dirty flag + throttle + budget watchdog
│   ├─ model/Wireframe.kt       Node immutable: id, bounds, type, style, text(masked)
│   ├─ diff/WireframeDiffer.kt  Diff frame N-1 vs N → mutations
│   ├─ rrweb/RrwebEncoder.kt    Wireframe/diff → event rrweb (Meta, FullSnapshot, Incremental)
│   ├─ touch/TouchRecorder.kt   Window.Callback wrapper → MouseInteraction/TouchMove
│   └─ ReplayChunker.kt         Pecah event menjadi chunk ≤24 KB
├─ privacy/
│   ├─ MaskingPolicy.kt         Keputusan mask/unmask per node
│   ├─ ViewMaskRules.kt         EditText inputType, autofill hints, tag, class
│   ├─ ComposeMaskRules.kt      SemanticsProperties.Password/EditableText, key kustom
│   ├─ ARTrackerMask.kt         Modifier.artrackerMask(), View.artrackerMask()
│   └─ AttributeRedactor.kt     Redaksi PII pada props track()/URL (port dari web)
├─ telemetry/
│   ├─ CrashHandler.kt          Thread.UncaughtExceptionHandler (chain ke handler lama)
│   ├─ CrashStore.kt            Tulis/baca file crash secara atomik
│   ├─ ExitInfoCollector.kt     ApplicationExitInfo (API 30+): ANR & native crash sesi lalu
│   ├─ Breadcrumbs.kt           Ring buffer (screen, tap, http, log)
│   └─ okhttp/ARTrackerOkHttpInterceptor.kt
└─ transport/
    ├─ EventPipeline.kt         Channel masuk → redaksi → persist → trigger flush
    ├─ EventStore.kt            SQLiteOpenHelper (write-ahead queue)
    ├─ Batcher.kt               Ambil ≤50 item / ≤128 KB berdasarkan prioritas
    ├─ PayloadEncoder.kt        Encoder JSON Kotlin murni (core/Json.kt) + GZIPOutputStream
    ├─ IngestClient.kt          HttpURLConnection (sengaja tidak memakai OkHttp)
    ├─ Backoff.kt               Eksponensial + jitter, Retry-After
    └─ FlushWorker.kt           CoroutineWorker (WorkManager, constraint CONNECTED menggantikan NetworkMonitor)
```

### Aliran data

```
 Main thread (murah, read-only)              SDK worker (single-thread)                 I/O
 ─────────────────────────────              ─────────────────────────                  ───
 track()/identify()/log() ──┐
 Lifecycle callbacks ───────┼─► ItemFactory ─► Channel ─► AttributeRedactor ─► EventStore (SQLite)
 OkHttp interceptor (bg) ───┤                                                           │
 Crash (sync → file) ───────┘ (dibaca ulang saat init berikutnya)                        ▼
 FrameScheduler tick ─► ViewWalker/ComposeWalker ─► Wireframe ─► Differ ─► RrwebEncoder ─► Chunker ─┘
                        (masking diterapkan di sini)                                    │
                                                         Batcher ◄──────────────────────┘
                                                            │  gzip
                                                            ▼
                                            IngestClient ─► POST /api/v1/ingest
                                            (foreground: timer; background/offline: WorkManager)
```

---

## 4. Core Manager & Lifecycle Tracker

### 4.1 Inisialisasi

- `ARTracker.init()` dipanggil dari `Application.onCreate()`. Bagian sinkron di main thread **≤2 ms**: validasi config, membuat `ARTrackerCore`, memasang `CrashHandler` (harus sedini mungkin) dan `ActivityLifecycleCallbacks`.
- Bagian berat di worker: membuka SQLite, membaca crash file dari sesi lalu, `ExitInfoCollector`, flush awal.
- Idempoten: pemanggilan kedua diabaikan dengan warning.
- Tidak memakai `ContentProvider`/App Startup untuk auto-init, karena API key harus diberikan secara eksplisit.

### 4.2 Lifecycle

| Sumber | Mekanisme | Output |
| --- | --- | --- |
| Proses foreground/background | `ProcessLifecycleOwner` (`lifecycle-process`) | `session`/`app_foreground`, `app_background`; memicu flush + jadwal WorkManager saat background; jeda replay |
| Activity | `Application.ActivityLifecycleCallbacks` | `event`/`screen_view` `{screen, previous, type:"activity"}`; memberi tahu `ReplayController` bahwa root window berganti (→ full snapshot) |
| Fragment | `FragmentManager.FragmentLifecycleCallbacks` (recursive), didaftarkan di `onActivityCreated` bila activity adalah `FragmentActivity`. Dependensi `compileOnly` + cek kelas saat runtime | `screen_view` `{type:"fragment"}` |
| Compose | Compose tidak punya lifecycle per layar. Disediakan `ARTracker.trackNavController(navController)` (listener `OnDestinationChangedListener`) **dan** `ARTracker.screen("Checkout")` manual | `screen_view` `{type:"compose"}` |

Nama layar default: `activity.javaClass.simpleName`, bisa ditimpa lewat `@ARTrackerScreenName("…")` atau `config.screenNameResolver`.

### 4.3 Session Manager

| Aspek | Keputusan |
| --- | --- |
| ID | UUIDv7 string (≤128), persist di `SharedPreferences` (`artracker_session`, dikecualikan dari backup) |
| Sesi baru | Saat init pertama, atau saat kembali ke foreground setelah **>30 menit** di background (configurable), atau `ARTracker.reset()` |
| Batas keras | 24 jam per sesi |
| `session_start` | `type:"session"`, attributes `{platform:"android", os_version, api_level, manufacturer, model, app_version, app_build, screen{w,h,density}, locale, tz, network, is_emulator, sdk_version}`. Ini wajib karena backend membuang field `context` selain environment/app_version |
| `session_end` | Dikirim saat background >timeout terdeteksi atau sesi dirotasi, dengan `duration_ms`, `screen_count`, ringkasan breadcrumbs |

### 4.4 Threading

- Satu `CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + CoroutineExceptionHandler)`: semua mutasi antrean berjalan berurutan tanpa lock.
- `track()` dari thread mana pun hanya membuat item (timestamp diambil saat itu) lalu `trySend` ke `Channel(capacity = 1000, onBufferOverflow = DROP_OLDEST)`. Pemanggil tidak pernah terblokir.
- Capture replay di main thread; hasilnya (`Wireframe` immutable) diserahkan ke `Dispatchers.Default` untuk diff/encode.

---

## 5. Public API

```kotlin
// Application.onCreate()
ARTracker.init(this, ARTrackerConfig(
    endpoint = "https://art.exlm.my.id",
    apiKey = "art_live_…",
    environment = "production",           // → kolom environment
    appVersion = BuildConfig.VERSION_NAME, // default: dari PackageManager
    debug = BuildConfig.DEBUG,
    replay = ReplayConfig(enabled = true, sampleRate = 0.1, framesPerSecond = 1),
    privacy = PrivacyConfig(maskAllInputs = true, maskAllText = false),
    flushIntervalMs = 10_000,
))

ARTracker.identify("user-123", mapOf("plan" to "pro"))   // → event "$identify"
ARTracker.track("checkout_clicked", mapOf("total" to 120_000))
ARTracker.screen("Checkout")                              // screen_view manual
ARTracker.log(LogLevel.WARN, "Promo API slow", mapOf("ms" to 2300))
ARTracker.captureException(throwable, handled = true)
ARTracker.flush()
ARTracker.reset()                                          // logout: user_id hilang, sesi baru
ARTracker.optOut(true) / ARTracker.startReplay() / ARTracker.stopReplay()

// OkHttp
OkHttpClient.Builder().addInterceptor(ARTrackerOkHttpInterceptor()).build()

// Privasi
view.artrackerMask() / view.artrackerUnmask()
Modifier.artrackerMask() / Modifier.artrackerUnmask()
```

- Facade `object ARTracker` dengan `@JvmStatic` dan builder Java (`ARTrackerConfig.Builder`). Nama `ARTracker` mengikuti issue #12 dan web SDK.
- Semua fungsi sebelum `init()` menjadi no-op dengan warning, bukan exception.
- `props`: `Map<String, Any?>` dengan nilai primitif, `List`, `Map`, atau `Date`. Nilai lain di-`toString()`. Kedalaman maks 6, maks 1000 node, hasil ≤24 KB.
- `beforeSend: ((Item) -> Item?)?` disediakan sebagai hook terakhir untuk redaksi kustom atau drop.

---

## 6. Visual/Layout Recording & Snapshot Strategy

### 6.1 Keputusan format: **wireframe → rrweb**, bukan screenshot

| Opsi | Kelebihan | Kekurangan |
| --- | --- | --- |
| **A. Wireframe → event rrweb** *(direkomendasikan)* | Player rrweb di dashboard bisa langsung memutarnya; ukuran kecil (target <45 KB/menit, sesuai klaim landing page); masking struktural (teks diganti sebelum keluar); sesuai batas 24 KB/item | Visual tidak *pixel-perfect* (gambar = placeholder, custom drawing tidak terekam) |
| B. Screenshot (PixelCopy) | Pixel-perfect | 30–100 KB/frame WebP sehingga melanggar soft cap 24 KB per item dan kuota event; masking butuh menggambar kotak di bitmap (rawan bocor bila koordinat meleset); biaya CPU/baterai tinggi |

Mode screenshot dicatat sebagai pekerjaan masa depan (§13), bukan v1.

### 6.2 Capture

- **Root windows**: `RootViewLocator` memakai `WindowManagerGlobal.mViews` (reflection, dibungkus `runCatching`) agar dialog, bottom sheet, dan popup ikut terekam. Fallback: `decorView` activity aktif.
- **ViewWalker** (main thread, read-only): per `View` yang `VISIBLE` dan berukuran >0 diambil `id` stabil (`System.identityHashCode`), bounds di layar (`getLocationOnScreen`, dalam dp), tipe (`text`, `input`, `image`, `button`, `container`, `web`, `surface`), warna background (`ColorDrawable`), teks (`TextView`, setelah masking), warna/ukuran font, alpha, dan clip.
- **ComposeWalker**: untuk `AndroidComposeView`, baca `(view as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode` lalu ambil `boundsInWindow`, `Text`, `EditableText`, `Role`, `Password`, dan key mask kustom. Pembacaan dilakukan lewat reflection-safe wrapper. Bila API berubah, node Compose dirender sebagai satu blok placeholder dan app tidak crash.
- Batas: maksimum **2000 node** per frame dan kedalaman 50. Bila terlampaui, subtree dirangkum sebagai blok.

### 6.3 Framing rate control (`FrameScheduler`)

1. `ViewTreeObserver.OnDrawListener` di setiap root hanya mengeset `dirty = true` (tanpa alokasi).
2. Handler main thread melakukan tick tiap `1000 / framesPerSecond` ms (default **1 fps**, rentang 0,5–4). Capture **hanya bila dirty**.
3. Capture tambahan (debounce 100 ms) setelah `ACTION_UP` dan pergantian layar, agar efek tap terlihat.
4. **Budget watchdog**: bila waktu walk >8 ms pada 3 frame berturut-turut, fps diturunkan setengah. Kembali naik setelah 30 detik stabil.
5. Berhenti total saat `app_background`, `stopReplay()`, kill-switch, atau sesi tidak lolos sampling.

### 6.4 Encoding ke rrweb

| Kejadian | Event rrweb |
| --- | --- |
| Mulai/ganti layar/rotasi, dan checkout tiap 2 menit | `Meta (4)` `{href:"android://<screen>", width, height}` + `FullSnapshot (2)`: dokumen sintetis `html>body>div` dengan `div` absolute per wireframe (`style="left:..;top:..;width:..;height:..;background:..;color:..;font-size:.."`) |
| Perubahan antar frame | `IncrementalSnapshot (3)` source `Mutation (0)`: `adds/removes/attributes/texts` berdasarkan diff `id` stabil |
| Scroll | Ditangkap sebagai perubahan posisi pada diff (tanpa source `Scroll` terpisah di v1) |
| Sentuhan | Source `MouseInteraction (2)` dengan `TouchStart (7)`/`TouchEnd (9)`, dan source `TouchMove (6)` yang di-throttle 50 ms |
| Input | Tidak merekam ketukan keyboard. Perubahan teks input muncul sebagai mutasi teks yang **sudah di-mask** |

`TouchRecorder` membungkus `Window.Callback` setiap activity (meneruskan semua panggilan ke callback asli) dan hanya membaca koordinat. Tidak pernah ada `consume`.

### 6.5 Chunking & sampling

- Chunk = `type:"session"`, `name:"replay_chunk"`, attributes `{seq, start, end, full_snapshot, platform:"android", events:[…]}`, ≤24 KB. Dipecah per event; event tunggal >24 KB (full snapshot raksasa) subtree-nya dirangkum lalu ditandai `oversized:true`.
- Flush chunk tiap 5 detik atau saat ≥20 KB.
- **Default `replay.enabled = false`** (opt-in, setara keputusan D2 web) dan `sampleRate = 0.1`. Sampling deterministik: `hash(session_id) < sampleRate`.

---

## 7. Privacy Masking Engine

Masking diterapkan **di walker** (lapis 1), lalu redaksi atribut berjalan di pipeline (lapis 2).

### 7.1 Lapis 1 — UI

| Aturan | Default | Detail |
| --- | --- | --- |
| Password | **Selalu mask, tidak bisa di-unmask** | `EditText`/`TextView` dengan `inputType` variation `TEXT_PASSWORD`, `TEXT_VISIBLE_PASSWORD`, `TEXT_WEB_PASSWORD`, `NUMBER_PASSWORD`; `TransformationMethod` adalah `PasswordTransformationMethod`; Compose `SemanticsProperties.Password` |
| Numerik / telepon | Mask | `TYPE_CLASS_NUMBER`, `TYPE_CLASS_PHONE` (PIN, OTP, kartu, nominal) |
| Autofill hints sensitif | Mask | `password`, `creditCardNumber`, `creditCardSecurityCode`, `creditCardExpiration*`, `phone`, `emailAddress`, `postalAddress`, `smsOTPCode` |
| Semua input | Mask (`maskAllInputs = true`) | Semua `EditText` dan node Compose `EditableText`. Pengguna bisa melonggarkan per view dengan `artrackerUnmask()` (kecuali password) |
| Semua teks | Off (`maskAllText = false`) | Opsi untuk app finansial/kesehatan |
| Tag view | — | Padanan Android dari `data-artracker-mask`: `android:tag="artracker-mask"` di XML, `view.setTag(R.id.artracker_mask, true)`, atau `view.artrackerMask()`. Berlaku untuk **seluruh subtree**. `artracker-unmask` sebagai kebalikannya |
| Compose | — | `Modifier.artrackerMask()` = `semantics { this[ARTrackerMaskKey] = true }` |
| Kelas kustom | — | `PrivacyConfig.sensitiveViewClasses = setOf(CardInputView::class.java)` (cocok via `isInstance`) |
| Selalu diblok | — | `WebView` (isi DOM tidak terlihat dari hierarki View), `SurfaceView`, `TextureView`, `VideoView`, MapView: dirender sebagai blok berlabel. `ImageView`: placeholder warna netral; bitmap tidak pernah direkam |

**Bentuk masking:** teks diganti `*` dengan panjang sama (maks 32 karakter) **di dalam walker**, jadi `String` asli tidak pernah masuk `Wireframe`. Prioritas keputusan: `password` > `unmask` eksplisit > `mask` eksplisit/kelas > aturan default.

### 7.2 Lapis 2 — Atribut (`AttributeRedactor`)

Port aturan web SDK agar konsisten:
- Key sensitif (`password|passwd|secret|token|authorization|cookie|api_key|card|cvv|pin|otp|ssn`) → `"[redacted]"` (sama persis dengan web SDK).
- Nilai: email, nomor kartu (lolos Luhn), JWT, dan `Bearer …` → di-redaksi.
- URL (http_request, href replay): query param sensitif → `[redacted]`, fragment dibuang.
- Berlaku untuk `track/identify/log/captureException`, breadcrumbs, dan atribut http. Chunk replay **tidak** melewati walker ini (sudah di-mask di lapis 1), hanya redaksi URL `href`.

---

## 8. Telemetry Listeners

### 8.1 Crash (`CrashHandler`)

1. `Thread.setDefaultUncaughtExceptionHandler` dipasang di `init()` dengan menyimpan handler sebelumnya (Firebase Crashlytics dsb.) dan **selalu memanggilnya** setelah selesai.
2. Di handler: bangun JSON `{id(UUIDv7), timestamp, session_id, user_id, thread, exception_class, message, stacktrace (cause chain, maks 16 KB), breadcrumbs (30 terakhir), screen, memory, app_version}` lalu tulis **sinkron** ke `filesDir/artracker/crash/<id>.json` lewat file temp + `rename` (atomik). Tidak menyentuh SQLite atau jaringan; batas waktu <50 ms.
3. Guard re-entrancy: bila handler sendiri melempar, langsung delegasikan ke handler lama.
4. Pada `init()` berikutnya: file crash diubah menjadi item `type:"log"`, `level:"fatal"`, `name:"crash"`, `message:"<Class>: <msg>"`, dimasukkan ke SQLite, lalu file dihapus. Item crash diberi **prioritas tertinggi** di batcher dan tidak pernah di-evict.
5. `ExitInfoCollector` (API 30+): `ActivityManager.getHistoricalProcessExitReasons` untuk `REASON_ANR`, `REASON_CRASH_NATIVE`, dan `REASON_LOW_MEMORY` sejak timestamp terakhir yang diproses → `log/fatal` `name:"anr"` / `"native_crash"` (trace ANR dipotong 16 KB).
6. `fingerprint = sha1(exception_class + 5 frame teratas)` untuk dedup di sisi SDK (maks 10 error identik per sesi).
7. Stack yang sudah di-R8 dikirim apa adanya + `attributes.build_id` (diinjeksi via plugin di masa depan). Symbolication di luar scope.

### 8.2 Network (`ARTrackerOkHttpInterceptor`)

- `okhttp3` adalah **`compileOnly`**: SDK tidak memaksa versi OkHttp ke app pelanggan. Interceptor hanya bisa dipakai bila app memang memakai OkHttp/Retrofit.
- Dipasang sebagai application interceptor oleh pengguna. Mencatat `log`, `name:"http_request"`, level dari status (`<400 info`, `4xx warn`, `5xx`/exception `error`), attributes `{method, url (redacted), host, status, duration_ms, req_bytes, res_bytes (dari Content-Length, body tidak dibaca), protocol, error?}`.
- Exception dilempar ulang tanpa perubahan. Response dikembalikan utuh.
- Opsi: `captureHeaders = false` (default), `ignoreHosts`, `sampleRate`, dan `captureOnlyErrors`.
- Request SDK sendiri tidak pernah terekam karena `IngestClient` memakai `HttpURLConnection`, bukan OkHttp milik app. Interceptor juga melewati host endpoint ARTracker sebagai pengaman tambahan.
- Setiap request juga masuk ke breadcrumbs.

### 8.3 Breadcrumbs

Ring buffer 30 entri (screen_view, tap `{target class/id/text masked}`, http, log). Breadcrumbs tidak dikirim sebagai item sendiri (hemat kuota 1000 event/s) dan hanya dilampirkan ke crash, error, dan `session_end`.

---

## 9. Off-Main-Thread Processing & Transport

### 9.1 Dependensi

| Lib | Scope | Alasan |
| --- | --- | --- |
| `kotlinx-coroutines-android` | api/impl | Pipeline |
| `androidx.work:work-runtime-ktx` | impl | Diminta issue; flush background/offline |
| `androidx.lifecycle:lifecycle-process` | impl | Foreground/background |
| `androidx.core` | impl | Utilitas |
| `com.squareup.okhttp3:okhttp` | **compileOnly** | Interceptor opsional |
| `androidx.fragment`, `androidx.compose.ui`, `androidx.navigation` | **compileOnly** | Integrasi opsional, dicek saat runtime |

Tidak memakai Room (KSP/kapt, ukuran, konflik versi) dan kotlinx.serialization. JSON memakai encoder Kotlin murni internal (`core/Json.kt`), bukan `android.util.JsonWriter`, agar bisa diuji di JVM tanpa Robolectric.

### 9.2 Local cache — `EventStore` (SQLiteOpenHelper, `artracker.db`)

```sql
CREATE TABLE items (
  id TEXT PRIMARY KEY,           -- UUIDv7 item (stabil saat retry)
  priority INTEGER NOT NULL,     -- 0 crash/fatal, 1 log error, 2 event/session, 3 replay_chunk
  created_at INTEGER NOT NULL,
  size_bytes INTEGER NOT NULL,
  payload TEXT NOT NULL,         -- JSON RawItem yang sudah final (ter-redaksi)
  batch_id TEXT,                 -- NULL = pending; terisi = sedang dikirim
  attempts INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_items_pending ON items(batch_id, priority, created_at);
```

- **Write-ahead**: semua item dipersist sebelum dikirim, dengan transaksi berkelompok (tiap 1 detik atau 20 item) untuk menekan I/O.
- Batas: **5 MB / 5000 baris**. Eviction mulai dari `replay_chunk` tertua, lalu `event`. Crash/fatal tidak pernah di-evict.
- Item lebih tua dari 7 hari dibuang (backend menolak drift >7 hari untuk koreksi skew).
- Saat start, baris dengan `batch_id` yang tertinggal (proses mati di tengah kirim) di-reset ke pending.

### 9.3 Batching & gzip

- `Batcher` mengambil ≤50 item / ≤128 KB uncompressed, diurutkan berdasarkan `priority, created_at`.
- Envelope: `{sent_at, sdk:{name:"artracker-android", version}, context:{environment, app_version, sdk_platform:"android"}, items}`.
- `PayloadEncoder` men-stream JSON langsung ke `GZIPOutputStream` (tanpa String perantara) di `Dispatchers.IO`. Bila hasil >450 KB, batch dibelah.

### 9.4 Pemicu flush

| Pemicu | Jalur |
| --- | --- |
| Timer foreground (default 10 detik), hanya bila ada pending | Coroutine in-process |
| ≥50 item pending atau ada crash/fatal baru | Coroutine, segera |
| `app_background` | Coroutine (best-effort) **+** enqueue `FlushWorker` |
| Gagal jaringan / offline | `FlushWorker` unik (`ExistingWorkPolicy.KEEP`), constraint `NetworkType.CONNECTED`, backoff eksponensial |
| Init dengan item pending (termasuk crash sesi lalu) | Coroutine, throttle maks 5 batch/detik |
| `ARTracker.flush()` | Coroutine |

`FlushWorker` bisa berjalan di proses yang baru dibangkitkan WorkManager. Karena itu endpoint dan key (key publik `art_live_*`, sudah ada di APK) disimpan di `SharedPreferences` agar worker bisa mengirim tanpa `init()`. WorkManager **tidak** dipakai untuk periodic work (minimum 15 menit, boros).

### 9.5 IngestClient

`HttpURLConnection`, connect timeout 10 s, read timeout 15 s, header `Content-Type: application/json`, `Content-Encoding: gzip`, `X-Artracker-Key`, `User-Agent: artracker-android/<ver>`. Hanya satu request in-flight sekaligus.

---

## 10. Integrasi dengan modul `app/`

1. `settings.gradle.kts`: `include(":artracker-sdk")`.
2. `gradle/libs.versions.toml`: tambah plugin `android-library`, `coroutines`, `work-runtime-ktx`, `lifecycle-process`, `okhttp`, `mockwebserver`, `robolectric`, `navigation-compose`.
3. `artracker-sdk/build.gradle.kts`: `com.android.library`, Kotlin package `id.art.tracker` (Android `namespace = "id.art.tracker.sdk"`, lihat Q2), `minSdk 24`, `consumer-rules.pro` (keep facade publik dan rule reflection Compose/WindowManagerGlobal), `explicitApi()`, `buildConfigField SDK_VERSION`.
4. `app/build.gradle.kts`: `implementation(project(":artracker-sdk"))`. `ARTRACKER_ENDPOINT` dan `ARTRACKER_KEY` dibaca dari `local.properties` ke `BuildConfig` (key tidak di-commit). Untuk emulator: `http://10.0.2.2:<port>` dengan `network_security_config` cleartext **hanya di debug**.
5. Sample app (Compose):
   - `SampleApplication` → `ARTracker.init(...)` dengan `debug = true`, replay `sampleRate = 1.0`.
   - Layar **Login** (password + email, untuk uji masking), **Payment** (kartu/CVV numerik, `Modifier.artrackerMask()`), **Feed** (LazyColumn untuk uji scroll/diff), **Legacy XML Activity + Fragment** (uji ViewWalker, `android:tag="artracker-mask"`, FragmentTracker), **Diagnostics** (tombol `track`, `identify`, request OkHttp sukses/404/timeout, *Crash now*, *ANR 10 s*, *Flush*, *Toggle offline queue counter*).
   - Panel debug menampilkan jumlah item pending di SQLite dan status kirim terakhir.

---

## 11. Taksonomi Event → Skema DB

| Aktivitas | `type` | `name` / `level` | Catatan |
| --- | --- | --- | --- |
| `track(name, props)` | `event` | name | props → attributes |
| `identify()` | `event` | `$identify` | traits → attributes |
| Activity/Fragment/Compose screen | `event` | `screen_view` | `{screen, previous, type}` |
| Foreground/background | `event` | `app_foreground` / `app_background` | |
| Sesi | `session` | `session_start` / `session_end` | metadata perangkat |
| Replay | `session` | `replay_chunk` | ≤24 KB |
| Crash JVM | `log` | `fatal`, name `crash` | dikirim pada launch berikutnya |
| ANR / native crash (API 30+) | `log` | `fatal`, name `anr` / `native_crash` | dari `ApplicationExitInfo` |
| `captureException` | `log` | `error`, name `exception` | |
| HTTP | `log` | `info`/`warn`/`error`, name `http_request` | |
| `log()` | `log` | sesuai level | |

---

## 12. Strategi Pengujian

| Lapis | Cakupan | Alat |
| --- | --- | --- |
| Unit (JVM) | UUIDv7, SessionManager (fake clock), ItemFactory, AttributeRedactor (korpus PII), Batcher, Backoff, PayloadEncoder (gzip round-trip), Chunker, WireframeDiffer, RrwebEncoder (snapshot JSON) | JUnit4 + Truth/kotlin.test |
| Robolectric | ViewWalker + MaskingPolicy (EditText per inputType, tag, subtree), EventStore (SQLite), CrashStore | Robolectric |
| Kontrak transport | Semua kode respons §2 (202/400/401/413/429 + Retry-After/503/timeout) | `MockWebServer` |
| Instrumented | Crash offline → relaunch → terkirim; Compose semantics walker; touch wrapper tidak mengubah perilaku | AndroidX Test di emulator |
| End-to-end | Sample app → backend lokal (`docker-compose`) → `GET /projects/:id/events` | Manual + skrip |
| Performa | Waktu walk per frame, jank (`FrameMetrics`), CPU background | Macrobenchmark, Perfetto |
| Validasi rrweb | JSON chunk dari sample app diputar di `rrweb-player` lokal | Halaman HTML kecil di `tools/` |

---

## 13. Keputusan & Risiko

| # | Hal | Keputusan (disetujui 2026-11-05) |
| --- | --- | --- |
| Q1 | Nama API | **`ARTracker.init`** (issue + parity web). Snippet `ARTracker.start` di landing page diperbarui terpisah |
| Q2 | Package | **Tetap `id.art.tracker`**: seluruh kode Kotlin SDK berada di package `id.art.tracker` (dan sub-package `core`, `transport`, …); groupId Maven `id.art.tracker`. Sample app **tidak diubah** (namespace & package tetap `id.art.tracker`). Agar kelas `R`/`BuildConfig` hasil generate tidak bentrok dengan app, properti Gradle `android.namespace` modul library diisi `id.art.tracker.sdk`. Ini hanya memengaruhi kelas generate, bukan package kode. SDK tidak boleh memakai sub-package `id.art.tracker.ui` (dipakai sample app) |
| Q3 | Format replay | Wireframe → rrweb (§6.1). Screenshot sebagai opsi masa depan |
| Q4 | Replay default | Off + sampling 0.1 (parity web) |
| Q5 | `maskAllInputs` default `true` | Lebih aman daripada hanya password/numerik; bisa dilonggarkan per view |
| R1 | `ViewRootForTest.semanticsOwner` dan `WindowManagerGlobal` adalah API internal/reflection | Dibungkus `runCatching` + fallback; diuji di setiap bump versi Compose |
| R2 | AGP 9.0.1 memakai built-in Kotlin, sedangkan catalog `kotlin = 2.0.21` (plugin compose). Kompatibilitas belum diverifikasi | Diverifikasi di Fase 0 dengan `./gradlew :artracker-sdk:assemble`; naikkan Kotlin bila perlu |
| R3 | Kuota 1000 event/s per project dibagi semua device | Replay sampling, dedup error, breadcrumbs tidak dikirim terpisah, batching 10 s |
| R4 | Tidak ada dedup server → retry setelah `202` hilang membuat baris ganda | UUIDv7 stabil; dedup di backend di masa depan |
| R5 | Backend belum punya endpoint replay; `rrweb-player` dashboard perlu menyusun chunk dari `telemetry_events` | Di luar scope issue ini, sama dengan web SDK |

---

## 14. Checklist Implementasi

### Fase 0 — Fondasi
- [ ] Buat modul `:artracker-sdk` (`com.android.library`), daftarkan di `settings.gradle.kts`, catalog, `explicitApi()`, `consumer-rules.pro`
- [ ] Library `namespace = "id.art.tracker.sdk"`, package Kotlin `id.art.tracker` (Q2); `app` bergantung pada `:artracker-sdk` tanpa mengubah namespace app
- [ ] Verifikasi build AGP 9 + Kotlin (R2); siapkan unit test + Robolectric

### Fase 1 — Core & API
- [ ] `ARTrackerConfig` (+ Java builder, validasi key `art_live_`), `ARTracker` facade no-op-safe
- [ ] UUIDv7, `ItemFactory`, `DeviceContext`, `SessionManager`, `IdentityStore`, `KillSwitch`
- [ ] `AppLifecycleObserver`, `ActivityTracker`, `FragmentTracker`, `trackNavController`, `screen()`

### Fase 2 — Transport & Offline
- [ ] `EventStore` (SQLite, prioritas, eviction, reset batch yatim)
- [ ] `EventPipeline` (Channel → redaksi → persist), `Batcher`, `PayloadEncoder` (stream gzip)
- [ ] `IngestClient` + tabel reaksi status + `Backoff` (Retry-After)
- [ ] `FlushWorker` (WorkManager, constraint `CONNECTED`) + config tersimpan untuk worker
- [ ] Tes kontrak `MockWebServer`

### Fase 3 — Telemetry
- [ ] `CrashHandler` + `CrashStore` (atomik, chain), konversi saat launch berikutnya
- [ ] `ExitInfoCollector` (API 30+)
- [ ] `Breadcrumbs`, `captureException`, `log()`, dedup fingerprint
- [ ] `ARTrackerOkHttpInterceptor` (compileOnly)
- [ ] Tes instrumentasi crash offline → relaunch (acceptance criteria #2)

### Fase 4 — Privacy Engine
- [ ] `MaskingPolicy`, `ViewMaskRules` (inputType, autofill, tag, kelas), `ComposeMaskRules`
- [ ] `artrackerMask()`/`artrackerUnmask()` untuk View & Compose, resource id tag
- [ ] `AttributeRedactor` (port korpus PII dari web SDK)

### Fase 5 — Replay
- [ ] `RootViewLocator`, `ViewWalker`, `ComposeWalker` (dengan fallback)
- [ ] `FrameScheduler` (dirty flag, throttle, watchdog, stop di background)
- [ ] `WireframeDiffer`, `RrwebEncoder`, `ReplayChunker`, sampling
- [ ] `TouchRecorder` (Window.Callback wrapper)
- [ ] Validasi pemutaran chunk di `rrweb-player`

### Fase 6 — Sample App & Pengerasan
- [ ] Layar demo (§10.5) + panel debug
- [ ] Macrobenchmark & Perfetto: walk ≤8 ms/frame, CPU background <1% (acceptance criteria #1)
- [ ] Audit: tidak ada exception SDK yang lolos ke app (fuzz API publik)

### Fase 7 — Rilis
- [ ] `maven-publish` (groupId `id.art.tracker`, artifact `artracker-android`), sources + javadoc jar
- [ ] README integrasi (Gradle, init, OkHttp, masking, ProGuard), CHANGELOG
- [ ] E2E ke backend lokal; perbarui snippet landing page bila nama API berubah (Q1)
