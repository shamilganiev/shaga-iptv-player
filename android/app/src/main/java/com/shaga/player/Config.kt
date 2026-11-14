// ShaGa IPTV Player for Android — Config.kt
// App settings in one place / Настройки приложения в одном месте.
package com.shaga.player

object Config {
    const val APP_NAME = "ShaGa IPTV Player"

    // Playlist download: whole timeout and size limit.
    // Загрузка плейлиста: общий тайм-аут и предел размера.
    const val LOAD_TIMEOUT_MS = 30_000
    const val MAX_PLAYLIST_BYTES = 64 * 1024 * 1024

    // User-Agent for downloading playlists (the same as in the Roku version): some
    // providers send a reduced playlist to unknown clients.
    // User-Agent для загрузки плейлистов (как в Roku-версии): некоторые провайдеры
    // отдают незнакомым клиентам урезанный плейлист.
    const val PLAYLIST_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Channel start timeout / Тайм-аут старта канала.
    const val START_TIMEOUT_MS = 45_000L

    // Loading sign appears only after this buffering time.
    // Значок загрузки появляется только после такой буферизации.
    const val BUFFER_SIGN_MS = 700L

    // Channel info bar hides after / Плашка канала скрывается через.
    const val INFO_HIDE_MS = 4_000L

    // Row heights of the lists, dp / Высота строк списков, dp
    const val ROW_GROUP_DP = 44
    const val ROW_CHANNEL_DP = 60

    // The link dialog ignores OK for this time after it opens: one press of "Done" could
    // work in the closing dialog and in the new one.
    // Окно ссылки не принимает OK в течение этого времени после открытия: одно нажатие
    // «Готово» могло сработать и в закрывающемся окне, и в новом.
    const val DIALOG_GUARD_MS = 700L

    // Log size in memory, records / Размер журнала в памяти, записей.
    const val LOG_MAX = 1500
}
