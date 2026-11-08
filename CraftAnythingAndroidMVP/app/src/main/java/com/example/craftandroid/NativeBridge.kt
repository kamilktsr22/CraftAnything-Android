package com.example.craftandroid

/** JNI entry point implemented by rust-core/src/lib.rs. Runs in-process, not in Termux. */
object NativeBridge {
    init {
        System.loadLibrary("craft_android")
    }

    external fun analyzeApk(absoluteCachePath: String): String
}
