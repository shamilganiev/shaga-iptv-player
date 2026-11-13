// ShaGa IPTV Player for Android — settings.gradle.kts
// Where Gradle looks for plugins and libraries / Где Gradle ищет плагины и библиотеки.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "shaga-iptv-player"
include(":app")
