import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.pabich.buzzer"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.pabich.buzzer"
        minSdk = 24
        targetSdk = 35
        // Build-Nummer kommt aus der GitHub Action (Lauf-Nummer); lokal 1.
        val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Fester Schlüssel, damit jeder Build als Update über den vorherigen installiert werden kann
    // (sonst gingen Config und Profile verloren). Nur für Test-/Hobby-Builds gedacht.
    signingConfigs {
        getByName("debug") {
            storeFile = file("buzzer-debug.keystore")
            storePassword = "buzzer"
            keyAlias = "buzzer"
            keyPassword = "buzzer"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Die Fragenpakete aus dem Repository (quiz/) werden als Assets eingebaut.
    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file("quiz"))
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("de.pabich.buzzer:core:1.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    // QR-Code für den Download-Link (nur der Kern, keine Kamera)
    implementation("com.google.zxing:core:3.5.3")
}
