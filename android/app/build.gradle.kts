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
        versionCode = 10
        // Keep in step with internal/version/version.go. MAJOR == PROTOCOL:
        // a client can only talk to a server with the same PROTOCOL.
        versionName = "1.8.0"
        buildConfigField("int", "PROTOCOL", "1")

        // Default take-back server (REST API + signaling). Overridable at
        // runtime via the in-app Settings screen — handy for pointing at a
        // local dev server (e.g. http://10.0.2.2:8081 from the emulator).
        buildConfigField("String", "BASE_URL", "\"https://takeback.chain-of-thought.org\"")
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
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Maintained prebuilt WebRTC for Android (org.webrtc.* API).
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // WebSocket + HTTP client for signaling and the REST API.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Markdown rendering for chat messages, and async image loading for thumbs.
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.coil-kt:coil:2.6.0")
}
