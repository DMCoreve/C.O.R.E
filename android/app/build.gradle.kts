plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dmcore.core"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dmcore.core"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-skeleton"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // Para correr el modelo de wake word (openWakeWord) on-device. Ver
    // docs/ENTRENAR_WAKE_WORD.md — la integración real en CoreForegroundService.kt
    // se escribe cuando exista el modelo .onnx entrenado.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    testImplementation("junit:junit:4.13.2")
}
