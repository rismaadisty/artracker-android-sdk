# Technical Architecture Plan — `artracker-sdk` (Android)

> Status: **Disetujui** (Q1–Q5, lihat §13) — 2026-11-05. Implementasi SDK sampai Task 26 selesai (lihat §14); pengukuran performa di perangkat, E2E ke backend, dan uji instrumentasi belum dijalankan. Penyimpangan implementasi dari dokumen ini ada di bagian terakhir ("Deviations from this document").
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
2. **Main thread hanya melakukan yang wajib di main thread**: membaca properti View/semantics dan mengomposit window ke bitmap. Mask, scale, kompresi, serialisasi, redaksi, gzip, I/O, dan jaringan berjalan di worker thread.
3. **Dependensi minimal.** Tanpa Room, tanpa kotlinx.serialization, tanpa OkHttp sebagai dependensi wajib (lihat §9).
4. **Privasi secara default.** Masking terjadi saat capture, sebelum frame dikompresi, sehingga piksel sensitif tidak pernah masuk memori antrean.

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
| Replay | **Session Replay API**, bukan `/ingest`: `POST /api/v1/replay/chunk-url` (minta URL upload bertanda tangan) → `PUT {upload_url}` (body gzip, `Content-Type: application/gzip`) → `POST /api/v1/replay/chunk-ack`. Semua langkah idempotent di server, jadi retry mengulang ketiganya. `session_id` cocok `^[a-zA-Z0-9_-]{8,128}$`, `chunk_seq >= 0`, `size_bytes` 1..5 MiB (5242880) dan harus sama dengan panjang body PUT. `X-Artracker-Key` hanya untuk dua `POST`, tidak pernah ke `upload_url`. Rate limit 50 req/s per project (dibagi semua device). Field `direct` **tidak dikirim**: server memutuskan mode upload lewat `REPLAY_PROXY_UPLOAD`. SDK selalu menamai `format: "artracker-screen-v1"` (frame JPEG + sentuhan) di body `chunk-url`; `403 {"error":"screenshot_disabled"}` berarti pemilik project mematikan replay screenshot | `backend/internal/handler/replay.go`, `0004_session_replay.sql`, `0006_replay_frustration.sql` (repo `artracker`, commit `8af564a`) |

**Aturan SDK (sama dengan web):** maksimum **50 item / 128 KB uncompressed per request**, soft cap **24 KB per `attributes`**. Aturan ini hanya untuk `/ingest`; replay punya batas sendiri (lihat baris Replay dan §6.5).

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
│   ├─ ReplayControllerFactory.kt  Merakit ScreenshotKit (Compose hanya bila ada)
│   ├─ capture/
│   │   ├─ RootViewLocator.kt   Semua root window (Activity, Dialog, PopupWindow)
│   │   └─ FrameScheduler.kt    Dirty flag + throttle + budget watchdog
│   ├─ screenshot/              ScreenshotCapturer, MaskCollector, ScreenshotEncoder, ScreenChunker, ByteBudgetGovernor (§6.6)
│   ├─ touch/TouchRecorder.kt   Window.Callback wrapper → sentuhan down/move/up
│   └─ ReplayIntake.kt          Chunk tertutup → antrean upload (ReplayStore)
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
 FrameScheduler tick ─► ScreenshotCapturer ─► (thread pemrosesan) ScreenshotEncoder ─► ScreenChunker ─► ReplayStore ─► ReplayFlusher
                        (rect mask dikumpulkan di sini)          (mask dicat sebelum scale/JPEG)                         (Session Replay API)

 Item telemetry ──► Batcher ─► gzip ─► IngestClient ─► POST /api/v1/ingest
                                            (foreground: timer; background/offline: WorkManager)
```

---

## 4. Core Manager & Lifecycle Tracker

### 4.1 Inisialisasi

- `ARTracker.init()` dipanggil dari `Application.onCreate()`. Bagian sinkron di thread pemanggil (target **≤2 ms**, belum terukur di perangkat): validasi config, membaca tiga file `SharedPreferences` kecil (`artracker_session`, identitas, `artracker_transport`; pemuatan disk saat pertama kali, StrictMode dapat melaporkannya), membuat `ARTrackerCore`, memasang `CrashHandler` (harus sedini mungkin) dan `ActivityLifecycleCallbacks`.
- Bagian berat di worker: membuka SQLite, membaca crash file dari sesi lalu, `ExitInfoCollector`, flush awal.
- Idempoten: pemanggilan kedua diabaikan dengan warning.
- Tidak memakai `ContentProvider`/App Startup untuk auto-init, karena API key harus diberikan secara eksplisit.

### 4.2 Lifecycle

| Sumber | Mekanisme | Output |
| --- | --- | --- |
| Proses foreground/background | `ProcessLifecycleOwner` (`lifecycle-process`) | `session`/`app_foreground`, `app_background`; memicu flush + jadwal WorkManager saat background; jeda replay |
| Activity | `Application.ActivityLifecycleCallbacks` | `event`/`screen_view` `{screen, previous, type:"activity"}`; memberi tahu `ReplayController` bahwa root window berganti (→ frame berikutnya dipaksa terkirim) |
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
- Capture replay di main thread (komposit window + kumpulan rect mask); bitmap-nya diserahkan ke satu konteks `Dispatchers.Default` untuk mask, scale, JPEG, dan chunking.

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

## 6. Visual Recording (Screenshot Replay)

### 6.1 Keputusan format: **screenshot yang di-mask di perangkat**

Replay adalah urutan **screenshot asli** aplikasi. Data sensitif di-mask **di perangkat**, di bitmap ukuran penuh, sebelum bitmap itu di-scale atau dikompresi; tidak ada mode lain dan SDK tidak membaca pengaturan replay apa pun dari server. (Versi awal merekam wireframe → event rrweb; jalur itu sudah dihapus.) Dashboard menampilkan persis yang dilihat pengguna (font, icon, foto, warna, gerak), dengan konsekuensi: semua yang tidak di-mask ikut terkirim sebagai piksel, dan anggaran data dijaga oleh `ByteBudgetGovernor` (§6.6).

### 6.2 Capture

- **Root windows**: `RootViewLocator` memakai `WindowManagerGlobal.mViews` (reflection, dibungkus `runCatching`) agar dialog, bottom sheet, dan popup ikut terekam. Fallback: `decorView` activity aktif.
- **`ScreenshotCapturer`** (main thread): `PixelCopy` window Activity (API 26+; `View.draw` di API 24–25 atau bila copy gagal), dialog/popup di-`draw` di atas dengan dim window; satu bitmap ukuran layar dari `BitmapPool` (2 buffer dipakai ulang). Rect mask dikumpulkan di pass yang sama (§6.6).

### 6.3 Framing rate control (`FrameScheduler`)

1. `ViewTreeObserver.OnDrawListener` di setiap root hanya mengeset `dirty = true` (tanpa alokasi).
2. Handler main thread melakukan tick tiap `1000 / framesPerSecond` ms (default **1 fps**, rentang 0,5–10, disarankan 2). Capture **hanya bila dirty**.
3. Capture tambahan (debounce 100 ms) setelah `ACTION_UP` dan pergantian layar, agar efek tap terlihat.
4. **Budget watchdog**: bila waktu capture >16 ms pada 3 frame berturut-turut, fps diturunkan setengah. Kembali naik setelah 30 detik stabil. `ByteBudgetGovernor` ikut membatasi fps lewat `capFps`.
5. Berhenti total saat `app_background`, `stopReplay()`, kill-switch, sesi tidak lolos sampling, atau server menjawab `403 screenshot_disabled`.

### 6.4 Sentuhan

`TouchRecorder` membungkus `Window.Callback` setiap activity (meneruskan semua panggilan ke callback asli) dan hanya membaca koordinat. Tidak pernah ada `consume`. `DOWN` dan `UP` (`CANCEL` dihitung `UP`) selalu dicatat, `MOVE` paling banyak sekali per 50 ms; koordinat diubah ke dp dan ikut di chunk screenshot yang sudah berisi frame sesi yang sama. Tidak merekam ketukan keyboard.

### 6.5 Chunking, upload & sampling

- `ScreenChunker` (tutup di 10 detik sejak entri pertama, saat sesi berganti, saat frame berikutnya tidak muat di 2 MB gzip, atau saat recording berhenti) menghasilkan chunk `artracker-screen-v1`. Chunk **bukan item `session` di `/ingest`**: ia disimpan di antrean `artracker_replay.db` (`ReplayStore`) dan dikirim lewat Session Replay API (§2). Chunk gzip >5 MiB dibuang.
- `seq` ditetapkan oleh `ReplayStore` dan dipersist per session, jadi melanjutkan setelah proses restart pada session yang sama. `start_time` = timestamp entri pertama dari chunk pertama session itu.
- Flush tick tiap 5 detik menutup chunk yang sudah melewati jendelanya.
- Upload replay lewat `ARTrackerSender` bila host mengaturnya (`NetworkConfig.sender`), jadi Chucker, certificate pinning, dan proxy host ikut mencakupnya; tanpa sender dipakai `HttpURLConnection` bawaan (`ReplayClient`). Karena itu `SendRequest` punya `method` (`POST`, atau `PUT` untuk upload) dan `SendResponse` punya `body` (langkah `chunk-url` membaca `upload_url` darinya). `FlushWorker` di proses tanpa `init` selalu memakai transport bawaan.
- `403 {"error":"screenshot_disabled"}` pada `chunk-url` (→ `ReplayResult.ScreenshotDisabled`; 403 tanpa body terbaca diperlakukan sama) berarti pemilik project mematikan replay screenshot: chunk dibuang, chunk lain yang menunggu di antrean dihapus (`ReplayQueue.clearFormat`), dan `ReplayController.onScreenshotDisabled()` menghentikan perekaman di proses itu (chunk yang terbuka dibuang, dicatat sekali). Ini bukan kegagalan autentikasi: kill-switch tidak menyala. Proses berikutnya mencoba lagi.
- **Default `replay.enabled = false`** (opt-in, setara keputusan D2 web) dan `sampleRate = 0.1`. Sampling deterministik: `hash(session_id) < sampleRate`.

### 6.6 Pipeline screenshot

API: `ReplayConfig(enabled, sampleRate, framesPerSecond, quality = ReplayQuality.LOW|STANDARD|HIGH)`. Preset (sisi pendek gambar / kualitas JPEG): LOW 480 px / 50, STANDARD 720 px / 60, HIGH 1080 px / 75, tidak pernah di-upscale. `framesPerSecond` dijepit 0,5–10 (disarankan 2).

**Capture** (`replay/screenshot/`):

| Kelas | Tugas |
| --- | --- |
| `ScreenshotCapturer` (main thread) | `PixelCopy` window Activity (API 26+; `View.draw` di API 24–25 atau bila copy gagal), dialog/popup di-`draw` di atas dengan dim window; satu bitmap ukuran layar dari `BitmapPool` (2 buffer dipakai ulang). Window `FLAG_SECURE` tidak pernah di-copy/di-draw, areanya masuk daftar mask. Area keyboard (IME, proses lain) dikembalikan sebagai placeholder |
| `MaskCollector` / `ComposeMaskCollector` (main thread, satu pass yang sama) | Kumpulkan rect mask dalam koordinat layar dari `MaskingPolicy` (password, sensitif, mask eksplisit, `maskAllInputs`, `maskAllText`, `sensitiveViewClasses`, view terblokir) dan pass semantics Compose khusus mask (`ComposeMaskRules`, tanpa membaca gaya). Gagal membaca → frame dibuang |
| `ScreenshotEncoder` (thread pemrosesan) | Cat mask (`MaskPainter`, pola silang) **di bitmap ukuran penuh sebelum** fingerprint, scale, dan JPEG; fingerprint CRC dari downscale 64×64 menekan frame kembar; scale ke preset; JPEG |
| `ByteBudgetGovernor` | Jendela 60 s byte terenkode; >5 MB → fps dibagi dua (min 0,5, paling sering tiap 10 s); overrun menetap di lantai fps → kualitas turun satu preset (tidak naik lagi dalam sesi); pulih di bawah 80 % |
| `ScreenChunker` | Chunk `artracker-screen-v1` (JSON gzip `{format, platform:"android", seq, frames:[{ts,screen,w,h,vw,vh,mime,data(base64),masked}], touches:[{ts,x,y,kind}]}`), tutup di 10 s, 2 MB gzip, atau ganti sesi; frame yang tidak muat sendirian di-encode ulang satu preset lebih rendah, kalau masih tidak muat dibuang |

Pemicu capture memakai `FrameScheduler` (dirty flag + `requestSoon`), watchdog 16 ms per capture dan `capFps` dari governor. Keputusan: `SurfaceView`/`TextureView`/`VideoView`/peta/WebView **terblokir** oleh `MaskingPolicy`, sehingga areanya di-mask, bukan di-composite lewat `PixelCopy(surfaceView)`. Frame pertama sesi, pergantian layar (nama mentah, di memori saja), dan checkout tiap 2 menit selalu dikirim meskipun gambarnya sama; frame kembar lain tidak dikirim. Upload memakai `ReplayClient` (`format` ikut di body `chunk-url`, `event_count` = frame + sentuhan). Nama layar di tiap frame dibangun `ReplayHref` (disanitasi).

**Antrean**: lihat `ReplayStore` di bawah: badan chunk berupa file, batas 50 MB.

---

## 7. Privacy Masking Engine

Masking diterapkan **di pengumpul mask di perangkat** (lapis 1: piksel yang di-mask tidak pernah sampai ke JPEG), lalu redaksi atribut berjalan di pipeline (lapis 2).

### 7.1 Lapis 1 — UI

| Aturan | Default | Detail |
| --- | --- | --- |
| Password | **Selalu mask, tidak bisa di-unmask** | `EditText`/`TextView` dengan `inputType` variation `TEXT_PASSWORD`, `TEXT_VISIBLE_PASSWORD`, `TEXT_WEB_PASSWORD`, `NUMBER_PASSWORD`; `TransformationMethod` adalah `PasswordTransformationMethod`; Compose `SemanticsProperties.Password` |
| Numerik / telepon | Mask | `TYPE_CLASS_NUMBER`, `TYPE_CLASS_PHONE` (PIN, OTP, kartu, nominal) |
| Autofill hints sensitif | Mask | `password`, `creditCardNumber`, `creditCardSecurityCode`, `creditCardExpiration*`, `phone`, `phoneNumber`, `phoneNumberDevice`, `phoneNational`, `emailAddress`, `postalAddress`, `smsOTPCode`. Compose: `SemanticsProperties.ContentType` dengan padanan yang sama (UI 1.8+ saat runtime) |
| Semua input | Mask (`maskAllInputs = true`) | Semua `EditText` dan node Compose `EditableText`. Pengguna bisa melonggarkan per view dengan `artrackerUnmask()` (kecuali password) |
| Semua teks | Off (`maskAllText = false`) | Opsi untuk app finansial/kesehatan |
| Tag view | — | Padanan Android dari `data-artracker-mask`: `android:tag="artracker-mask"` di XML, `view.setTag(R.id.artracker_mask, true)`, atau `view.artrackerMask()`. Berlaku untuk **seluruh subtree**. `artracker-unmask` sebagai kebalikannya |
| Compose | — | `Modifier.artrackerMask()` = `semantics { this[ARTrackerMaskKey] = true }` |
| Kelas kustom | — | `PrivacyConfig.sensitiveViewClasses = setOf(CardInputView::class.java)` (cocok via `isInstance`) |
| Selalu diblok | — | `WebView`, `SurfaceView`, `TextureView`, `VideoView`, MapView: areanya selalu di-mask. Window `FLAG_SECURE` tidak pernah ditangkap (areanya di-mask) dan keyboard (proses lain) diganti placeholder. `ImageView`/`Image` biasa **ikut terkirim sebagai piksel** kecuali di-mask |

**Bentuk masking:** rect view/node yang di-mask dicat tertutup (pola silang opak) di bitmap ukuran penuh, **sebelum** fingerprint, scale, dan JPEG; tidak ada teks yang ikut dikirim. Prioritas keputusan: `password` > `unmask` eksplisit > `mask` eksplisit/kelas > aturan default.

### 7.2 Lapis 2 — Atribut (`AttributeRedactor`)

Port aturan web SDK agar konsisten:
- Key sensitif (`password|passwd|secret|token|authorization|cookie|api_key|card|cvv|pin|otp|ssn`) → `"[redacted]"` (sama persis dengan web SDK).
- Nilai: email, nomor kartu (lolos Luhn), JWT, dan `Bearer …` → di-redaksi.
- URL (http_request, href replay): query param sensitif → `[redacted]`, fragment dibuang.
- Berlaku untuk `track/identify/log/captureException`, breadcrumbs, dan atribut http. Chunk replay **tidak** melewati redaksi ini (pikselnya sudah di-mask di lapis 1), hanya nama layar tiap frame yang disanitasi (`ReplayHref`: redaksi, id path, URL-safe).

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
  priority INTEGER NOT NULL,     -- 0 crash/fatal, 1 log error, 2 event/session
  created_at INTEGER NOT NULL,
  size_bytes INTEGER NOT NULL,
  payload TEXT NOT NULL,         -- JSON RawItem yang sudah final (ter-redaksi)
  batch_id TEXT,                 -- NULL = pending; terisi = sedang dikirim
  attempts INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_items_pending ON items(batch_id, priority, created_at);
```

- **Write-ahead**: semua item dipersist sebelum dikirim, dengan transaksi berkelompok (tiap 1 detik atau 20 item) untuk menekan I/O.
- Batas: **5 MB / 5000 baris**. Eviction mulai dari `event`/`session` tertua, lalu log error. Crash/fatal tidak pernah di-evict.
- Item lebih tua dari 7 hari dibuang (backend menolak drift >7 hari untuk koreksi skew).
- Saat start, baris dengan `batch_id` yang tertinggal (proses mati di tengah kirim) di-reset ke pending.

#### Antrean replay — `ReplayStore` (`artracker_replay.db`)

Database terpisah, dibuka **malas**: tidak dibuat bila replay tidak pernah dipakai.

```sql
CREATE TABLE replay_batches (
  id TEXT PRIMARY KEY, session_id TEXT NOT NULL, seq INTEGER NOT NULL, created_at INTEGER NOT NULL,
  first_ts INTEGER NOT NULL, last_ts INTEGER NOT NULL, event_count INTEGER NOT NULL, has_error INTEGER NOT NULL,
  user_id TEXT, environment TEXT NOT NULL, file TEXT NOT NULL, format TEXT NOT NULL DEFAULT 'artracker-screen-v1',
  size_bytes INTEGER NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE replay_seq (session_id TEXT PRIMARY KEY, next_seq INTEGER NOT NULL, start_ms INTEGER NOT NULL, updated_at INTEGER NOT NULL);
```

- **Badan batch adalah file** di `noBackupFilesDir/artracker/replay/<id>.gz` (DB versi 3); kolom `file` menyimpan namanya dan `format` selalu `artracker-screen-v1`. Alasan: `CursorWindow` Android ±2 MB membuat BLOB besar bisa ditulis tapi tidak bisa dibaca. File ditulis sebelum barisnya dan dihapus bersama barisnya (selesai, evict, `clear`, purge); `resetOrphaned()` menyapu file tanpa baris. Upgrade dari versi lama membuang batch wireframe (rrweb) yang masih antre (SDK tidak lagi mengirim format itu); counter `seq` per sesi tetap.
- Batas: **50 MB / 200 batch**; eviction membuang batch pending tertua (yang sedang diunggah tidak pernah). Maksimal **8 attempt** per batch, lalu dibuang. Retensi **7 hari** (batch dan counter `seq`).
- `replay_seq` bertahan setelah batch selesai, di-`clear()` (opt-out), atau dievict, supaya `seq` tidak pernah dipakai ulang: PUT menimpa objek lama sedangkan ack ditolak server.
- `ReplayFlusher` mengunggah satu batch pada satu waktu (maks 2 batch per detik saat drain). `401/403` pada `chunk-url`/`chunk-ack` menyalakan kill-switch; `401/403` pada PUT berarti tanda tangan kedaluwarsa dan hanya mengulang dari langkah 1. Opt-out mengosongkan antrean dan membuang batch yang terbuka. `FlushWorker` ikut mengirim antrean ini di background.

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
   - Layar **Login** (password + email, untuk uji masking), **Payment** (kartu/CVV numerik, `Modifier.artrackerMask()`), **Feed** (LazyColumn untuk uji scroll), **Legacy XML Activity + Fragment** (uji masking View, `android:tag="artracker-mask"`, FragmentTracker), **Diagnostics** (tombol `track`, `identify`, request OkHttp sukses/404/timeout, *Crash now*, *ANR 10 s*, *Flush*, *Toggle offline queue counter*).
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
| Replay | *(bukan item ingest)* | chunk `artracker-screen-v1` lewat Session Replay API | ≤5 MiB gzip per upload (chunk 2 MB) |
| Crash JVM | `log` | `fatal`, name `crash` | dikirim pada launch berikutnya |
| ANR / native crash (API 30+) | `log` | `fatal`, name `anr` / `native_crash` | dari `ApplicationExitInfo` |
| `captureException` | `log` | `error`, name `exception` | |
| HTTP | `log` | `info`/`warn`/`error`, name `http_request` | |
| `log()` | `log` | sesuai level | |

---

## 12. Strategi Pengujian

| Lapis | Cakupan | Alat |
| --- | --- | --- |
| Unit (JVM) | UUIDv7, SessionManager (fake clock), ItemFactory, AttributeRedactor (korpus PII), Batcher, Backoff, PayloadEncoder (gzip round-trip), ScreenChunker, ScreenshotEncoder, ByteBudgetGovernor | JUnit4 + Truth/kotlin.test |
| Robolectric | MaskCollector + MaskingPolicy (EditText per inputType, tag, subtree), ReplayController, EventStore (SQLite), CrashStore | Robolectric |
| Kontrak transport | Semua kode respons §2 (202/400/401/413/429 + Retry-After/503/timeout) | `MockWebServer` |
| Instrumented | Crash offline → relaunch → terkirim; Compose semantics mask; touch wrapper tidak mengubah perilaku | AndroidX Test di emulator |
| End-to-end | Sample app → backend lokal (`docker-compose`) → `GET /projects/:id/events` | Manual + skrip |
| Performa | Waktu capture per frame, jank (`FrameMetrics`), anggaran data, CPU background | Macrobenchmark, Perfetto |

---

## 13. Keputusan & Risiko

| # | Hal | Keputusan (disetujui 2026-11-05) |
| --- | --- | --- |
| Q1 | Nama API | **`ARTracker.init`** (issue + parity web). Snippet `ARTracker.start` di landing page diperbarui terpisah |
| Q2 | Package | **Tetap `id.art.tracker`**: seluruh kode Kotlin SDK berada di package `id.art.tracker` (dan sub-package `core`, `transport`, …); groupId Maven `id.art.tracker`. Sample app **tidak diubah** (namespace & package tetap `id.art.tracker`). Agar kelas `R`/`BuildConfig` hasil generate tidak bentrok dengan app, properti Gradle `android.namespace` modul library diisi `id.art.tracker.sdk`. Ini hanya memengaruhi kelas generate, bukan package kode. SDK tidak boleh memakai sub-package `id.art.tracker.ui` (dipakai sample app) |
| Q3 | Format replay | Screenshot yang di-mask di perangkat (§6.1) sebagai satu-satunya format; wireframe → rrweb dan pembacaan konfigurasi mode dari server dihapus |
| Q4 | Replay default | Off + sampling 0.1 (parity web) |
| Q5 | `maskAllInputs` default `true` | Lebih aman daripada hanya password/numerik; bisa dilonggarkan per view |
| R1 | `ViewRootForTest.semanticsOwner` dan `WindowManagerGlobal` adalah API internal/reflection | Dibungkus `runCatching` + fallback; diuji di setiap bump versi Compose |
| R2 | AGP 9.0.1 memakai built-in Kotlin, sedangkan catalog `kotlin = 2.0.21` (plugin compose). Kompatibilitas belum diverifikasi | Diverifikasi di Fase 0 dengan `./gradlew :artracker-sdk:assemble`; naikkan Kotlin bila perlu |
| R3 | Kuota 1000 event/s per project dibagi semua device | Replay sampling, dedup error, breadcrumbs tidak dikirim terpisah, batching 10 s |
| R4 | Tidak ada dedup server → retry setelah `202` hilang membuat baris ganda | UUIDv7 stabil; dedup di backend di masa depan |
| R5 | ~~Backend belum punya endpoint replay~~ **Selesai**: SDK memakai Session Replay API (`chunk-url` → `PUT` → `chunk-ack`). Backend lama tanpa endpoint itu membalas `404`, sehingga batch dibuang dan replay tidak tampil sampai backend di-upgrade | Lihat §2 dan §6.5 |
| R6 | `ARTrackerSender` kustom yang selalu `POST` atau tidak mengembalikan body response merusak upload replay (`chunk-url` tanpa `upload_url` di-retry lalu dibuang). Kontrak sender sekarang mewajibkan `method` dan `body` | Terdokumentasi di KDoc `ARTrackerSender`, README, dan CHANGELOG; `ARTrackerOkHttpSender` sudah memenuhinya. API masih eksperimental |

---

## 14. Checklist Implementasi

> **Status (2026-11-06):** Fase 0–5 selesai di JVM/Robolectric (kecuali dua item di bawah yang belum dijalankan); sample app (Fase 6) dan audit fuzz selesai.
> **Belum dijalankan/diukur** (butuh emulator, perangkat, atau Docker, yang tidak tersedia saat Task 25): tes instrumentasi crash offline,
> pemutaran chunk di dashboard, Macrobenchmark/Perfetto (CPU background <1%, `init` ≤2 ms), dan E2E ke backend lokal
> (`docs/e2e-manual-test.md`). Ukuran AAR rilis terukur: 398621 byte (`PERF.md`).
> Fase 7: konfigurasi publikasi, README, dan CHANGELOG selesai (Task 26, `0.1.0`); publikasi ke repositori publik dan E2E belum dikerjakan.


### Fase 0 — Fondasi
- [x] Buat modul `:artracker-sdk` (`com.android.library`), daftarkan di `settings.gradle.kts`, catalog, `explicitApi()`, `consumer-rules.pro`
- [x] Library `namespace = "id.art.tracker.sdk"`, package Kotlin `id.art.tracker` (Q2); `app` bergantung pada `:artracker-sdk` tanpa mengubah namespace app
- [x] Verifikasi build AGP 9 + Kotlin (R2); siapkan unit test + Robolectric

### Fase 1 — Core & API
- [x] `ARTrackerConfig` (+ Java builder, validasi key `art_live_`), `ARTracker` facade no-op-safe
- [x] UUIDv7, `ItemFactory`, `DeviceContext`, `SessionManager`, `IdentityStore`, `KillSwitch`
- [x] `AppLifecycleObserver`, `ActivityTracker`, `FragmentTracker`, `trackNavController`, `screen()`

### Fase 2 — Transport & Offline
- [x] `EventStore` (SQLite, prioritas, eviction, reset batch yatim)
- [x] `EventPipeline` (Channel → redaksi → persist), `Batcher`, `PayloadEncoder` (stream gzip) — batching ada di `ItemQueue.claimBatch` (tidak ada kelas `Batcher` terpisah)
- [x] `IngestClient` + tabel reaksi status + `Backoff` (Retry-After)
- [x] `FlushWorker` (WorkManager, constraint `CONNECTED`) + config tersimpan untuk worker
- [x] Tes kontrak `MockWebServer`

### Fase 3 — Telemetry
- [x] `CrashHandler` + `CrashStore` (atomik, chain), konversi saat launch berikutnya
- [x] `ExitInfoCollector` (API 30+)
- [x] `Breadcrumbs`, `captureException`, `log()`, dedup fingerprint
- [x] `ARTrackerOkHttpInterceptor` (compileOnly)
- [ ] Tes instrumentasi crash offline → relaunch (acceptance criteria #2) *(belum: butuh emulator; hanya ada tes JVM `CrashRecoveryTest`)*

### Fase 4 — Privacy Engine
- [x] `MaskingPolicy`, `ViewMaskRules` (inputType, autofill, tag, kelas), `ComposeMaskRules`
- [x] `artrackerMask()`/`artrackerUnmask()` untuk View & Compose, resource id tag
- [x] `AttributeRedactor` (port korpus PII dari web SDK)

### Fase 5 — Replay
- [x] `RootViewLocator`, `ScreenshotCapturer`, `MaskCollector`, `ComposeMaskCollector` (dengan fallback)
- [x] `FrameScheduler` (dirty flag, throttle, watchdog, stop di background)
- [x] `ScreenshotEncoder`, `ScreenChunker`, `ByteBudgetGovernor`, sampling
- [x] `TouchRecorder` (Window.Callback wrapper)
- [ ] Validasi pemutaran chunk screenshot di dashboard *(belum diputar)*

### Fase 6 — Sample App & Pengerasan
- [x] Layar demo (§10.5) + panel debug — layar Login/Payment/Feed/Legacy/Diagnostics; panel debug = `DiagnosticsScreen`
- [ ] Macrobenchmark & Perfetto: walk ≤8 ms/frame, CPU background <1% (acceptance criteria #1) *(belum diukur: butuh emulator/perangkat; prosedur dan tabel hasil di `PERF.md`)*
- [x] Audit: tidak ada exception SDK yang lolos ke app (fuzz API publik) — `PublicApiFuzzTest` (1000 iterasi, seed tetap): 3 bug ditemukan dan diperbaiki (pipeline mati oleh `Error`, `Number` non-JSON, surrogate tunggal)

### Fase 7 — Rilis
- [x] `maven-publish` (groupId `id.art.tracker`, artifact `artracker-android`), sources + javadoc jar — hanya `publishToMavenLocal` yang diuji; repositori remote dan kredensial sengaja tidak dikonfigurasi (placeholder terdokumentasi di `build.gradle.kts`); metadata POM (lisensi, developer, SCM) masih `TODO(owner)`
- [x] README integrasi (Gradle, init, OkHttp, masking, ProGuard), CHANGELOG (`artracker-sdk/CHANGELOG.md`)
- [ ] E2E ke backend lokal *(belum: butuh Docker + emulator; checklist di `docs/e2e-manual-test.md`)*
- [ ] Perbarui snippet landing page bila nama API berubah (Q1) *(di luar repo ini)*

---

## Deviations from this document (as implemented)

Hal-hal berikut berbeda dari teks di atas (atau melengkapinya) setelah implementasi. Sumber: ledger Task 0–25 dan kode.

**Struktur & build**
- **Namespace/package (Q2)**: `android.namespace` modul SDK `id.art.tracker.sdk` hanya untuk `R`/`BuildConfig`; seluruh kode tetap di `id.art.tracker`. Akibatnya `R.id.artracker_mask` adalah `id.art.tracker.sdk.R.id`.
- **Penomoran Task**: Task 4 dikerjakan sebelum Task 3 (`ARTrackerConfig` butuh `TelemetryItem`/`BeforeSend`). Hanya mengubah urutan.
- **R2 (Kotlin/AGP 9)**: build berjalan dengan Kotlin built-in AGP 9.0.1 (2.2.10) sementara plugin `kotlin.compose` di catalog masih `2.0.21`; sample app memakai versi `coreKtx 1.17.0`, `lifecycle 2.9.4`, `activityCompose 1.11.0` (diturunkan karena versi asli butuh AGP 9.1+/compileSdk 37). SDK dikompilasi (`compileOnly`) dan diuji terhadap Compose BOM `2025.07.00` (UI 1.8.3, entri catalog `composeBomSdk`) karena `SemanticsProperties.ContentType` baru publik di 1.8; BOM `app/` tetap `2024.09.00`, tetapi runtime sample di-resolve ke UI 1.9.0 oleh sebuah constraint transitif, jadi sample **bukan** harness Compose 1.7.
- **Batcher**: tidak ada kelas `Batcher`; batching ada di `ItemQueue.claimBatch` (sudah tercatat di Fase 2).
- **Javadoc jar**: dibuat oleh Dokka 1.4.32 bawaan AGP (butuh jaringan sekali untuk mengunduhnya); artefak `0.1.0` memuat `.aar`, `.pom`, `.module`, `-sources.jar`, `-javadoc.jar`.
- **Consumer rules**: tidak ada `-keep` untuk `ARTracker`/`ARTrackerConfig*` (dipanggil langsung oleh host, jadi tetap bisa di-shrink); yang di-keep hanya `@ARTrackerScreenName` (dibaca lewat refleksi), nama `FlushWorker`, kelas pemisah untuk library opsional, dan konstruktor interceptor (`allowshrinking`).

**Transport & core**
- **`ItemQueue.insert` mengembalikan `Boolean`** (false bila ditolak/gagal), supaya pipeline tahu bahwa item crash/replay tidak tersimpan.
- **`ItemQueue.clear()`** ditambahkan: `optOut(true)` juga menghapus antrean yang tertunda (paritas web SDK), bukan hanya berhenti merekam.
- **`Backoff` stateless**: `reset()` no-op; `Flusher` memegang penghitung kegagalan beruntun dan memanggil `delayFor(attempt)`. `Retry-After` hanya format delta-detik, dibatasi 1 jam; 3xx/1xx yang tak terpetakan diperlakukan sebagai retry.
- **`EventPipeline.flushNow()`** (non-blocking, terurut setelah item yang sudah di-enqueue; `requestFlush()` adalah alias): flush latar belakang/eksplisit mem-persist grup/channel yang tertunda lebih dulu supaya `app_background`/`session_end` tidak terlambat.
- **Dispatch listener `SessionManager` setelah lock dilepas** (transisi state atomik di dalam lock, callback sesudahnya, berurutan).
- **Loop flush menangkap `Throwable`** (bukan hanya `Exception`): `Error` dari `Flusher.step` tidak lagi mematikan pengiriman; `FlushWorker` mengembalikan `Result.retry()`.
- **Kill-switch** `401` menyimpan keadaan di prefs `artracker_transport` yang sama dengan kunci API publik untuk `FlushWorker`.

**Telemetry**
- **Item crash** (§8.1): ditulis sinkron (hanya redaksi pola nilai, karena proses sedang mati), lalu saat launch berikutnya `CrashRecovery` mengurainya kembali menjadi `TelemetryItem` dan memprosesnya lewat `EventPipeline.prepareRecovered` (`beforeSend` + redaksi pola dan key yang sama dengan item lain) sebelum masuk antrean. `beforeSend` yang mengembalikan `null` membuang crash (file dihapus); file yang tidak bisa diurai dibuang (fail closed); `beforeSend` yang melempar exception menyisakan item asli.
- **`ExitInfoCollector`** melaporkan semua exit yang disimpan OS (sampai ~16) pada `init` pertama setelah instal, termasuk yang terjadi sebelum SDK dipasang; trace native-crash API 31+ adalah tombstone protobuf.
- **Interceptor**: `req_bytes`/`res_bytes` dari `Content-Length` saja; `captureHeaders` dicadangkan dan diabaikan; pengabaian host endpoint SDK membandingkan host dan port efektif.

**Privacy**
- **`AttributeRedactor`**: nomor kartu yang lolos Luhn diganti `**** **** **** NNNN` (paritas perilaku web SDK; 4 digit terakhir dipertahankan), bukan `[redacted]` penuh.
- **Aturan input sensitif** (numerik/telepon/autofill) berlaku untuk **view apa pun**, bukan hanya `EditText` (fail-closed); sebuah view dianggap input bila `EditText`, `onCheckIsTextEditor()`, atau `TextView` dengan `inputType != 0`. Tag string `artracker-mask`/`artracker-unmask` tidak peka huruf besar/kecil; hint autofill `newPassword` ikut dianggap sensitif.
- **`artrackerUnmask()`** (View dan Compose) menimpa mask yang diwarisi untuk **seluruh subtree** node itu (bukan hanya node itu), sehingga unmask di dalam kontainer yang di-mask membuka kembali subtree-nya.
- **Compose**: tidak ada semantics input-type (keyboard type tidak ada di pohon semantics), jadi field PIN/angka Compose tanpa `ContentType` hanya ter-mask lewat `Password`, `maskAllInputs`, atau `Modifier.artrackerMask()`. Dari UI 1.8, `ComposeContentTypeRules` membaca `SemanticsProperties.ContentType` (dibandingkan dengan identitas konstanta companion): password / new password selalu di-mask (unmask tidak berlaku), kartu/telepon/email/alamat pos/OTP di-mask kecuali di-unmask eksplisit, dan gabungan (`a + b`) atau `ContentType("hint")` kustom di-mask karena hint-nya tidak terbaca (fail closed). Di Compose 1.7 saat runtime, probe (`ClassAvailability.probe`, panggilan langsung yang menangkap `LinkageError`) gagal dan aturan `ContentType` dilewati; aturan lain tetap sama.

**Replay**
- **Kedalaman JSON**: `Json.MAX_DEPTH = 32` dilonggarkan untuk dokumen chunk: `ScreenChunker` meng-encode dengan `Json.REPLAY_MAX_DEPTH = 256`. Chunk replay tidak melewati `EventPipeline`: tidak ada pengecualian redaksi atau prioritas khusus untuk item bernama `replay_chunk`.
- **`ReplayHref`**: `href` `android://<screen>` dibangun dari nama layar yang disuplai host dan disanitasi (scrub URL, masking id di path, batas 200 karakter), karena batch replay tidak melewati lapis 2. Pergantian layar dideteksi pada nama mentah (callback `onScreenChanged`), bukan pada href yang sudah disanitasi, dan memaksa frame berikutnya terkirim.
- **`ComposeMaskCollector`**: `ReflectiveSemanticsAccess` tidak memakai refleksi sama sekali; ia menautkan langsung ke `ViewRootForTest` (API internal Compose) dan dibungkus try/catch dengan fallback: seluruh host Compose di-mask. Di-instansiasi hanya di balik `ClassAvailability.compose`.
- **`stopReplay()`** mematikan perekaman juga untuk sesi berikutnya sampai `startReplay()` dipanggil.
- `seq` chunk mulai dari 0 lagi pada tiap proses.

**Pengujian**
- Fuzz API publik (`PublicApiFuzzTest`, 1000 iterasi) menemukan dan memperbaiki 3 bug (pipeline mati oleh `Error`, `Number` non-JSON, surrogate tunggal yang tertulis sebagai JSON tidak valid; sekarang U+FFFD).
- Belum ada tes instrumentasi, E2E, atau pengukuran performa (butuh emulator/Docker); lihat `PERF.md` dan `docs/e2e-manual-test.md`.
