// ShaGa IPTV Player for Android — Strings.kt
// UI strings EN/ES/RU, same keys in every table. Wording is taken from the LG and Roku versions.
// Строки интерфейса EN/ES/RU, во всех таблицах одинаковые ключи. Формулировки — из версий LG и Roku.
package com.shaga.player

object S {
    // The tables go first: the fields below are built from them.
    // Таблицы идут первыми: поля ниже строятся из них.
    private val TABLES: Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            "welcomeTitle" to "Welcome to ShaGa IPTV Player",
            "welcomeText" to "A free player for your own M3U and M3U8 playlists. The app does not include any channels: add a link to a playlist you are allowed to use.",
            "welcomeBtn" to "Add a playlist",
            "urlTitle" to "Playlist link",
            "editTitle" to "Change link",
            "urlMsg" to "Enter the full link to your M3U/M3U8 playlist. If you omit https://, it is added automatically.",
            "urlPlaceholder" to "https://example.com/playlist.m3u",
            "ok" to "OK",
            "cancel" to "Cancel",
            "invalidUrl" to "This does not look like a web link. Example: https://example.com/playlist.m3u",
            "loading" to "Loading the channel list…",
            "errTitle" to "Playlist not loaded",
            "errNetwork" to "Could not connect to the server. Check the link and your internet connection.",
            "errHttp" to "The server returned error {code}. Check the link.",
            "errTimeout" to "The server did not respond in time.",
            "errEmpty" to "This link does not contain an M3U playlist, or the playlist has no channels.",
            "editLink" to "Change link",
            "channels" to "Channels: {n}",
            "noGroup" to "Other",
            "buffering" to "Loading…",
            "playError" to "The channel does not start.",
            "playTimeout" to "The channel did not start in time.",
            "hintBackList" to "Back: to channels"
        ),
        "es" to mapOf(
            "welcomeTitle" to "Bienvenido a ShaGa IPTV Player",
            "welcomeText" to "Un reproductor gratuito para tus propias listas M3U y M3U8. La aplicación no incluye canales: añade el enlace de una lista que tengas derecho a usar.",
            "welcomeBtn" to "Añadir una lista",
            "urlTitle" to "Enlace de la lista",
            "editTitle" to "Cambiar enlace",
            "urlMsg" to "Escribe el enlace completo de tu lista M3U/M3U8. Si omites https://, se añade automáticamente.",
            "urlPlaceholder" to "https://example.com/playlist.m3u",
            "ok" to "Aceptar",
            "cancel" to "Cancelar",
            "invalidUrl" to "Esto no parece un enlace web. Ejemplo: https://example.com/playlist.m3u",
            "loading" to "Cargando la lista de canales…",
            "errTitle" to "No se pudo cargar la lista",
            "errNetwork" to "No se pudo conectar con el servidor. Revisa el enlace y tu conexión a internet.",
            "errHttp" to "El servidor devolvió el error {code}. Revisa el enlace.",
            "errTimeout" to "El servidor no respondió a tiempo.",
            "errEmpty" to "Este enlace no contiene una lista M3U o la lista no tiene canales.",
            "editLink" to "Cambiar enlace",
            "channels" to "Canales: {n}",
            "noGroup" to "Otros",
            "buffering" to "Cargando…",
            "playError" to "El canal no se inicia.",
            "playTimeout" to "El canal no se inició a tiempo.",
            "hintBackList" to "Atrás: a los canales"
        ),
        "ru" to mapOf(
            "welcomeTitle" to "Добро пожаловать в ShaGa IPTV Player",
            "welcomeText" to "Бесплатный плеер для ваших собственных плейлистов M3U и M3U8. В приложении нет каналов: добавьте ссылку на плейлист, которым вы вправе пользоваться.",
            "welcomeBtn" to "Добавить плейлист",
            "urlTitle" to "Ссылка на плейлист",
            "editTitle" to "Изменить ссылку",
            "urlMsg" to "Введите полную ссылку на плейлист M3U/M3U8. Если не написать https://, он добавится сам.",
            "urlPlaceholder" to "https://example.com/playlist.m3u",
            "ok" to "OK",
            "cancel" to "Отмена",
            "invalidUrl" to "Это не похоже на веб-ссылку. Пример: https://example.com/playlist.m3u",
            "loading" to "Загружаю список каналов…",
            "errTitle" to "Плейлист не загрузился",
            "errNetwork" to "Не удалось подключиться к серверу. Проверьте ссылку и подключение к интернету.",
            "errHttp" to "Сервер вернул ошибку {code}. Проверьте ссылку.",
            "errTimeout" to "Сервер не ответил вовремя.",
            "errEmpty" to "По этой ссылке нет M3U-плейлиста или в плейлисте нет каналов.",
            "editLink" to "Изменить ссылку",
            "channels" to "Каналов: {n}",
            "noGroup" to "Прочее",
            "buffering" to "Загрузка…",
            "playError" to "Канал не запускается.",
            "playTimeout" to "Канал не запустился вовремя.",
            "hintBackList" to "Назад: к каналам"
        )
    )

    private val EN: Map<String, String> = TABLES.getValue("en")
    private var table: Map<String, String> = EN

    var lang = "en"
        private set

    // System language when we have it, else English / Язык системы, если он у нас есть, иначе английский.
    fun init(systemLang: String) {
        lang = if (TABLES.containsKey(systemLang)) systemLang else "en"
        table = TABLES[lang] ?: EN
    }

    fun t(key: String): String = table[key] ?: EN[key] ?: key
}
