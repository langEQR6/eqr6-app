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
        versionCode = 10
        versionName = "2.3"

        // Default OTA manifest location.
        // Served by serve-app.py (scheduled task EQR6-AppUpdateServer) on
        // port 8080, reachable from the LAN and over Tailscale.
        // It can be changed at runtime in the app, so moving to GitHub
        // later needs no rebuild.
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"http://192.168.3.11:8080/version.json\"")
    }

    // Release builds are signed with the project keystore so that OTA
    // upgrades are accepted by Android (same signature required).
    //
    // NOTE: a CI run once produced an UNSIGNED apk silently, because the
    // keystore lookup failed and the build just carried on. Now an expected
    // keystore that is missing is a hard build failure, and the workflow
    // verifies the signature before publishing.
    signingConfigs {
        create("release") {
            val envPath = System.getenv("EQR6_KEYSTORE")
            val ksPath = if (!envPath.isNullOrBlank()) envPath else "../keystore/eqr6-release.jks"
            val ksFile = file(ksPath)
            logger.lifecycle("signing: EQR6_KEYSTORE env = " + (envPath ?: "(unset)"))
            logger.lifecycle("signing: resolved keystore = ${ksFile.absolutePath} exists=${ksFile.exists()}")
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

            val envPath = System.getenv("EQR6_KEYSTORE")
            val ksPath = if (!envPath.isNullOrBlank()) envPath else "../keystore/eqr6-release.jks"
            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
                logger.lifecycle("signing: release build WILL be signed")
            } else if (!envPath.isNullOrBlank()) {
                throw GradleException(
                    "EQR6_KEYSTORE was set to '" + envPath +
                    "' but that file does not exist. Refusing to build an unsigned release APK."
                )
            } else {
                logger.lifecycle("signing: no keystore found - release build will be UNSIGNED")
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

// The ONLY third-party dependency in this project.
//
// It exists for one feature: opening the DSH web UI from the phone. DSH binds
// to 127.0.0.1 on the server by design (that is the security model), so the
// phone cannot reach it directly - it needs a local SSH tunnel, which is what
// sshj provides. Every other screen works without it.
dependencies {
    implementation("com.hierynomus:sshj:0.38.0")

    // sshj logs through slf4j. The no-op binding avoids a "no binding" warning
    // and adds almost nothing to the APK.
    implementation("org.slf4j:slf4j-nop:2.0.13")
}
