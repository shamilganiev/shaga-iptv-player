// ShaGa IPTV Player for Android — TextUtil.kt
// Links: clean-up, check, playlist name; search masks (the same rules as text.js in LG).
// Ссылки: очистка, проверка, имя плейлиста; маски поиска (правила те же, что в text.js у LG).
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

    // ---------- search / поиск (SPEC.md, section 5; the same builder as text.js in LG) ----------

    // Lower case, ё -> е, repeated spaces collapsed / Нижний регистр, ё -> е, лишние пробелы убраны
    fun norm(s: String): String = s.lowercase().replace('ё', 'е').replace(SPACES, " ").trim()

    private val SPACES = Regex(" {2,}")

    // Latin letter groups that stand for one Cyrillic letter / Сочетания латиницы для одной буквы кириллицы
    private val FIND_MULTI = listOf(
        "shch" to "щ", "sch" to "щ", "sh" to "ш", "ch" to "ч", "zh" to "ж", "kh" to "х", "ts" to "ц",
        "yu" to "ю", "ya" to "я", "yo" to "е", "ye" to "е", "ju" to "ю", "ja" to "я"
    )

    // One Latin letter and the Cyrillic letters it may stand for / Одна латинская буква и её кириллица
    private val FIND_ONE: Map<Char, List<String>> = mapOf(
        'a' to listOf("а"), 'b' to listOf("б"), 'v' to listOf("в"), 'w' to listOf("в"), 'g' to listOf("г"),
        'd' to listOf("д"), 'e' to listOf("е", "э"), 'z' to listOf("з"), 'i' to listOf("и", "й", "ы", "і"),
        'y' to listOf("й", "ы", "и"), 'j' to listOf("й", "ж"), 'k' to listOf("к"), 'l' to listOf("л"),
        'm' to listOf("м"), 'n' to listOf("н"), 'o' to listOf("о"), 'p' to listOf("п"), 'r' to listOf("р"),
        's' to listOf("с"), 't' to listOf("т"), 'u' to listOf("у"), 'f' to listOf("ф"), 'h' to listOf("х", "г"),
        'c' to listOf("ц", "к", "с"), 'x' to listOf("кс", "х"), 'q' to listOf("к")
    )

    private const val SOFT = "(?:ь|Ь|ъ|Ъ)?"

    // A non-Latin letter in both cases; е also matches ё / Не латинская буква в обоих регистрах; е — и ё
    private fun cyrAlt(ch: Char): String {
        if (ch == 'е') return "(?:е|Е|ё|Ё)"
        val u = ch.uppercaseChar()
        return if (u == ch) Regex.escape(ch.toString()) else "(?:$ch|$u)"
    }

    private fun latinOne(ch: Char): String {
        val sb = StringBuilder("(?:[").append(ch).append(ch.uppercaseChar()).append(']')
        for (alt in FIND_ONE[ch] ?: emptyList()) {
            sb.append('|')
            for (c in alt) sb.append(cyrAlt(c))
        }
        return sb.append(')').toString()
    }

    private const val ESCAPE = "\\.^$|()[]{}+-/"

    // Search mask -> regular expression over the original names; null = empty query.
    // * = any number of any characters (and none), ? = exactly one; * at both ends is implied.
    // Case does not matter, е = ё, Latin letters also find Cyrillic: "garri*potter".
    // Маска поиска -> регулярное выражение по исходным названиям; null = пустой запрос.
    // * = любое число любых символов (и ноль), ? = ровно один; * по краям подразумевается.
    // Регистр не важен, е = ё, латиница находит и кириллицу: «garri*potter».
    fun findRegex(query: String): Regex? {
        val q = norm(query).trimStart('*').trimEnd('*').trim()
        if (q.isEmpty()) return null
        val out = StringBuilder()
        var i = 0
        while (i < q.length) {
            val ch = q[i]
            when {
                ch == '*' -> { out.append("[\\s\\S]*"); i++ }
                ch == '?' -> { out.append("[\\s\\S]"); i++ }
                ch == ' ' -> { out.append("\\s+"); i++ }
                ch in 'a'..'z' -> {
                    val multi = FIND_MULTI.firstOrNull { q.startsWith(it.first, i) }
                    if (multi != null) {
                        out.append("(?:").append(cyrAlt(multi.second[0])).append('|')
                        for (c in multi.first) out.append(latinOne(c))
                        out.append(')').append(SOFT)
                        i += multi.first.length
                    } else {
                        out.append(latinOne(ch)).append(SOFT)
                        i++
                    }
                }
                ch.code < 128 -> {
                    if (ESCAPE.indexOf(ch) >= 0) out.append('\\')
                    out.append(ch)
                    i++
                }
                else -> { out.append(cyrAlt(ch)); i++ }
            }
        }
        return Regex(out.toString())
    }
}
