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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
            // Native addon .so files from bare-link + bare-kit runtime
            jniLibs.srcDirs("src/main/addons", "libs/bare-kit/jni")
        }
    }
}

dependencies {
    // bare-kit Java classes (downloaded from GitHub releases)
    api(files("libs/bare-kit/classes.jar"))
}
