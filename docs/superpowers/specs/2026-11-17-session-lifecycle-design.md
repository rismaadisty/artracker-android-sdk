# Session Lifecycle: Background Retention & Idle Timeout (Android) — Design

Tanggal: 2026-11-17. Status: menunggu persetujuan.

## Intent

Session harus bertahan saat app masuk background dan baru berganti setelah idle lebih lama dari timeout yang diatur
consumer saat `ARTracker.init` (default 30 menit). Session harus pulih setelah process dibunuh di background, dan
screenshot recording hanya berjalan di foreground.

Audit 2026-11-17 menemukan semua itu **sudah** diimplementasikan di `main`:

- `ProcessLifecycleOwner` → `AppLifecycleObserver` → `ARTrackerCore.onForeground/onBackground`.
- `SessionManager.onBackground()` tidak mengakhiri session. `onForeground()` merotasi (`session_end` lalu
  `session_start`) hanya bila `now - lastActive > timeoutMs`.
- `ARTrackerConfig.sessionTimeoutMs` (default `1_800_000`, minimum `1000`, ada di konstruktor dan `Builder`).
- `session_id`, `session_started_at`, `session_last_active` dipersist di SharedPreferences `artracker_session` dan
  dibaca kembali oleh instance baru.
- `ReplayController.onAppBackground()` menghentikan capture, `onAppForeground()` mengevaluasi ulang. Session tetap hidup.

Jadi pekerjaan ini **mengunci perilaku itu dengan test dan meluruskan dokumentasi**, bukan mengubah perilaku.

## Goals

1. Test untuk jalur publik yang belum dites: `ARTrackerConfig.Builder.sessionTimeoutMs()`.
2. Test di level `ARTrackerCore` untuk restorasi session setelah process death (dalam timeout dan lewat timeout).
3. Test di `ReplayController` bahwa recording berlanjut di `session_id` yang sama setelah background singkat.
4. `ARCHITECTURE.md` §4.3 diselaraskan dengan kode dan README (timeout dihitung dari user input terakhir).
5. README `sessionTimeoutMs` menjelaskan perbedaan dengan iOS `sessionTimeout`.

## Non-goals

- Tidak ada perubahan di `artracker-sdk/src/main` (termasuk KDoc).
- Tidak mengubah acuan timeout ke "timestamp masuk background" (Opsi B ditolak).
- Tidak menyeragamkan semantik Android dan iOS. Perbedaannya hanya didokumentasikan.
- Tidak menambah guard untuk jam device yang dimundurkan (opsional di audit, tidak dipilih).

## Anti-goals

- Test yang diam-diam mengubah perilaku: semua test baru adalah test karakterisasi dan harus langsung lulus terhadap
  kode sekarang. Bila ada yang gagal, itu regresi yang dilaporkan, bukan alasan mengubah `src/main`.

## Approaches

| Opsi | Isi | Keputusan |
| --- | --- | --- |
| A: acuan "user input terakhir" | Perilaku sekarang, satu definisi idle di foreground, background, dan replay (`isExpired()`); sejalan dengan iOS yang juga mengukur dari aktivitas terakhir | **Dipilih** |
| B: acuan "timestamp masuk background" | Sesuai spesifikasi awal, ramah app pasif, tetapi dua definisi idle hidup bersamaan, membatalkan `4e431ed`, menggeser metrik, dan menambah selisih dengan iOS | Ditolak |
| Plan minimal `plans/2026-11-17-session-timeout-config.md` | Hanya test Builder + catatan satuan iOS | Dipakai sebagai dasar, ditambah goal 2–4 dan catatan iOS yang lebih lengkap |

## Design

1. **Builder** (`ARTrackerConfigTest`): `Builder.sessionTimeoutMs(60_000)` sampai ke config dan valid; `999` gagal
   validasi. Diambil apa adanya dari plan minimal.
2. **Process death** (`ARTrackerCoreTest`): core #1 masuk foreground, lalu background, lalu `ARTracker.resetForTest()`.
   Core #2 dibuat di atas SharedPreferences yang sama dengan `FakeClock` yang dimajukan.
   - Dalam timeout: tidak ada `session_start` atau `session_end` baru, dan item core #2 memakai `session_id` core #1.
   - Lewat timeout: `session_end` dengan `session_id` lama, lalu `session_start` baru, dalam urutan itu.

   Test di level `SessionManager` untuk kasus yang sama sudah ada (`restoredAcrossInstances`,
   `restoredAfterTimeout_endsOldAndStartsNew`) dan tidak diubah.
3. **Replay continuity** (`ReplayControllerTest`): recording berjalan, lalu background singkat (di bawah timeout), lalu
   foreground. Controller berjalan lagi dan batch sesudahnya ber-`session_id` sama dengan sebelumnya.
4. **`ARCHITECTURE.md` §4.3**: baris "Sesi baru" menjadi "saat init pertama, saat kembali ke foreground atau pada user
   input pertama setelah **tidak ada user input >`sessionTimeoutMs`** (default 30 menit), saat hard cap 24 jam, atau
   `ARTracker.reset()`. Backgrounding sendiri tidak mengakhiri sesi." Baris `session_end` diselaraskan.
5. **README** baris `sessionTimeoutMs` ditambah catatan bahwa padanan iOS adalah `ARTrackerConfig.sessionTimeout`, dengan
   tiga perbedaan:
   - satuannya **detik** (default `1800`);
   - di bawah minimum di-clamp, sedangkan Android menolak config;
   - setiap pembacaan session id oleh SDK memperpanjang sesi dan clock-nya monotonic, sedangkan Android hanya dihitung
     dari user input dan memakai wall clock.

   Ini dokumentasi perilaku, bukan kontrak.

## Diserahkan ke builder

- Penempatan test di dalam file dan nama helper, mengikuti pola yang ada (`startCoreWith`, `settle`,
  `ProcessLifecycleTestUtil`, `FakeClock`).
- Cara membuat core #2 di atas prefs yang sama: Robolectric mempertahankan SharedPreferences dalam satu test, dan
  `setUp()` hanya menghapus database, bukan prefs.
