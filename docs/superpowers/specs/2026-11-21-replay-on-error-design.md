# Replay saat error (Android SDK) — design

Tanggal: 2026-11-21 · Perbandingan lengkap: [`docs/replay-on-error-comparison.md`](../../replay-on-error-comparison.md)

## Intent

Hemat kuota data user. Sekarang sesi yang terpilih (`sampleRate`, default 10%) diupload penuh, ±3,4 MB/menit
saat scroll. Tim terutama butuh replay di sekitar masalah, jadi SDK merekam ke buffer bergulir di HP dan hanya
mengirimnya ketika terjadi **crash, ANR, atau error yang di-log**.

## Goals

- Sesi tanpa pemicu tidak mengupload byte replay apa pun.
- Crash fatal (JVM), ANR (watchdog API 24–29 dan `ApplicationExitInfo` API 30+), native crash (API 30+), dan item
  level `ERROR`/`FATAL` yang diterima pipeline memicu upload ~60 detik terakhir.
- Hampir semua crash punya replay (default `onErrorSampleRate = 1.0`).
- Mode lama (rekam dan kirim seluruh sesi) tetap ada lewat `sampleRate`, tapi default-nya 0.

## Non-goals

- Backend dan dashboard (repo `artracker`): menampilkan replay yang dimulai di tengah sesi dan menautkannya ke crash.
  Dicatat sebagai dependensi, dikerjakan terpisah.
- Web SDK dan iOS SDK.
- Upload hanya lewat Wi-Fi.
- Mengubah capture, masking, encoder, atau format `artracker-screen-v1`.

## Anti-goals

- Error beruntun (misalnya error API tiap detik) tidak boleh membuat upload hampir sepanjang sesi. Kalau itu terjadi,
  penghematan kuota hilang.
- Crash handler tidak boleh melakukan kerja database, network, atau lock (aturan `CrashHandler` yang sudah ada).
- Mode lama yang dinyalakan eksplisit harus berperilaku persis seperti sekarang.

## Keputusan

Diputuskan oleh user:

- Pemicu: crash, ANR, dan error yang di-log.
- Mode lama tetap ada, default 0; mode saat error jadi default.

Keputusan saya (bisa dikoreksi):

| Hal | Keputusan | Alasan |
|---|---|---|
| Config | `ReplayConfig.sampleRate` default `0.1` → `0.0`; tambah `onErrorSampleRate` default `1.0` | Nama sejalan dengan `sampleRate`; perubahan default dicatat di README/CHANGELOG sebagai perubahan perilaku |
| Pemilihan sesi | Sesi lolos `sampleRate` → mode **full** (seperti sekarang). Kalau tidak, sesi lolos `onErrorSampleRate` → mode **buffer**. Hash `Sampling.isSampled` yang sama, dengan salt berbeda untuk rate kedua supaya kedua keputusan independen | Satu sesi hanya punya satu mode |
| Panjang buffer | 60 detik: simpan chunk berstatus ditahan selama `last_ts` ≥ (chunk terbaru − 60 dtk), maksimal 8 chunk | Konteks cukup, ≤ ±5 MB |
| Window chunk di mode buffer | 5 detik (mode full tetap 10 detik) | Saat crash, chunk yang masih terbuka di memori hilang; window lebih kecil = yang hilang ≤ 5 dtk |
| Rekam lanjut setelah error di-log | 30 detik: chunk yang ditutup dalam 30 dtk setelah pemicu langsung siap kirim | Supaya akibat error terlihat |
| Batas per sesi | Maksimal 3 pemicu error di-log per sesi, jeda ≥ 60 dtk antar-pemicu. Crash dan ANR tidak dibatasi (prosesnya berhenti) | Mencegah anti-goal error beruntun |
| fps/kualitas | Sama seperti config (`framesPerSecond`, `quality`) | Tidak menambah knob; diukur dulu |
| `seq` | Chunk ditahan **belum** mendapat `seq`. `seq` dan `start_ms` sesi dialokasikan saat chunk dipromosikan menjadi siap kirim | Chunk yang dibuang tidak meninggalkan celah `seq`; `start_ms` = awal replay yang benar-benar dikirim |
| Hasil yang diupload | Chunk yang sama, endpoint dan ack yang sama. `has_error = true` pada chunk dari pemicu | Backend tidak perlu endpoint baru |

## Approaches yang dipertimbangkan

1. **Buffer di disk lewat `ReplayStore` dengan status `held` (dipilih).** Chunk yang ditutup ditulis seperti sekarang,
   tapi dengan `state = held`. Pemicu mengubahnya menjadi `ready`. Bertahan saat proses mati, jadi crash dan ANR API 30+
   bisa ditangani saat launch berikutnya. Menambah satu migrasi DB (v5) dan sedikit I/O disk per 5 dtk.
2. **Ring buffer di memori.** Paling ringan, tapi hilang saat crash. Ini justru pemicu terpenting, jadi ditolak.
3. **Ring buffer di memori + tulis darurat dari crash handler.** Melanggar aturan crash handler (I/O besar saat proses
   sekarat, bisa OOM). Tidak menangani ANR API 30+. Ditolak.

## Desain

### Alur

1. `ReplayController.shouldRecord` memilih mode per sesi (full / buffer / tidak merekam).
2. Mode buffer: `ScreenChunker` memakai window 5 dtk. Batch yang ditutup membawa `held = true` ke `ReplaySink`.
3. `ReplayIntake` → `ReplayStore.enqueue(held)`: tulis body + row `state = 0 (held)`, `seq` NULL, lalu buang chunk
   held sesi itu di luar jendela 60 dtk / 8 chunk. Uploader **tidak** di-kick untuk chunk held.
4. `claim()` hanya mengambil `state = 1 (ready)`. Eviction kapasitas global tetap berlaku untuk semua row.
5. **Pemicu saat proses hidup** (item `ERROR`/`FATAL` lewat `onItemAccepted`, termasuk ANR watchdog):
   - controller menutup chunk terbuka (ikut diberi `has_error`), lalu memanggil `promote(sessionId)`;
   - store, dalam satu transaksi: semua row held sesi itu → `ready`, alokasikan `seq` urut `first_ts`;
   - controller membuka jendela "lanjut" 30 dtk: chunk yang ditutup selama jendela itu langsung `ready`;
   - batas 3 pemicu/sesi dan jeda 60 dtk diterapkan di controller. Pemicu yang ditolak tetap menandai `has_error`
     seperti sekarang.
6. **Pemicu setelah proses mati** (saat init, di `onBackgroundInit`, setelah crash recovery dan `ExitInfoCollector`):
   - kalau run sebelumnya meninggalkan file crash (`CrashRecovery.recover` > 0) **atau** `ExitInfoCollector`
     mengembalikan item `anr`/`native_crash` → promosikan semua row held yang `created_at` < waktu start proses ini;
   - kalau tidak → hapus row held tersebut;
   - batasnya waktu, bukan session id, karena sesi bisa berlanjut setelah restart (session timeout belum lewat);
   - setelah promosi, kick uploader;
   - item exit/crash dari proses sebelumnya (timestamp < start proses ini) **tidak** dihitung sebagai pemicu error untuk
     sesi yang sedang berjalan.
7. Sesi berganti (timeout) tanpa pemicu: row held sesi lama dihapus saat sesi baru mulai.
   Setiap frame berisi JPEG utuh (tidak ada delta), jadi chunk mana pun bisa diputar sendiri; `seq` di dalam body chunk
   boleh melompat di mode buffer, yang dipakai server adalah `chunk_seq` di ack.
8. App ke background di mode buffer: chunk terbuka ditutup sebagai held (tidak di-flush ke network).

### Perubahan per komponen

- `ARTrackerConfig.kt`: `ReplayConfig.sampleRate` default 0.0, `onErrorSampleRate: Double = 1.0` (validasi 0..1);
  KDoc diperbarui.
- `replay/ReplayController.kt`: mode per sesi (`RecordingMode.FULL/BUFFER`), meneruskan mode ke chunker, logika pemicu
  (batas, jeda, jendela lanjut), `onTrigger(ERROR)` yang sudah ada dipakai untuk capture.
- `replay/screenshot/ScreenChunker.kt`: window per mode; batch membawa flag `held`.
- `transport/ReplayQueue.kt`: `NewReplayBatch.held`; `ReplayQueue.promote(sessionId)`, `promoteHeldBefore(cutoffMs)`,
  `dropHeld(sessionId)`, `dropHeldBefore(cutoffMs)`.
- `transport/ReplayStore.kt`: DB v5 — kolom `state INTEGER NOT NULL DEFAULT 1`; row held disimpan dengan `seq = -1`
  (kolom tetap NOT NULL, tanpa rebuild tabel) dan tidak menyentuh `replay_seq`; `nextPending` dan `pendingCount`
  hanya `state = 1`; trimming per sesi untuk held; migrasi v4→v5 (row lama = ready).
- `replay/ReplayIntake.kt` + `ReplaySink`: tidak kick untuk held; `ReplaySink` mendapat `promote(sessionId)` dan
  `discardHeld(sessionId)` (default no-op) supaya controller bisa memicu promosi lewat jalur yang sudah ada.
- `replay/ErrorTriggerPolicy.kt` (baru): batas 3 pemicu/sesi dan jeda 60 dtk, kelas murni.
- `replay/RecordingMode.kt` (baru): `FULL`/`BUFFER` dan pemilihan mode per sesi.
- `core/ARTrackerCore.kt`: menyambungkan pemicu proses hidup ke controller; memutuskan promosi/hapus saat init
  berdasarkan hasil crash recovery dan `ExitInfoCollector`.
- `Diagnostics`/log: `SdkLog.d` saat promosi ("replay buffer promoted: N chunks, reason").
- README, `docs/android-sdk-playbook.md`, CHANGELOG: config baru, perubahan default, perilaku.

### Kasus tepi

- Opt-out/kill-switch: semua row (held juga) ikut dihapus oleh `clear()` yang sudah ada.
- `403 screenshot_disabled`: `clearFormat` menghapus held juga; controller berhenti seperti sekarang.
- `startReplay()` manual: tetap mode full (melewati sampling), seperti sekarang.
- Retensi: `purgeOlderThan` tetap berlaku untuk held.
- Crash di sesi mode full: tidak ada yang berubah (sudah terkirim).
- Low-memory kill (API 30+, `WARN`): bukan pemicu; held dihapus.

## Dependensi di luar repo ini

Backend/dashboard harus menerima replay sesi yang `start_ms`-nya di tengah sesi dan chunk pertamanya full snapshot.
Hal ini perlu dicek di repo `artracker` sebelum rilis. Pencarian sekilas tidak menemukan pemakaian `has_error` di
backend.

## Yang diserahkan ke builder

- Nama kelas/fungsi internal, bentuk tes, dan urutan migrasi selama mengikuti desain di atas.
- Cara menyimpan "run sebelumnya crash" (flag dari crash recovery yang sudah ada atau query sederhana).
- Pengukuran: ulangi `ScreenshotBudgetTest` di mode buffer dan catat byte terupload (harus ~0 tanpa pemicu) di
  `PERF.md`.
