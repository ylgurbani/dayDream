plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.yattubhaa.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yattubhaa.app"
        // Covers the grandfather's older Motorola (Android 14) and the helper's S23 Ultra.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // 10.0.2.2 is the emulator's name for the computer running the relay.
            buildConfigField("String", "DEFAULT_RELAY_URL", "\"ws://10.0.2.2:8787\"")
        }
        release {
            // No default: the helper enters their own deployed (wss://) relay in Helper mode.
            buildConfigField("String", "DEFAULT_RELAY_URL", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Key agreement + authenticated encryption for the pairing/session channel,
    // via a well-audited library instead of hand-rolled crypto.
    implementation("com.google.crypto.tink:tink-android:1.15.0")

    // Transport to the relay (WebSocket over TLS).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR generation + scanning for the one-time pairing step.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
