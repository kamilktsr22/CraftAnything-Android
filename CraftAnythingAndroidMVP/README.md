# Craft Anything Android Native — APK Analyzer MVP 0.1

Proyek sumber Android Studio + Rust JNI untuk membaca APK secara lokal di Android ARM64. **Ini adalah implementasi awal kode sumber, belum APK siap instal.** Proyek belum dikompilasi dan belum diuji pada ponsel di lingkungan pengembangan ini.

Sumber analisis asli: [storytold/craft-anything](https://github.com/storytold/craft-anything) (`craft-anything-formats`, commit `56aa31976b4ff0950e6b34ba020b0a568d2462fa`). Lisensi upstream: MIT OR Apache-2.0. Lihat `THIRD_PARTY_NOTICES.md`.

## Fitur yang sudah diimplementasikan di kode

- Memilih APK dari penyimpanan lewat Android Storage Access Framework.
- Memeriksa `AndroidManifest.xml`, identitas aplikasi, versi, SDK, dan permission.
- Menampilkan komponen (Activity/Service/Receiver/Provider) dan status `exported` menurut parser manifest.
- Membaca ringkasan DEX (jumlah kelas, method, field, checksum) dan cuplikan nama kelas.
- Menginventarisasi library `.so` per ABI dan menampilkan peringatan/hasil terbatas.
- Analisis dilakukan secara offline melalui library Rust `libcraft_android.so` di dalam aplikasi Android; file diproses di cache pribadi kemudian dihapus.

Tidak termasuk pada MVP: dekompilasi Java/JADX, Ghidra, Frida, kontrol aplikasi lain, pemeriksaan tanda tangan, pembuatan APK baru, AI aktif, atau cloud build aktif.

## Persyaratan build di komputer

- Android Studio dan JDK 17.
- Android SDK Platform 35, Android NDK (misalnya `27.2.12479018`).
- Gradle **8.9** (cocok dengan AGP 8.7.3). Wrapper JAR tidak disertakan; lihat langkah 3.
- Rust **1.97 atau lebih tinggi**, rustup, dan target `aarch64-linux-android`.
- `cargo-ndk` (untuk membuat `.so` ARM64).
- Internet saat setup pertama untuk mendapatkan dependency Git `craft-anything-formats`, Maven, dan crates.io.

### 1. Instal Rust & target Android

```bash
rustup update stable
rustup target add aarch64-linux-android
cargo install cargo-ndk
```

Pastikan Android NDK sudah dipasang dari **Android Studio > SDK Manager > SDK Tools > NDK (Side by side)**. Jika NDK tidak terdeteksi, tentukan `ANDROID_NDK_HOME` ke direktori versi NDK di SDK lokal.

### 2. Build library native Rust

Dari folder proyek:

```bash
bash scripts/build-rust.sh
```

Atau secara manual (terminal di `rust-core`):

```bash
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
```

File yang dihasilkan:

```text
app/src/main/jniLibs/arm64-v8a/libcraft_android.so
```

**Penting:** NDK, Cargo, dan Rust berjalan saat membangun aplikasi **di komputer**; pengguna aplikasi akhir tidak perlu memasang Termux, Rust, maupun NDK di ponselnya.

### 3. Build APK Android

Buka direktori root proyek di Android Studio, pilih JDK 17 dan konfigurasi Gradle 8.9. Jika Gradle 8.9 telah terpasang di komputer dan ingin membuat wrapper standar:

```bash
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug
```

Di Windows gunakan `gradlew.bat :app:assembleDebug`, dan ganti `bash scripts/build-rust.sh` dengan perintah `cargo ndk ...` pada langkah 2 di terminal PowerShell (dijalankan dari `rust-core`).

Apabila berhasil, APK debug berada di:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Jika `.so` belum dibangun, task `verifyNativeLibrary` sengaja menggagalkan Gradle agar APK tidak diluncurkan tanpa mesin Rust.

### 4. Menggunakan aplikasi

Instal APK debug pada Android **ARM64**, Android **8.0 (API 26) atau yang lebih baru**. Buka aplikasi, tekan **Pilih berkas APK**, lalu pilih `.apk` dari pengelola berkas. Hasil muncul langsung di aplikasi. Tidak perlu root atau izin internet.

## Pengujian

Lakukan pemeriksaan mandiri setelah mengunduh dependensi:

```bash
cd rust-core
cargo test
cargo check
```

Uji di perangkat dengan beberapa APK yang sah, APK multi-DEX, file rusak, arsip ZIP non-APK, APK terenkripsi, berkas besar, dan nama file non-ASCII. Fitur network tidak dapat diuji karena belum ada implementasi network pada MVP.

## Status dan keterbatasan

- **Belum terverifikasi lewat kompilasi:** mesin di sesi penulisan ini tidak menyediakan Rust/Cargo, Android SDK/NDK, atau Gradle yang diperlukan.
- Hanya ABI `arm64-v8a`; emulator x86_64 memerlukan build kedua.
- Ukuran APK maksimal 128 MiB, dekompresi tiap anggota 32 MiB, maksimal 16 DEX, pratinjau 120 class.
- Hasil awal hanya analisis **statis**; tidak bisa melihat UI runtime atau data privat aplikasi lain.
- Ekspor file dan pagination belum ada; output UI dibatasi.
- Bila target berisi APK terproteksi/terobfuskasi atau split APK, pemeriksaan bisa parsial.
- AI dan cloud build hanyalah kontrak interface yang belum memiliki endpoint, credential, atau layanan aktif.
- Hasil parsing bukan klaim keamanan/keaslian APK. Jangan menginstal APK tak tepercaya hanya karena parser membacanya.

Lihat `docs/ARCHITECTURE.md` untuk diagram sistem dan rencana fase selanjutnya.

## Alternatif build lewat GitHub Actions (tanpa instal toolchain di HP)

Workflow `.github/workflows/android-debug.yml` adalah **rancangan pipeline** yang dapat membangun ARM64 `.so`, menjalankan unit test Rust, mengompilasi APK debug, lalu mengunggah APK sebagai artifact CI.

1. Buat repository GitHub milik Anda, unggah seluruh isi direktori proyek ini.
2. Buka tab **Actions** dan pilih **Android ARM64 Debug APK**.
3. Jalankan **Run workflow** pada branch `main` (atau dorong commit ke `main`).
4. Bila job sukses, buka halaman eksekusi dan unduh artifact `CraftAnythingAndroid-arm64-debug`.

**Belum dijalankan/terverifikasi di GitHub Actions.** Jika ada kesalahan kompilasi, log CI akan menunjukkan dependensi, konfigurasi, atau kode mana yang perlu diperbaiki. APK ini debug-signed dan tidak cocok langsung untuk Play Store.
