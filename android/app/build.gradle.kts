plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.takeback.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.takeback.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // Where the signaling server lives. Override for your deployment; use
        // wss:// when the server is behind TLS. 10.0.2.2 is the host machine as
        // seen from the Android emulator.
        buildConfigField("String", "SIGNAL_URL", "\"ws://10.0.2.2:8081/ws\"")
    }

    buildFeatures {
        viewBinding = true
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Maintained prebuilt WebRTC for Android (org.webrtc.* API).
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // WebSocket client for signaling.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
