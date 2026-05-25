plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nospoon.vpn"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.nospoon.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // libhyperdht_jni.so is shipped only for arm64-v8a; restrict
            // the APK to that ABI so we don't end up with broken builds
            // on other ABIs.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    sourceSets {
        getByName("main") {
            // libhyperdht_jni.so goes in jniLibs/arm64-v8a/
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // Coroutines — required by the hyperdht-cpp Kotlin wrapper
    // (HyperDHT uses suspend fns + Channel/Flow for stream data).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Material Design 3 (includes RecyclerView)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // activity-ktx provides enableEdgeToEdge() and registerForActivityResult
    implementation("androidx.activity:activity-ktx:1.9.3")
    // SDK 31+ Splash Screen API with backwards-compat for older versions
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // QR code scanning — Google Code Scanner (standalone, includes camera UI).
    // Smaller than ZXing, async module download on first use, no runtime
    // camera permission needed (Play Services owns the prompt).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
