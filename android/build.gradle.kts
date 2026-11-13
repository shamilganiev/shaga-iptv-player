// ShaGa IPTV Player for Android — build.gradle.kts (project)
// Build plugin versions / Версии плагинов сборки.
// Android Gradle Plugin 8.11 needs Gradle 8.13 (see .github/workflows/android.yml).
// Android Gradle Plugin 8.11 требует Gradle 8.13 (см. .github/workflows/android.yml).
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
