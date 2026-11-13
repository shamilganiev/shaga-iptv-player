// ShaGa IPTV Player for Android — build.gradle.kts (app)
// App build: version, Android levels, libraries / Сборка приложения: версия, уровни Android, библиотеки.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shaga.player"
    compileSdk = 36

    defaultConfig {
        // Cannot be changed after publishing / После публикации изменить нельзя.
        applicationId = "com.shaga.player"
        minSdk = 24        // Android 7.0
        targetSdk = 36     // Android 16
        // versionCode = MAJOR*10000 + MINOR*100 + PATCH  (0.1.0 -> 100)
        versionCode = 100
        versionName = "0.1.0"
    }

    // One fixed test key in the repository, so that every build from GitHub installs
    // over the previous one. Not secret; the Google Play key will be a different one.
    // Один постоянный тестовый ключ в репозитории, чтобы каждая сборка с GitHub
    // ставилась поверх предыдущей. Не секретный; ключ для Google Play будет другим.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Interface: Compose (foundation only, our own focus highlight)
    // Интерфейс: Compose (только foundation, своя подсветка фокуса)
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Player: Media3 (ExoPlayer) / Плеер: Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
