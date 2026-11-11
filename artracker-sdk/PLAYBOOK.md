# Playbook & Flow — `artracker-sdk` (Android)

> Pelengkap `ARCHITECTURE.md` (desain & keputusan). Dokumen ini fokus pada **cara kerja saat runtime** (flow) dan
> **apa yang dilakukan** saat integrasi, debugging, dan rilis (playbook). Diagram memakai Mermaid.
> Sumber: kode `src/main/java/id/art/tracker/` (cabang `feature/session-profiling`), termasuk modul `perf/` yang belum
> ada di `ARCHITECTURE.md`.

---

## Bagian A — Flow

### A1. Gambaran besar

```mermaid
flowchart LR
    subgraph Host["App host (main thread)"]
        API["ARTracker facade<br/>init/track/identify/screen/log/<br/>captureException/flush/reset/optOut"]
        LC["Lifecycle: Process, Activity,<br/>Fragment, Nav"]
        OK["OkHttp interceptor"]
        CR["CrashHandler<br/>(sinkron ke file)"]
        FS["FrameScheduler +<br/>ScreenshotCapturer"]
        TR["TouchRecorder"]
    end
    subgraph Worker["SDK worker (single thread IO)"]
        IF["ItemFactory<br/>UUIDv7 + timestamp"]
        PIPE["EventPipeline<br/>Channel → redaksi → persist"]
        ES[("EventStore<br/>artracker.db")]
        FL["Flusher<br/>claim ≤50 item/128 KB → gzip"]
        PERF["PerfMonitor<br/>(opt-in)"]
    end
    subgraph Proc["Thread pemrosesan (Default)"]
        ENC["ScreenshotEncoder<br/>mask → scale → JPEG"]
        CH["ScreenChunker"]
    end
    RS[("ReplayStore<br/>artracker_replay.db + file .gz")]
    RF["ReplayFlusher"]
    WM["FlushWorker<br/>(WorkManager, CONNECTED)"]
    BE[("Backend<br/>/api/v1/ingest<br/>/api/v1/replay/*")]

    API --> IF
    LC --> IF
    OK --> IF
    PERF --> IF
    IF --> PIPE --> ES --> FL --> BE
    CR -. file crash, dibaca saat init berikutnya .-> ES
    FS --> ENC --> CH --> RS --> RF --> BE
    TR --> CH
    FL -. gagal/background .-> WM
    RF -. background .-> WM
    WM --> FL
    WM --> RF
```

### A2. `ARTracker.init()` dan startup

```mermaid
sequenceDiagram
    participant App as Application.onCreate
    participant F as ARTracker (facade)
    participant C as ARTrackerCore
    participant W as Worker scope
    App->>F: init(context, config)
    Note over F: runCatching, idempoten<br/>(panggilan ke-2 diabaikan + warning)
    F->>C: bangun graph (validasi config, baca 3 SharedPreferences)
    F->>C: start()
    C->>C: CrashHandler.install (pertama, sedini mungkin)
    C->>W: launch onBackgroundInit
    W->>W: crashStore.prepare, warm-up regex redactor
    W->>W: CrashRecovery.recover → antrean SQLite
    W->>W: collectExitInfo (ANR/native, API 30+)
    W->>W: bila ada sisa item → pipeline.flushNow()
    C->>C: registerActivityLifecycleCallbacks
    C->>C: replay.enabled → ensureReplay().start()
    C->>C: performance.enabled → startPerformance() (monitor + memory callbacks, belum ada loop)
    C->>C: registerProcessObserver (ProcessLifecycleOwner)
    Note over C: ON_START di-replay ke observer → onForeground() tepat sekali
```

### A3. Foreground / background

```mermaid
stateDiagram-v2
    [*] --> Foreground: ProcessLifecycle ON_START
    Foreground --> Background: ON_STOP
    Background --> Foreground: ON_START

    state Foreground {
        [*] --> F1
        F1: session.onForeground (bisa rotasi sesi >30 mnt)
        F1 --> F2: replay.onAppForeground
        F2: pipeline.startTimer (flush tiap 10 dtk)
        F2 --> F3: emit app_foreground
        F3: startPerfLoop (bila opt-in)
    }
    state Background {
        [*] --> B1
        B1: replay.onAppBackground (stop capture, tutup chunk)
        B1 --> B2: stopPerfLoop
        B2: emit app_background
        B2 --> B3: perf.onBackground (flush sampel terakhir)
        B3: pipeline.stopTimer + flushNow
        B3 --> B4: kickReplayIfAny + scheduler.scheduleFlush (WorkManager)
        B4: session.onBackground
    }
```

Di background **tidak ada** capture, timer, atau sampling perf. Hanya satu `OneTimeWorkRequest` bila ada yang tertunda.

### A4. Jalur event → server

```mermaid
flowchart TD
    A["track / identify / screen / log /<br/>captureException / lifecycle / http / perf"] --> B{"isDisabled?<br/>(optOut atau kill-switch 401)"}
    B -- ya --> X["drop"]
    B -- tidak --> C["ItemFactory: id UUIDv7, timestamp,<br/>session_id, user_id"]
    C --> D["trySend ke Channel<br/>cap 1000, DROP_OLDEST<br/>(pemanggil tidak pernah blok)"]
    D --> E["beforeSend hook → AttributeRedactor<br/>(key sensitif, email, Luhn, JWT, Bearer)"]
    E --> F["Persist ke EventStore<br/>(transaksi tiap 1 dtk / 20 item)"]
    F --> G{"Pemicu flush?"}
    G -->|"timer 10 dtk, ≥50 item,<br/>crash baru, flush(), background"| H["Flusher.claimBatch<br/>≤50 item / ≤128 KB, urut priority"]
    H --> I["PayloadEncoder: JSON stream → gzip<br/>(>450 KB → belah)"]
    I --> J["IngestClient POST /api/v1/ingest"]
    J --> K{"Status?"}
    K -- 202 --> K1["hapus batch, reset backoff"]
    K -- 400 --> K2["drop permanen + debug log"]
    K -- 401 --> K3["KILL-SWITCH: stop kirim+rekam<br/>disabledUntil = now + 1 jam"]
    K -- 413 --> K4["belah dua; 1 item tetap besar → drop"]
    K -- "429 / 503" --> K5["hormati Retry-After (maks 1 jam),<br/>batch tetap pending"]
    K -- "IOException / offline" --> K6["backoff 2s→5m + jitter,<br/>jadwalkan FlushWorker (CONNECTED)"]
```

Prioritas antrean: `0` crash/fatal (tidak pernah di-evict) → `1` log error → `2` event/session.
Batas penyimpanan: 5 MB / 5000 baris, item >7 hari dibuang.

### A5. Crash → terkirim pada launch berikutnya

```mermaid
sequenceDiagram
    participant T as Thread yang crash
    participant H as CrashHandler
    participant FS as File crash (filesDir)
    participant OLD as Handler lama (Crashlytics, dll.)
    participant N as Launch berikutnya
    participant Q as EventStore
    participant S as Server
    T->>H: uncaughtException
    H->>FS: tulis sinkron via temp + rename (atomik, <50 ms)
    Note over H: tanpa SQLite & tanpa jaringan<br/>guard re-entrancy
    H->>OLD: selalu diteruskan
    N->>N: init() → onBackgroundInit
    N->>FS: CrashRecovery.recover
    FS->>Q: item log/fatal name=crash (prioritas 0)
    N->>S: flushNow() segera
    S-->>N: 202 → hapus
```

Sumber lain: `ExitInfoCollector` (API 30+) mengirim `anr`, `native_crash`, dan `low_memory` dari sesi lalu lewat pipeline normal.

### A6. Session replay (screenshot ter-mask)

```mermaid
flowchart TD
    subgraph Cap["Main thread"]
        S1["OnDrawListener → dirty = true"] --> S2["Tick 1000/fps ms<br/>capture hanya bila dirty<br/>+ debounce 100 ms setelah UP / ganti layar"]
        S2 --> S3["ScreenshotCapturer: PixelCopy (API 26+)<br/>atau View.draw; FLAG_SECURE tidak di-copy"]
        S3 --> S4["MaskCollector + ComposeMaskCollector:<br/>kumpulkan rect (MaskingPolicy)<br/>gagal baca → frame dibuang"]
    end
    subgraph Proc["Thread pemrosesan"]
        S4 --> P1["MaskPainter: cat mask di bitmap PENUH"]
        P1 --> P2["Fingerprint CRC 64×64<br/>(frame kembar dibuang, kecuali frame pertama,<br/>ganti layar, checkpoint 2 mnt)"]
        P2 --> P3["Scale (LOW 480 / STANDARD 720 / HIGH 1080) → JPEG"]
        P3 --> P4["ScreenChunker: tutup tiap 10 dtk,<br/>2 MB gzip, atau ganti sesi"]
    end
    T1["TouchRecorder (Window.Callback wrapper)<br/>DOWN/UP selalu, MOVE ≥50 ms"] --> P4
    P4 --> R1[("ReplayStore: file .gz + baris SQLite<br/>maks 50 MB / 200 batch / 8 attempt")]
    R1 --> U1["ReplayFlusher (1 batch sekali)"]
    U1 --> U2["POST /replay/chunk-url → upload_url"]
    U2 --> U3["PUT upload_url (gzip, tanpa X-Artracker-Key)"]
    U3 --> U4["POST /replay/chunk-ack"]
    U2 -- "403 screenshot_disabled" --> U5["buang chunk + antrean format itu,<br/>stop replay proses ini (bukan kill-switch)"]
    U2 -- "401/403 lain" --> U6["kill-switch"]
    U3 -- "401/403" --> U7["tanda tangan kedaluwarsa → ulang dari langkah 1"]
```

Governor: `ByteBudgetGovernor` membagi dua fps bila >5 MB / 60 dtk; bila masih lewat di fps minimum, kualitas turun satu preset. `FrameScheduler` menurunkan fps bila capture >16 ms tiga kali berturut-turut.
Gate awal: `replay.enabled = true` **dan** sampling deterministik `hash(session_id) < sampleRate`.

### A7. Session profiling (`perf/`, opt-in)

```mermaid
flowchart TD
    I["init: performance.enabled?"] -- tidak --> Z["tidak ada objek, thread, loop"]
    I -- ya --> M["startPerformance: PerfMonitor +<br/>MemoryWarningCallbacks (trim 10/15)"]
    M --> FG{"onForeground"}
    FG --> L["startPerfLoop: monitor.onForeground<br/>(frame source start, baseline baru)"]
    L --> T["tiap sampleIntervalMs (500..10000):<br/>PerfMonitor.tick"]
    T --> SA["PerfSampler: CPU % (/proc/self/stat),<br/>RSS, thread, fps/jank/frozen (FrameMetrics),<br/>baterai, thermal"]
    SA --> BUF["buffer sampel"]
    BUF --> FE{"≥10 sampel / ganti sesi /<br/>background / flush?"}
    FE -- ya --> EM["emit item perf.samples (≤60 sampel/item,<br/>device: ram_mb, cores, refresh_hz)"]
    SA --> TH["thermal berubah → event perf.thermal_change"]
    MW["onTrimMemory level 10/15 saat foreground"] --> MWE["event perf.memory_warning"]
    EM --> PIPE["EventPipeline (jalur A4)"]
    BG["onBackground"] --> STOP["flush sampel terakhir, hentikan loop & frame source"]
    OO["optOut"] --> DISC["PerfMonitor.discard (buang buffer)"]
```

Catatan: `perf.memory_warning` tidak dikirim di Android 14+ (level trim 10/15 tidak lagi dikirim OS). Target overhead belum tercapai di emulator (commit `6f65348`, lihat `PERF.md`).

---

## Bagian B — Playbook

### B1. Integrasi di app host

1. **Dependensi**: `implementation("id.art.tracker:artracker-android:<versi>")` (GitHub Packages, butuh token `read:packages`). Kotlin host ≥ 2.0.x didukung (stdlib dipin 2.0.21).
2. **Init** di `Application.onCreate()` sedini mungkin (agar `CrashHandler` terpasang awal):
   ```kotlin
   ARTracker.init(this, ARTrackerConfig(
       endpoint = "https://…", apiKey = "art_live_…",   // wajib prefix art_live_
       environment = "production",
       replay = ReplayConfig(enabled = false),          // default off, opt-in
       privacy = PrivacyConfig(maskAllInputs = true),
       performance = PerformanceConfig(enabled = false) // default off, opt-in
   ))
   ```
3. **Opsional**: `ARTrackerOkHttpInterceptor()` (OkHttp harus ada di app), `ARTracker.trackNavController(nav)` atau `ARTracker.screen("…")` untuk Compose, `@ARTrackerScreenName`.
4. **Privasi**: tandai area sensitif dengan `artrackerMask()` / `Modifier.artrackerMask()` / `android:tag="artracker-mask"`; kelas kustom lewat `PrivacyConfig.sensitiveViewClasses`.
5. **Logout**: `ARTracker.reset()`. **Consent**: `ARTracker.optOut(true)` (membersihkan antrean & berhenti merekam).
6. **ProGuard/R8**: `consumer-rules.pro` sudah menangani; tidak perlu `-keep` manual untuk facade.

Kontrak yang harus dijaga: semua API publik no-op sebelum `init()`, tidak pernah melempar exception ke app.

### B2. Verifikasi cepat setelah integrasi

| Cek | Cara | Hasil yang diharapkan |
| --- | --- | --- |
| SDK hidup | `ARTracker.diagnostics()` (panel di `DiagnosticsScreen` sample app) | pending count, status kirim terakhir, kill-switch |
| Event tiba | `ARTracker.track("x")` lalu `ARTracker.flush()` | `202` di debug log; item muncul di `GET /projects/:id/events` |
| Offline queue | Mode pesawat → `track` beberapa kali → online | item terkirim sendiri (WorkManager / init berikutnya) |
| Crash | Tombol *Crash now* → buka ulang app (online) | item `log/fatal` `name=crash` tiba |
| Masking | Layar Login/Payment sample app dengan `replay.sampleRate = 1.0` | kolom password/kartu tertutup pola silang di dashboard |
| Profiling | `PerformanceConfig(enabled = true)`, foreground 30 dtk | item `perf.samples` (3 batch pada interval 1 dtk) |

Selalu aktifkan `debug = true` saat verifikasi supaya `SdkLog` tampil.

### B3. Troubleshooting (gejala → penyebab → tindakan)

| Gejala | Kemungkinan penyebab | Tindakan |
| --- | --- | --- |
| Tidak ada data sama sekali | `init()` belum dipanggil / key bukan `art_live_*` / kill-switch 401 aktif / opt-out | Cek `diagnostics()`; kill-switch bertahan 1 jam (persisted); `optOut(false)` bila perlu |
| Berhenti kirim setelah `401` | Key salah/dicabut | Perbaiki key; tunggu `disabledUntil` atau hapus data app saat uji |
| Data lambat muncul | Di background hanya WorkManager (tunda OS) | Normal; `flush()` saat foreground untuk uji |
| `429` terus | Kuota project 1000 event/s dibagi semua device | Turunkan sampling replay/perf, kurangi event berisik |
| Replay kosong | `enabled=false`, sesi tak lolos sampling, `403 screenshot_disabled`, backend lama (`404` → batch dibuang) | Cek config + `sampleRate`; cek setting project; upgrade backend |
| Replay berhenti setelah kill-switch 401 pulih | Batasan diketahui: tidak auto-resume saat tetap foreground | `startReplay()` atau relaunch |
| Area layar hitam/silang tak terduga | `SurfaceView`/`TextureView`/`WebView`/map/`FLAG_SECURE` selalu di-mask | Perilaku by design |
| Crash tidak terkirim | App di-kill sebelum file ditulis (>50 ms) atau belum relaunch | Relaunch online; cek file `filesDir/artracker/crash/` |
| `perf.memory_warning` tidak muncul | Android 14+ tidak mengirim trim level 10/15 | Batasan platform |
| Custom `ARTrackerSender`, replay gagal | Sender selalu `POST` / tanpa body response | Dukung `method` (`PUT`) dan `body` (lihat KDoc, R6) |
| Request SDK muncul di Chucker/OkHttp | Memakai `NetworkConfig.sender` kustom | Disengaja; interceptor melewati host endpoint |

### B4. Runbook perubahan kode

| Mau mengubah… | Mulai dari | Jangan lupa |
| --- | --- | --- |
| Event/atribut baru | `ItemFactory`, `core/ARTrackerCore.kt` | Taksonomi `ARCHITECTURE.md §11`, batas atribut 24 KB, redaksi |
| Aturan masking | `privacy/MaskingPolicy`, `ViewMaskRules`, `ComposeMaskRules` | Fail-closed; tes Robolectric per inputType/tag/subtree |
| Respons status baru | `transport/Flusher`, `ReplayFlusher`, `SendResult` | Tes kontrak `MockWebServer`, tabel di `ARCHITECTURE.md §2` |
| Metrik perf baru | `perf/PerfSampler`, `PerfSample`, `PerfMonitor` | Bump `v` di payload bila bentuk berubah; ukur overhead (`PERF.md`) |
| Config baru | `ARTrackerConfig` | Constructor default Kotlin berubah → catat di CHANGELOG (host Kotlin perlu rekompilasi) |
| Dependensi baru | `build.gradle.kts` | Opsional ⇒ `compileOnly` + `ClassAvailability`; hindari memaksa versi ke app host |

Prinsip tetap: tidak crash/lambat app host (`runCatching` di tiap entry point), main thread hanya membaca View dan komposit window, masking sebelum scale/JPEG.

### B5. Checklist rilis

1. `CHANGELOG.md`: pindahkan `[Unreleased]` ke versi baru; catat perubahan breaking (mis. constructor `ARTrackerConfig`).
2. Naikkan versi SDK (`SDK_VERSION`/`build.gradle.kts`).
3. Jalankan tes JVM + Robolectric: `./gradlew :artracker-sdk:testDebugUnitTest`.
4. `scripts/verify-publication.sh` (repo Maven lokal terisolasi + consumer app percobaan).
5. Tag `vX.Y.Z` → `.github/workflows/publish.yml` mempublikasikan ke GitHub Packages (lihat `docs/releasing.md`, `docs/github-packages.md`).
6. Sinkronkan `ARCHITECTURE.md` dan dokumen ini bila ada flow yang berubah.

### B6. Utang verifikasi (belum dijalankan)

- Tes instrumentasi: crash offline → relaunch (acceptance criteria #2).
- Macrobenchmark/Perfetto: CPU background <1%, `init` ≤2 ms (kriteria #1); overhead profiling di emulator belum memenuhi target (`PERF.md`).
- Pemutaran chunk screenshot di dashboard dan E2E ke backend lokal (`docs/e2e-manual-test.md`).
- `ARCHITECTURE.md` belum memuat modul `perf/` (item `ItemType.PERF`, `perf.*`): perlu ditambahkan bersama merge cabang ini.
- POM belum punya lisensi (repo belum punya `LICENSE`).
