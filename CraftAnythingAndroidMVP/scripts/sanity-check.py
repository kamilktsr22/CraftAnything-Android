"""Offline structural checks; not a replacement for Cargo/Gradle compilation."""
from pathlib import Path
import sys
import tomllib
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
failures = []

def require(ok, message):
    if not ok:
        failures.append(message)

cargo = tomllib.loads((root / "rust-core/Cargo.toml").read_text())
require(cargo["lib"]["name"] == "craft_android", "native lib name mismatch")
require("craft-anything-formats" in cargo["dependencies"], "upstream parser dependency missing")
require(len(cargo["dependencies"]["craft-anything-formats"]["rev"]) == 40, "un-pinned upstream commit")
manifest = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot()
android_ns = "{http://schemas.android.com/apk/res/android}"
for elt in manifest.iter():
    if elt.tag.endswith("uses-permission"):
        failures.append(f"unexpected permission: {elt.attrib.get(android_ns+'name')}")

bridge = (root / "app/src/main/java/com/example/craftandroid/NativeBridge.kt").read_text()
rust = (root / "rust-core/src/lib.rs").read_text()
ui = (root / "app/src/main/java/com/example/craftandroid/MainActivity.kt").read_text()
require("external fun analyzeApk(absoluteCachePath: String): String" in bridge, "JNI Kotlin declaration missing")
require("Java_com_example_craftandroid_NativeBridge_analyzeApk" in rust, "JNI Rust symbol missing")
require("zip::parse(" in rust and "axml::parse(" in rust and "dex::parse(" in rust, "upstream parsing calls missing")
require("ActivityResultContracts.OpenDocument" in ui and "privateFile.delete()" in ui, "SAF or cleanup missing")
require("libcraft_android.so" in (root / "app/build.gradle.kts").read_text(), "Gradle .so guard missing")
for p in root.rglob("*.toml"):
    tomllib.loads(p.read_text())
for p in root.rglob("*.xml"):
    ET.parse(p)
if failures:
    print("FAILED")
    for f in failures:
        print(" -", f)
    sys.exit(1)
print("PASS: Cargo TOML, Android XML, JNI names, upstream calls, SAF/cleanup, Gradle SO guard.")
print("NOTE: No Android build, Rust compilation, or device test was performed.")
