//! Craft Anything Android MVP: native, read-only APK inspection.
//! Parser implementation: github.com/storytold/craft-anything, crates/formats.
//! Only parses a user-chosen file copied into this app's private cache.

use craft_anything_formats::{axml, dex, zip};
use serde_json::{Value, json};
use std::fs::File;
use std::io::Read;
use std::path::Path;

const MAX_APK_BYTES: u64 = 128 * 1024 * 1024;
const MAX_ZIP_ENTRIES: usize = 100_000;
const MAX_MEMBER_BYTES: u64 = 32 * 1024 * 1024;
const MAX_DEX_FILES: usize = 16;
const MAX_CLASS_PREVIEW: usize = 120;

/// Read a file with a hard bound (even if its metadata reports a different length).
pub fn analyze_file(path: &Path) -> Result<Value, String> {
    let metadata = path.metadata().map_err(|e| format!("Tidak bisa membaca metadata APK: {e}"))?;
    if !metadata.is_file() {
        return Err("Input harus berkas biasa.".to_string());
    }
    if metadata.len() > MAX_APK_BYTES {
        return Err("Ukuran APK melebihi batas MVP 128 MiB.".to_string());
    }
    let file = File::open(path).map_err(|e| format!("Tidak bisa membuka APK: {e}"))?;
    let mut data = Vec::with_capacity(usize::try_from(metadata.len()).unwrap_or(0));
    file.take(MAX_APK_BYTES + 1)
        .read_to_end(&mut data)
        .map_err(|e| format!("Gagal membaca APK: {e}"))?;
    if data.len() as u64 > MAX_APK_BYTES {
        return Err("Ukuran APK melebihi batas MVP 128 MiB.".to_string());
    }
    analyze_bytes(&data)
}

/// All parsing uses Craft Anything's bounded, pure-Rust ZIP/AXML/DEX parsers.
pub fn analyze_bytes(data: &[u8]) -> Result<Value, String> {
    if data.len() as u64 > MAX_APK_BYTES {
        return Err("Ukuran APK melebihi batas MVP 128 MiB.".to_string());
    }
    if !zip::looks_like_zip(data) {
        return Err("Berkas tidak memiliki header ZIP/APK yang dikenali.".to_string());
    }
    let archive = zip::parse(data, MAX_ZIP_ENTRIES)
        .map_err(|e| format!("Direktori ZIP/APK tidak valid: {e}"))?;
    let mut warnings: Vec<String> = Vec::new();
    if archive.truncated {
        warnings.push("Daftar entri ZIP dipotong pada 100.000 entri.".into());
    }

    let manifest_entry = archive.entries.iter().find(|e| e.name == "AndroidManifest.xml")
        .ok_or("AndroidManifest.xml tidak ada di root. Ini mungkin bukan APK lengkap.")?;
    let manifest_bytes = zip::read_entry(data, manifest_entry, MAX_MEMBER_BYTES)
        .map_err(|e| format!("Manifest tidak bisa dibaca: {e}"))?;
    let doc = axml::parse(&manifest_bytes)
        .map_err(|e| format!("AndroidManifest.xml biner tidak valid: {e}"))?;
    warnings.extend(doc.warnings.iter().map(|w| format!("Manifest: {w}")));
    if doc.truncated {
        warnings.push("Sebagian node manifest tidak terbaca/dibatasi oleh parser.".into());
    }
    let manifest = axml::manifest(&doc);

    let mut dex_files = Vec::new();
    let mut class_names = Vec::new();
    let mut dex_count = 0usize;
    for entry in archive.entries.iter().filter(|e| is_root_dex(&e.name)) {
        dex_count += 1;
        if dex_files.len() >= MAX_DEX_FILES {
            continue;
        }
        match zip::read_entry(data, entry, MAX_MEMBER_BYTES) {
            Ok(bytes) => match dex::parse(&bytes) {
                Ok(decoded) => {
                    for class in &decoded.classes {
                        if class_names.len() >= MAX_CLASS_PREVIEW {
                            break;
                        }
                        class_names.push(dex::java_name(&decoded.class_name(class)));
                    }
                    dex_files.push(json!({
                        "name": entry.name,
                        "byte_size": bytes.len(),
                        "classes": decoded.header.class_defs,
                        "methods": decoded.header.method_ids,
                        "fields": decoded.header.field_ids,
                        "checksum_valid": decoded.header.checksum_ok,
                    }));
                    warnings.extend(decoded.warnings.iter().take(10)
                        .map(|w| format!("{}: {w}", entry.name)));
                }
                Err(err) => warnings.push(format!("DEX {} gagal diurai: {err}", entry.name)),
            },
            Err(err) => warnings.push(format!("DEX {} tidak bisa dibaca: {err}", entry.name)),
        }
    }
    if dex_count > MAX_DEX_FILES {
        warnings.push(format!("Hanya {} dari {} DEX diperiksa.", MAX_DEX_FILES, dex_count));
    }

    let mut native_libraries: Vec<Value> = Vec::new();
    for entry in &archive.entries {
        let parts: Vec<_> = entry.name.split('/').collect();
        if let ["lib", abi, file] = parts.as_slice() {
            if file.ends_with(".so") && native_libraries.len() < 500 {
                native_libraries.push(json!({"abi": abi, "name": file, "bytes": entry.uncompressed_size}));
            }
        }
    }
    if native_libraries.len() == 500 {
        warnings.push("Daftar library native dibatasi sampai 500 entri.".into());
    }

    let components: Vec<Value> = manifest.components.iter().take(500).map(|component| json!({
        "kind": component.kind,
        "name": component.name,
        "exported": component.exported,
        "permission": component.permission,
    })).collect();
    if manifest.components.len() > 500 {
        warnings.push("Daftar komponen dibatasi hingga 500 entri.".into());
    }

    Ok(json!({
        "ok": true,
        "engine": "craft-anything-formats",
        "package_name": manifest.package,
        "version_name": manifest.version_name,
        "version_code": manifest.version_code,
        "min_sdk": manifest.min_sdk,
        "target_sdk": manifest.target_sdk,
        "file_bytes": data.len(),
        "entry_count": archive.entries.len(),
        "entries_truncated": archive.truncated,
        "permissions": manifest.uses_permissions,
        "components": components,
        "components_total": manifest.components.len(),
        "dex_count": dex_count,
        "dex_files": dex_files,
        "classes_preview": class_names,
        "native_libraries": native_libraries,
        "warnings": warnings,
        "limits": {
            "apk_max_mib": 128,
            "member_max_mib": 32,
            "max_dex": MAX_DEX_FILES,
            "class_preview_max": MAX_CLASS_PREVIEW
        }
    }))
}

fn is_root_dex(name: &str) -> bool {
    if !name.ends_with(".dex") || name.contains('/') { return false; }
    if name == "classes.dex" { return true; }
    let Some(digits) = name.strip_prefix("classes").and_then(|s| s.strip_suffix(".dex")) else {
        return false;
    };
    digits.parse::<u32>().is_ok_and(|number| number >= 2)
}

/// Android JNI symbol for: com.example.craftandroid.NativeBridge.analyzeApk(String).
/// The JVM's second argument is an object reference because NativeBridge is a Kotlin object.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_craftandroid_NativeBridge_analyzeApk(
    mut env: jni::JNIEnv<'_>,
    _this: jni::objects::JObject<'_>,
    path: jni::objects::JString<'_>,
) -> jni::sys::jstring {
    let result: Value = match env.get_string(&path) {
        Ok(path) => {
            let path = path.to_string_lossy().into_owned();
            match analyze_file(Path::new(&path)) {
                Ok(v) => v,
                Err(e) => json!({ "ok": false, "error": e }),
            }
        }
        Err(e) => json!({ "ok": false, "error": format!("JNI input invalid: {e}") }),
    };
    let serialized = result.to_string();
    match env.new_string(serialized) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_bytes_rejected() {
        assert!(analyze_bytes(&[]).is_err());
    }

    #[test]
    fn corrupt_zip_rejected() {
        assert!(analyze_bytes(b"PK\x03\x04junk").is_err());
    }

    #[test]
    fn root_dex_filter() {
        assert!(is_root_dex("classes.dex"));
        assert!(is_root_dex("classes2.dex"));
        assert!(!is_root_dex("assets/classes.dex"));
        assert!(!is_root_dex("classes.dex.backup"));
    }
}
