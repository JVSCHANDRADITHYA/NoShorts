plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noshorts.blocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.noshorts.blocker"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal-use build: sign with the debug key so `assembleRelease`
            // produces something you can sideload directly. Swap in a real
            // keystore here if you ever want to distribute it.
            signingConfig = signingConfigs.getByName("debug")
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

// No third-party dependencies on purpose: plain framework widgets only, so the
// APK stays tiny and the build needs nothing but the Android SDK.
dependencies { }
