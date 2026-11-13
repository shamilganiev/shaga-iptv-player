// ShaGa IPTV Player for Android — TextUtil.kt
// Links: clean-up, check, playlist name (same rules as text.js in LG).
// Ссылки: очистка, проверка, имя плейлиста (правила те же, что в text.js у LG).
package com.shaga.player

object TextUtil {
    const val MAX_URL = 2048

    // Spaces are removed, https:// is added when there is no scheme, the scheme goes to lower case.
    // Пробелы убираются, без схемы добавляется https://, схема — в нижний регистр.
    fun normalizeUrl(raw: String?): String {
        var s = (raw ?: "").trim().replace(" ", "")
        if (s.isEmpty()) return ""
        val low = s.lowercase()
        if (!low.contains("://")) s = "https://$s"
        else if (low.startsWith("http://")) s = "http://" + s.substring(7)
        else if (low.startsWith("https://")) s = "https://" + s.substring(8)
        return s
    }

    // http(s) and a dot or a colon in the server name / http(s) и точка или двоеточие в имени сервера
    fun isValidUrl(s: String): Boolean {
        val low = s.lowercase()
        if (!low.startsWith("http://") && !low.startsWith("https://")) return false
        val rest = s.substring(s.indexOf("://") + 3)
        val sl = rest.indexOf('/')
        val host = if (sl >= 0) rest.substring(0, sl) else rest
        if (host.length < 3) return false
        return host.contains('.') || host.contains(':')
    }

    // "site · file name" when the file name is up to 24 characters; no login and password.
    // «сайт · имя файла», если имя файла не длиннее 24 символов; без логина и пароля.
    fun playlistName(url: String): String {
        var s = url
        val p = s.indexOf("://")
        if (p >= 0) s = s.substring(p + 3)
        val at = s.indexOf('@')
        var sl = s.indexOf('/')
        if (at >= 0 && (sl < 0 || at < sl)) s = s.substring(at + 1)
        val q = s.indexOf('?')
        if (q >= 0) s = s.substring(0, q)
        var host = s
        var file = ""
        sl = s.indexOf('/')
        if (sl >= 0) {
            host = s.substring(0, sl)
            file = s.substring(sl + 1).substringAfterLast('/')
        }
        return if (file.isNotEmpty() && file.length <= 24) "$host · $file" else host
    }
}
