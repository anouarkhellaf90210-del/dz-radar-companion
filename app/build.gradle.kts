plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dzradar.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dzradar.companion"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "1.2.0"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    signingConfigs {
        create("releaseConfig") {
            storeFile = file("dz-radar-release.jks")
            storePassword = System.getenv("KEYSTORE_PASS") ?: "dzradar2026"
            keyAlias = System.getenv("KEY_ALIAS") ?: "dzradar"
            keyPassword = System.getenv("KEY_PASS") ?: "dzradar2026"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("releaseConfig")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("releaseConfig")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
