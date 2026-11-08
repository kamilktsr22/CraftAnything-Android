# Arsitektur teknis: Craft Anything Android Native

## Sasaran

APK native (bukan WebView/Termux) yang menjalankan fungsi statis parser Craft Anything langsung pada prosesor Android arm64. AI dan cloud build merupakan ekstensi **opsional** dan tidak aktif pada MVP.

```text
┌───────────────────────────────────────────────────┐
│ Android Activity: Jetpack Compose                 │
│ OpenDocument / ContentResolver                    │
│ [pilih APK] → copy terbatas ke private cache      │
└───────────────────────┬───────────────────────────┘
                        │ path absolut file milik aplikasi
                        ▼
┌───────────────────────────────────────────────────┐
│ JNI: NativeBridge.analyzeApk(String): String       │
└───────────────────────┬───────────────────────────┘
                        ▼
┌───────────────────────────────────────────────────┐
│ libcraft_android.so (Rust, arm64-v8a)              │
│  - bounded file reading (128 MiB)                  │
│  - ZIP central directory parser (100k entries)    │
│  - binary XML manifest parser                      │
│  - DEX parser (max 16 files, 32 MiB/member)        │
│  - library ABI inventory                           │
│  - JSON result                                    │
│                                                    │
│  DEPENDENSI ASLI: craft-anything-formats           │
│  https://github.com/storytold/craft-anything       │
│  pin: 56aa31976b4ff0950e6b34ba020b0a568d2462fa │
└───────────────────────┬───────────────────────────┘
                        ▼
           JSONObject → Compose report

Next stages (NOT enabled):
 [Report redaction + explicit consent]
             │
             ├── AI explanation gateway (HTTPS)
             └── Cloud build gateway (HTTPS + job status)
```

## Komponen yang berasal dari upstream

- `craft-anything-formats::zip::parse` dan `zip::read_entry`
- `craft-anything-formats::axml::parse` dan `axml::manifest`
- `craft-anything-formats::dex::parse`, `dex::java_name`

Kode ini digunakan melalui dependency Git terpin, bukan salinan parser yang ditulis ulang. `crates/engine`, `crates/ai`, `crates/platform`, `crates/ui-egui`, Ghidra, Frida, ADB, JADX dan Apktool **tidak** dibundel pada MVP.

## Keputusan keamanan

1. SAF / OpenDocument adalah satu-satunya jalur pemilihan berkas. Tidak memerlukan akses luas ke penyimpanan atau `QUERY_ALL_PACKAGES`.
2. Tidak ada permission `INTERNET`, `READ_EXTERNAL_STORAGE`, Accessibility, MediaProjection, atau root pada MVP.
3. UI menyalin input ke cache privat lalu menghapusnya melalui blok `finally`, termasuk ketika terjadi error.
4. Batas ukuran input 128 MiB (sebelum dan setelah copy); dekompresi entry maksimal 32 MiB; DEX maksimal 16 file. Arsip yang melampaui batas dapat menghasilkan hasil parsial dan peringatan.
5. Hasilnya **bukan audit keamanan**. Tidak ada pemeriksaan validitas tanda tangan digital, sumber APK, malware, alur runtime, atau perizinan efektif di luar data manifest.
6. Rust berjalan di proses aplikasi, belum di proses terisolasi. Parsers yang menerima input tidak tepercaya tetap perlu fuzzing/pengujian perangkat.
7. Kunci API AI tidak boleh di-hard-code. Integrasi jaringan berikutnya harus memakai HTTPS, proxy/server yang melakukan autentikasi, redaksi hasil, persetujuan pengiriman, dan kebijakan retensi.

## Rencana tahapan

**M0 (kode dalam arsip ini):** APK/DEX/manifest/permission/component/ABI inventory, Compose UI, JNI, validasi ukuran.

**M1:** perangkat Android ARM64 nyata, pengujian APK sah dan rusak, unit/instrumentation tests, memory profiling, progressive DEX paging, `classes_find` dan `class_show`.

**M2:** AI service dengan opt-in per pemanggilan, penghapusan secret/PII dari evidence, pencatatan asal temuan, kontrol biaya dan timeout, MCP adapter berskop read-only.

**M3:** proyek hasil craft yang dapat dibuat ulang, cloud worker sandbox Linux dengan Android SDK + Gradle, signed artifact delivery, job queue, audit log, dan batas resource.

**M4:** runtime inspection bersyarat perizinan Android, isolasi proses parser, dukungan 16 KiB memory pages, x86_64 emulator, dan penanganan APK >128 MiB.

## Kontrak API yang disarankan (belum tersedia)

- `POST /v1/ai/explain`: body berisi *redacted report* dan pertanyaan; response berisi jawaban dengan referensi bagian laporan.
- `POST /v1/builds`: unggah arsip proyek yang dimiliki/diizinkan pengguna; response `job_id` dan status awal.
- `GET /v1/builds/{job_id}`: status queued/running/failed/succeeded + URL artefak yang dibatasi masa berlaku.

Backend wajib mengautentikasi permintaan, menerapkan rate-limit, mengisolasi build, memindai dependensi dan artefak, serta membatasi akses jaringan saat build. Mengunggah APK target lengkap tidak termasuk desain default.

## Konvensi Native ABI

`com.example.craftandroid.NativeBridge.analyzeApk(String): String` -> `Java_com_example_craftandroid_NativeBridge_analyzeApk` -> JSON `{ok:true,...}` atau `{ok:false,error:"..."}`. Sangat sederhana untuk diuji dan diganti dengan typed DTO di iterasi berikutnya.
