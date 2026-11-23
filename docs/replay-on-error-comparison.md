# Replay saat crash: kondisi sekarang vs refactor

**ARTracker Android SDK · Session replay** · 21 Nov 2026 · branch `feature/team-lead-feedback`

Tujuan: **hemat kuota data user**. Replay hanya dikirim ketika terjadi crash, ANR, atau error yang di-log, bukan sepanjang sesi.

Versi interaktif (timeline dan kalkulator kuota): [`replay-on-error-comparison.html`](replay-on-error-comparison.html).

Penanda di dokumen ini:

- **[fakta]**: dari kode atau `artracker-sdk/PERF.md`
- **[usulan]**: rancangan, belum diputuskan final
- **[terbuka]**: perlu keputusan

---

## Cara kerja mode saat error (analogi dashcam)

HP tetap merekam selama sesi, tapi tidak menyimpan semuanya dan tidak mengirim apa pun sampai terjadi masalah.

- Dashcam merekam sepanjang perjalanan, tapi memorinya hanya menyimpan potongan terakhir. Rekaman lama otomatis ditimpa.
- Kalau terjadi tabrakan, potongan terakhir itu yang disimpan.
- Kalau tidak terjadi apa-apa, rekamannya hilang begitu saja.

| | Terjadi terus selama sesi? | Bebannya |
|---|---|---|
| Ambil screenshot | Ya | CPU kecil (p95 6,2 ms per frame) |
| Simpan di HP | Hanya 60 detik terakhir (±maks 5 MB), yang lama ditimpa | Disk kecil |
| Kirim ke server | **Hanya kalau ada crash, ANR, atau error yang di-log** | **Kuota: 0 kalau tidak ada masalah** |

Merekam *hanya* saat crash tanpa merekam sebelumnya tidak mungkin, karena crash tidak bisa ditebak. Kalau perekaman baru dimulai setelah crash, momen penyebabnya sudah lewat.

## Gambaran singkat

Satu sesi 15 menit yang berakhir dengan crash. Tiap kotak = 30 detik rekaman.

```
Sekarang, sesi terpilih (10%)       → semua diupload, ±maks 51 MB
█████████████████████████████✖
Sekarang, sesi tidak terpilih (90%) → tidak merekam, crash tanpa replay
┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄✖
Refactor, semua sesi ke buffer      → hanya 60 detik terakhir diupload, ±maks 5 MB
░░░░░░░░░░░░░░░░░░░░░░░░░░░██✖
0 menit                                                  15 menit

█ direkam + diupload   ░ direkam lalu dibuang   ┄ tidak direkam   ✖ crash
```

## Perbandingan per aspek

| Aspek | Sekarang | Refactor (mode saat error) |
|---|---|---|
| **Siapa yang merekam** | **[fakta]** Sesi yang lolos `sampleRate` (default `0.1` = 10%). Keputusan dibuat per session id dengan hash FNV-1a yang sama dengan web SDK. Default `enabled = false`. | **[usulan]** Sesi yang lolos `onErrorSampleRate` (usulan default `1.0` = semua). Lebih banyak HP yang menjalankan capture. |
| **Apa yang diupload** | **[fakta]** Seluruh sesi. Chunk ditutup tiap 10 detik (maks 2 MB gzip), masuk antrean SQLite, lalu dikirim. | **[usulan]** Hanya ~60 detik terakhir sebelum pemicu. Sesi tanpa pemicu: 0 byte. |
| **Kuota per sesi terekam** | **[fakta]** Budget ±5 MB/menit. Terukur 16,91 MB untuk 5 menit scroll terus (2 fps, `STANDARD`), ±3,4 MB/menit. | ±1–5 MB per pemicu; 0 kalau tidak ada pemicu. |
| **CPU di main thread** | **[fakta]** Capture p95 6,2 ms per frame (target 16 ms), OPPO CPH2387, debug build. | Sama per frame. Usulan: 1 fps, bisa diturunkan ke 0,5 fps atau kualitas `LOW` di mode ini. |
| **Baterai** | **[fakta]** Belum diukur. CPU saat background < 0,1% (replay berhenti saat app di background). | Per HP sama atau sedikit lebih kecil (tanpa upload). Tetap perlu diukur. |
| **Penyimpanan di HP** | **[fakta]** Antrean replay maks 50 MB (`ReplayStore`), dikosongkan setelah upload. | **[usulan]** Buffer bergulir maks 13 chunk × 5 detik (60 detik) ≈ maks 5 MB; chunk lama ditimpa. |
| **Crash yang punya replay** | ±10% (hanya crash di sesi terpilih). | Hampir semua. Risiko: ≤10 detik terakhir yang belum ditulis ke disk bisa hilang saat proses mati. |
| **ANR** | **[fakta]** Dideteksi oleh watchdog (API 24–29, saat itu juga) dan `ApplicationExitInfo` (API 30+, baru terbaca saat app dibuka lagi). | API 24–29: upload langsung. API 30+: buffer dari disk dikirim saat app dibuka lagi. |
| **Error yang di-log** | **[fakta]** Hanya menandai chunk `has_error`. Flag ini belum dipakai oleh backend maupun dashboard. | **[usulan]** Memicu upload buffer dan perekaman lanjutan sebentar. Dibatasi per sesi supaya error beruntun tidak boros kuota. |
| **Privasi** | Masking di HP sebelum JPEG dibuat. | Masking sama. Data yang keluar dari HP jauh lebih sedikit. |
| **Sisi web/backend** | Replay dimulai dari awal sesi. | **[terbuka]** Replay dimulai di tengah sesi (frame pertama harus full snapshot). Dashboard perlu label "replay sebelum crash" dan tautan ke crash-nya. |
| **Kompleksitas SDK** | Sudah berjalan dan teruji. | Bagian baru: buffer bergulir di disk, status "ditahan" vs "siap kirim", pengiriman saat launch berikutnya, batas per sesi. |

## Hitung kuota (ilustrasi)

Ini **bukan data produksi**. Ganti asumsinya dengan trafik nyata, atau pakai kalkulator di versi HTML. Laju data memakai angka terukur 3,4 MB/menit saat scroll terus, jadi hasilnya batas atas. Sesi nyata lebih kecil karena ada mode idle dan frame yang sama tidak dikirim ulang.

**Asumsi:** 1.000 sesi per hari, 10 menit per sesi, sample rate sekarang 10%, 1% sesi crash/ANR, 5% sesi punya error yang di-log, buffer 60 detik, perekaman lanjut 30 detik setelah error, maksimal 1 upload error per sesi.

| | Perhitungan | Kuota per hari | Crash yang punya replay |
|---|---|---|---|
| **Sekarang** | 100 sesi × 10 mnt × 3,4 MB | **±3,3 GB** | ±1 dari 10 |
| **Refactor: crash/ANR** | 10 sesi × 1 mnt × 3,4 MB | ±34 MB | |
| **Refactor: error di-log** | 50 sesi × 1,5 mnt × 3,4 MB | ±255 MB | |
| **Refactor: total** | | **±289 MB** | 10 dari 10 |

## Pemicu upload

| Pemicu | Kapan terdeteksi | Penanganan |
|---|---|---|
| **Crash fatal** | Saat itu juga, proses langsung mati | Buffer harus sudah ada di disk. Dikirim saat app dibuka lagi, ditautkan ke crash yang sama lewat session id. |
| **ANR** | API 24–29: saat itu juga. API 30+: saat launch berikutnya | Watchdog: upload langsung. `ApplicationExitInfo`: cari buffer sesi itu di disk, lalu kirim. |
| **Error di-log** | App masih berjalan | Upload buffer, lalu rekam lanjut ±30 detik supaya akibatnya terlihat. Maksimal N upload per sesi. |

## Alur di HP

**Sekarang**

1. Sesi mulai, SDK mengecek `sampleRate`.
2. Kalau terpilih: capture → masking → JPEG → chunk 10 detik.
3. Chunk ditutup → antrean SQLite → upload.
4. Kalau ada error yang di-log: chunk diberi flag `has_error`.
5. Diulang sampai sesi selesai atau app masuk background.

**Refactor**

1. Sesi mulai, SDK mengecek `onErrorSampleRate`.
2. Kalau terpilih: capture → masking → JPEG → chunk 10 detik (sama seperti sekarang).
3. Chunk ditutup → disimpan dengan status **ditahan**. Hanya 60 detik terakhir (maks 13 chunk) yang disimpan.
4. Kalau ada pemicu: chunk sesi itu yang berstatus ditahan diubah menjadi **siap kirim** → upload.
5. Kalau sesi berakhir tanpa pemicu: chunk yang ditahan dihapus.

## Yang perlu diputuskan

1. **Mode lama tetap ada?** Usulan: tetap bisa dipakai (misalnya `sampleRate = 0.01` untuk melihat perilaku normal), tapi default-nya 0 dan mode saat error jadi default.
2. **Panjang buffer.** Usulan 60 detik. Buffer lebih panjang memberi konteks lebih banyak, tapi kuota per pemicu ikut naik.
3. **Batas upload error di-log per sesi.** Usulan maksimal 1–3 kali, dengan jeda antar-pemicu.
4. **fps dan kualitas di mode ini.** Usulan tetap 1 fps `STANDARD`; diturunkan kalau hasil pengukuran baterai buruk.
5. **Upload hanya lewat Wi-Fi?** Paling hemat kuota, tapi replay datang terlambat.
6. **Web SDK dan iOS.** Sampling sekarang identik di semua platform. Perlu disepakati apakah mode ini juga dibawa ke web dan iOS.
7. **Dashboard.** Perlu bisa menampilkan replay yang dimulai di tengah sesi dan menautkannya ke crash/error.
