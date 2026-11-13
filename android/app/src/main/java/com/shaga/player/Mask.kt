// ShaGa IPTV Player for Android — Mask.kt
// Hides access keys in any text that goes to the log (SPEC.md, section 10).
// Прячет ключи доступа в любом тексте, который идёт в журнал (SPEC.md, раздел 10).
package com.shaga.player

object Mask {
    private val I = RegexOption.IGNORE_CASE

    // server + path of a link / сервер + путь ссылки
    private val URL = Regex("(https?://[^/\\s?#]+)(/[^\\s?#\"']*)", I)

    // secret query parameters / секретные параметры запроса
    private val SECRET = Regex(
        "([?&;](?:token|key|password|pass|pwd|auth|sid|session|signature|sig|username|user|login|mac)=)[^&\\s\"']+", I
    )

    // login:password@server / логин:пароль@сервер
    private val USER_INFO = Regex("(//)[^/\\s:@]+:[^/\\s@]+@")

    // Xtream Codes: /live/login/password/123.ts
    private val XTREAM = Regex("^/(live|movie|series|timeshift|play)/[^/]+/[^/]+/", I)

    private val SEGMENT = Regex("[^/]+")
    private val LETTER = Regex("[A-Za-z]")
    private val DIGIT = Regex("[0-9]")

    fun mask(s: String): String =
        s.replace(URL) { m -> m.groupValues[1] + maskPath(m.groupValues[2]) }
            .replace(SECRET) { m -> m.groupValues[1] + "***" }
            .replace(USER_INFO) { m -> m.groupValues[1] + "***@" }

    // A path segment that looks like a random key (8+ characters, both letters and digits,
    // no dot) becomes ***; so does a 24+ character segment without a dot (hash).
    // Channel numbers (12101) and file names (index.m3u8) stay.
    // Сегмент пути, похожий на случайный ключ (8+ символов, есть и буквы, и цифры, нет точки),
    // заменяется на ***; так же сегмент 24+ символов без точки (хеш).
    // Номера каналов (12101) и имена файлов (index.m3u8) остаются.
    fun maskPath(path: String): String {
        val p = path.replace(XTREAM) { m -> "/" + m.groupValues[1] + "/***/***/" }
        return p.replace(SEGMENT) { m ->
            val seg = m.value
            val noDot = !seg.contains('.')
            val key = seg.length >= 8 && noDot && LETTER.containsMatchIn(seg) && DIGIT.containsMatchIn(seg)
            val long = seg.length >= 24 && noDot
            if (key || long) "***" else seg
        }
    }

    // Short link for the log: server + path without keys, parameters are dropped.
    // Короткая ссылка для журнала: сервер + путь без ключей, параметры отбрасываются.
    fun url(u: String): String {
        val m = Regex("^(https?://[^/?#]+)([^?#]*)", I).find(u) ?: return mask(u)
        val host = m.groupValues[1].replace(USER_INFO) { x -> x.groupValues[1] + "***@" }
        return host + maskPath(m.groupValues[2]) + if (u.contains('?')) "?…" else ""
    }
}
