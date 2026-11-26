# 1cdx brief : Technical & Product Brief: Native Android Code Editor + SFTP Client (Samsung DeX Optimized)

Android Visual Studi Code Alternatife with dex support

## 1. Ringkasan Proyek & Tujuan
Membangun aplikasi *code editor* Android *native* berbasis **Kotlin** yang ringan, responsif, dan dioptimalkan khusus untuk penggunaan layar eksternal (**Samsung DeX**) dengan keyboard fisik dan mouse. Aplikasi ini dirancang sebagai alternatif ringkas pengganti VS Code dengan fitur **SFTP Client terintegrasi** untuk alur kerja *remote development* langsung ke server/VPS.

**Target Performa Utama:**
* **Penggunaan RAM:** < 120 MB saat membuka beberapa tab file secara bersamaan (jauh lebih ringan dibandingkan editor berbasis WebView/Electron).
* **Latensi Ketik:** *Zero-lag* pada file kode hingga puluhan ribu baris.
* **Alur Kerja Utama:** Browse direktori server via SFTP → Edit kode secara lokal di cache → Auto-upload ke server secara instan saat menekan `Ctrl + S`.

---

## 2. Stack Teknologi & Arsitektur
* **Bahasa Pemrograman:** 100% Kotlin
* **Minimum / Target SDK:** `minSdk 26` (Android 8.0) / `targetSdk 35`
* **Arsitektur Aplikasi:** MVVM + Clean Architecture dengan *unidirectional data flow* (`StateFlow`).
* **Concurrency:** Kotlin Coroutines (`Dispatchers.IO` untuk seluruh operasi jaringan SFTP dan file I/O; `Dispatchers.Main` untuk pembaruan UI).
* **UI Framework:** Jetpack Compose atau XML dengan `ConstraintLayout` / `SlidingPaneLayout` yang mendukung tata letak *multi-pane* yang dapat diubah ukurannya (*resizable*).
* **Keamanan Kredensial:** `EncryptedSharedPreferences` / Android Keystore untuk menyimpan kata sandi SSH dan *private key*.

---

## 3. Rekomendasi Library Inti (Core Dependencies)

1. **Mesin Editor Kode: `sora-editor`**
   * **Artefak:** `io.github.Rosemoe.sora-editor:editor` & `io.github.Rosemoe.sora-editor:language-textmate`
   * **Alasan:** Menggunakan *custom canvas rendering* (bukan `EditText` standar Android), mendukung *TextMate Grammar* (`.tmLanguage.json`), tema warna standar VS Code (Dark+, Monokai, Dracula), *code folding*, *bracket matching*, *auto-indentation*, dan *multi-cursor*.
2. **Mesin SFTP & SSH: `mwiede/jsch`**
   * **Artefak:** `com.github.mwiede:jsch`
   * **Alasan:** *Fork* modern yang aktif dipelihara dari JSch dengan dukungan algoritma kriptografi OpenSSH terbaru (`ed25519`, `ecdsa`, `rsa-sha2-256`) serta kompatibel penuh dengan *security provider* bawaan Android tanpa konflik BouncyCastle.
3. **Panel Terminal SSH (Opsional / Fase 2): `termux-terminal-view`**
   * **Artefak:** `com.termux.termux-app:terminal-view` & `terminal-emulator`
   * **Alasan:** Menyediakan komponen UI terminal ANSI penuh yang dapat dihubungkan langsung ke `ChannelShell` JSch (berfungsi sebagai terminal SSH remote di bagian bawah editor).

---

## 4. Spesifikasi Fungsional Utama

### A. Connection Manager (Manajemen Profil Server)
* Pengguna dapat menambah, mengedit, menghapus, dan menduplikasi profil koneksi SFTP.
* **Parameter Konfigurasi:** Nama Profil, Host/IP, Port (default `22`), Username, Metode Autentikasi (*Password* atau *SSH Private Key* `.pem`/`id_ed25519` + opsional *passphrase*), dan *Default Remote Path* (misal: `/var/www/project`).
* Fitur **Test Connection** sebelum profil disimpan.

### B. Remote File Explorer (Sidebar Kiri)
* **Lazy Directory Loading:** Aplikasi hanya memanggil `channelSftp.ls(path)` ketika pengguna mengklik atau mengekspansi folder tertentu agar hemat *bandwidth* dan tidak membekukan antarmuka.
* **Indikator Visual:** Ikon khusus berdasarkan ekstensi file (`.go`, `.py`, `.rs`, `.kt`, `.js`, `.ts`, `.html`, `.css`, `.json`, `.sql`, `.sh`, dll.).
* **Context Menu (Klik Kanan Mouse / Long Press):**
  * *New File* & *New Folder* (langsung membuat di server via SFTP)
  * *Rename* & *Delete* (dengan dialog konfirmasi untuk hapus)
  * *Refresh Directory* & *Copy Remote Path*

### C. Multi-Tab Editor & SFTP Sync Engine (Auto-Upload on Save)
* **Multi-Tab Bar:** Mendukung pembukaan banyak file sekaligus. Tab yang memiliki perubahan belum tersimpan wajib menampilkan indikator titik (`•`) di samping nama file.
* **Mekanisme Sinkronisasi Cache & Server:**
  1. Saat file remote dibuka, unduh file ke direktori cache lokal terisolasi: `context.cacheDir/sftp_workspaces/<server_id>/<remote_path>`.
  2. Muat konten file ke `CodeEditor` dan terapkan *syntax highlighting* otomatis sesuai ekstensi file.
  3. Simpan pemetaan struktur data `localCachePath <-> remoteServerPath` di memori.
  4. Saat pengguna menekan `Ctrl + S` (atau ikon *Save*):
     * Tulis perubahan ke file cache lokal terlebih dahulu (sebagai *backup* aman jika jaringan gagal).
     * Jalankan *coroutine* latar belakang untuk mengunggah file ke server menggunakan `channelSftp.put(localInputStream, remotePath, ChannelSftp.OVERWRITE)`.
  5. **Status Bar Bawah:** Menampilkan status *real-time* (contoh: `Connected: user@host`, bahasa file aktif, posisi baris/kolom kursor, serta status sinkronisasi `Syncing...` → `Uploaded ✓` atau peringatan merah jika *upload* gagal).

### D. Koneksi Persisten & Auto-Reconnect
* Atur `session.setServerAliveInterval(15000)` (ping *keep-alive* setiap 15 detik) dan `session.setServerAliveCountMax(3)`.
* Bungkus setiap perintah SFTP (`ls`, `get`, `put`, `mkdir`, `rm`, `rename`) dengan fungsi `ensureSessionConnected()`. Jika koneksi terputus akibat layar redup atau perpindahan jaringan, aplikasi otomatis melakukan *reconnect* di latar belakang sebelum mengeksekusi perintah sehingga pekerjaan pengguna tidak hilang.

---

## 5. Standar Optimasi Khusus Samsung DeX (Desktop UX)

1. **Multi-Window & Dynamic Resizing:**
   * Tambahkan `android:resizeableActivity="true"` pada `AndroidManifest.xml`.
   * Gunakan tata letak **Split-Pane** (Sidebar File Tree di kiri dengan lebar yang dapat digeser/*draggable divider*, Editor utama di kanan, dan Panel Terminal/Log yang dapat dibuka-tutup di bagian bawah).
2. **Pintasan Keyboard Fisik (`dispatchKeyEvent`):**
   * `Ctrl + S` : Simpan lokal & *Auto-Upload* ke server SFTP
   * `Ctrl + B` : Tampilkan / sembunyikan *Sidebar File Explorer*
   * `Ctrl + W` : Tutup tab editor yang sedang aktif
   * `Ctrl + P` : *Quick File Search* (pencarian cepat nama file pada direktori proyek yang telah dimuat)
   * `Ctrl + F` : *Find & Replace* di dalam editor aktif
   * `Ctrl + \`` : Buka / tutup panel terminal SSH di bagian bawah
3. **Dukungan Penuh Input Mouse & Trackpad:**
   * Tangkap event klik kanan mouse (`MotionEvent.BUTTON_SECONDARY`) untuk memunculkan menu konteks pada *File Tree* maupun area teks editor (*Cut, Copy, Paste, Select All*).
   * Pastikan *scroll wheel* vertikal dan horizontal (menggunakan tombol `Shift + Scroll`) berjalan mulus di seluruh panel.

---

## 6. Tahapan Pengembangan (Milestones)

* **Milestone 1 — Core Editor & DeX Layout:**
  Setup proyek Kotlin, implementasi tata letak *split-pane* responsif, integrasi `sora-editor` dengan tema gelap VS Code + *TextMate grammars*, manajemen *multi-tab*, dan pintasan keyboard dasar.
* **Milestone 2 — SFTP Engine & Sync Workflow:**
  Integrasi `mwiede/jsch`, pembuatan *Connection Manager* terenkripsi, *lazy loading* direktori SFTP, mekanisme *download-to-cache*, dan fitur *Auto-Upload on Save* (`Ctrl + S`) lengkap dengan logika *auto-reconnect*.
* **Milestone 3 — Polishing & Integrated SSH Terminal:**
  Penyempurnaan dukungan klik kanan mouse, indikator status sinkronisasi pada *status bar*, penanganan *error* jaringan, serta integrasi panel terminal SSH di bagian bawah editor.

---

## 7. Kriteria Penerimaan (Acceptance Criteria)
1. Aplikasi berjalan stabil dalam mode jendela bebas (*freeform window*) maupun layar penuh di Samsung DeX tanpa distorsi tata letak.
2. Proses simpan dan unggah file kode (< 500 KB) melalui SFTP selesai dalam waktu < 1 detik pada koneksi internet normal, tanpa menyebabkan *UI freeze*.
3. Tidak ada kredensial server atau *private key* yang disimpan dalam bentuk *plain text* di penyimpanan perangkat.
4. Jika koneksi internet terputus sementara saat mengedit, perubahan tetap tersimpan aman di cache lokal dan aplikasi berhasil melakukan *auto-reconnect* serta mengunggah ulang saat tombol simpan ditekan kembali.

---

## 8. Build & Rilis Otomatis

* **Unduh APK terbaru:** <https://github.com/1cdx/1cdx/releases/latest/download/1cdx.apk>
* Workflow `.github/workflows/release.yml` berjalan setiap push ke `main` (perubahan yang hanya menyentuh `*.md`/`LICENSE` diabaikan):
  1. Menentukan versi berikutnya dari tag `vX.Y.Z` terakhir (default naik *patch*; tulis `[minor]` atau `[major]` di pesan commit untuk naik level lain, `[skip release]` untuk melewati rilis).
  2. Build `assembleRelease` dengan `versionName = X.Y.Z` dan `versionCode = X*1000000 + Y*1000 + Z`.
  3. Menandatangani APK dengan keystore dari GitHub Secrets, lalu membuat tag + GitHub Release berisi `1cdx.apk`, `1cdx-vX.Y.Z.apk`, dan `SHA256SUMS.txt`.
* **Secret yang dibutuhkan** (Settings → Secrets and variables → Actions): `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`; serta **variable** `ANDROID_KEY_ALIAS`.
* Keystore **harus selalu sama** antar rilis; jika hilang/berganti, pengguna tidak bisa meng-*update* aplikasi tanpa uninstall.

* Branch selain `main` dan pull request menjalankan `.github/workflows/build.yml`: unit test + build debug & release **tanpa** membuat rilis. APK debug (`io.github.onecdx.app.debug`, bisa terpasang berdampingan dengan versi rilis) tersedia sebagai *artifact* di halaman run Actions.

Build lokal (butuh Android SDK, JDK 17+):

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Aset grammar/tema TextMate di `app/src/main/assets/textmate/` dibuat ulang dengan:

```bash
python3 tools/fetch_textmate.py app/src/main/assets/textmate
```

---

## 9. Status Implementasi

### Milestone 1 — selesai (v0.2.0)
* `sora-editor` + tema VS Code Dark+ + grammar TextMate: Go, Python, Rust, Kotlin, Java, JavaScript, TypeScript, HTML, CSS, JSON, SQL, Shell, Markdown, XML.
* Workspace folder lokal lewat Storage Access Framework (folder terakhir dibuka ulang otomatis), explorer dengan *lazy loading* per folder, ikon per ekstensi, menu klik kanan (Buka / Refresh / Salin path).
* Multi-tab: riwayat undo, kursor, dan posisi scroll per tab tetap terjaga; indikator `●` untuk perubahan belum disimpan; konfirmasi saat menutup tab atau keluar.
* Layout split-pane: sidebar dan panel Output bawah bisa di-resize dengan drag (ukuran diingat), status bar (folder, status simpan, Ln/Col, bahasa).
* Shortcut: `Ctrl+S` simpan, `Ctrl+B` sidebar, `Ctrl+W` tutup tab, `Ctrl+P` quick open, `Ctrl+F` cari, `Ctrl+H` ganti, ``Ctrl+` `` panel, `Ctrl+O` buka folder, `Ctrl+Tab` / `Ctrl+Shift+Tab` / `Ctrl+PgUp/PgDn` pindah tab, `Esc` tutup pencarian.
* Workspace lokal tetap tersedia; SFTP (Milestone 2) memakai kontrak `WorkspaceRepository` yang sama.

### Milestone 2 — SFTP Engine & Sync Workflow
* **Connection Manager** (`Ctrl+Shift+O`, ikon server di explorer): tambah, edit, duplikat, hapus profil; parameter nama, host, port, username, password atau SSH private key (`id_ed25519`, `id_rsa`, `.pem`, format OpenSSH/PEM) + passphrase, dan default remote path (kosong/`~` = home). Tombol **Test Connection** sebelum menyimpan.
* **Kredensial terenkripsi**: seluruh profil (password, private key, passphrase, host key) disimpan di satu file AES-256-GCM dengan kunci di Android Keystore. Tidak ada kredensial plain text; `allowBackup=false`.
* **Verifikasi host key (trust on first use)**: fingerprint `SHA256:` ditampilkan saat pertama kali terhubung dan harus dikonfirmasi; host key yang berubah selalu ditolak dengan peringatan MITM.
* **Remote explorer**: *lazy loading* per folder (`ls` hanya saat folder dibuka), symlink diikuti, menu klik kanan: File baru, Folder baru, Ganti nama, Hapus (konfirmasi, rekursif tanpa mengikuti symlink), Refresh, Salin path remote. Operasi yang sama juga berlaku untuk folder lokal.
* **Download-to-cache & Auto-Upload on Save**: file diunduh ke `cacheDir/sftp_workspaces/<server_id>/<remote_path>`; `Ctrl+S` menulis ke cache dulu lalu upload dengan `put(..., OVERWRITE)`. Status bar: `⇅ Connected: user@host`, `⟳ Syncing…` → `Uploaded ✓`, atau peringatan merah bila upload gagal.
* **Offline aman**: bila upload gagal, file ditandai *pending* dan versi cache tidak tertimpa unduhan berikutnya (tetap bisa dibuka tanpa koneksi); tab tetap bertanda `●` dan `Ctrl+S` berikutnya mengunggah ulang.
* **Auto-reconnect**: keep-alive 15 detik × 3; setiap perintah SFTP melewati `ensureConnected()` dan diulang sekali setelah sambung ulang bila koneksi putus (layar redup, pindah jaringan).
* Workspace SFTP terakhir disambungkan ulang otomatis saat aplikasi dibuka.
* Diuji end-to-end di CI terhadap server SSH sungguhan (Apache MINA SSHD): host key baru/berubah, password salah, key ed25519 OpenSSH (polos & ber-passphrase), RSA PEM ber-passphrase, operasi file, upload gagal → sinkron setelah server kembali, dan reconnect setelah koneksi putus. Algoritma JSch dipaksa ke jalur BouncyCastle seperti di Android.
