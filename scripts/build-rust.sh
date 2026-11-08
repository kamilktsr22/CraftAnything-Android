#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v cargo >/dev/null 2>&1; then
  echo 'Rust/Cargo belum terpasang. Pasang rustup dan Rust >= 1.97.' >&2; exit 1
fi
if ! cargo ndk --help >/dev/null 2>&1; then
  echo 'cargo-ndk belum terpasang. Jalankan: cargo install cargo-ndk' >&2; exit 1
fi
mkdir -p "$ROOT/app/src/main/jniLibs"
cd "$ROOT/rust-core"
cargo ndk -t arm64-v8a -o "$ROOT/app/src/main/jniLibs" build --release
SO="$ROOT/app/src/main/jniLibs/arm64-v8a/libcraft_android.so"
if [[ ! -s "$SO" ]]; then
  echo "Library native tidak ditemukan: $SO" >&2; exit 1
fi
echo "OK: $SO"
