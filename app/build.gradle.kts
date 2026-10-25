plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.overlingo.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.overlingo.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.8.1"
    }

    // Release signing key - only from environment variables (GitHub Actions puts the
    // keystore from the repository secrets there, see .github/workflows/build.yml).
    // Never commit the keystore or its passwords. Without them, assembleRelease
    // produces an unsigned APK; for local builds use assembleDebug as before.
    val releaseKeystore = System.getenv("OVERLINGO_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("OVERLINGO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OVERLINGO_KEY_ALIAS")
                keyPassword = System.getenv("OVERLINGO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // No R8 - the APK is for personal use, and ML Kit doesn't require shrinking.
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Text recognition - Google ML Kit (Latin script); the model is bundled in the APK and works offline
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // On-device translation without internet (models ~30 MB per language, downloaded on demand)
    // and detection of the source language
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json doesn't work in plain (JVM) tests - use the real library
    testImplementation("org.json:json:20240303")
}
