plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eqr6.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eqr6.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"

        // Default OTA manifest location.
        // Served by serve-app.py (scheduled task EQR6-AppUpdateServer) on
        // port 8080, reachable from the LAN and over Tailscale.
        // It can be changed at runtime in the app, so moving to GitHub
        // later needs no rebuild.
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"http://192.168.3.11:8080/version.json\"")
    }

    // Release builds are signed with the project keystore so that OTA
    // upgrades are accepted by Android (same signature required).
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("EQR6_KEYSTORE") ?: "../keystore/eqr6-release.jks"
            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("EQR6_STORE_PASS") ?: "eqr6app2026"
                keyAlias = System.getenv("EQR6_KEY_ALIAS") ?: "eqr6"
                keyPassword = System.getenv("EQR6_KEY_PASS") ?: "eqr6app2026"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            if (file(System.getenv("EQR6_KEYSTORE") ?: "../keystore/eqr6-release.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Deliberately NO third-party dependencies: plain framework APIs only.
// This keeps the first build fast and removes dependency-resolution risk.
dependencies {
}
