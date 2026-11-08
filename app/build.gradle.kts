plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.craftandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.craftandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Fail early rather than producing an APK that crashes on its first analysis.
tasks.register("verifyNativeLibrary") {
    doLast {
        val nativeLib = file("src/main/jniLibs/arm64-v8a/libcraft_android.so")
        check(nativeLib.isFile) {
            "Library Rust belum tersedia. Jalankan scripts/build-rust.sh sebelum membangun APK."
        }
    }
}
tasks.named("preBuild").configure { dependsOn("verifyNativeLibrary") }
