// ShaGa IPTV Player for Android — TextUtil.kt
// Links: clean-up, check, playlist name; channel search (same rules as text.js in LG).
// Ссылки: очистка, проверка, имя плейлиста; поиск каналов (правила те же, что в text.js у LG).
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

    // ---------- search / поиск (SPEC.md, section 5; the same rules as text.js in LG) ----------

    // Lower case, ё -> е, repeated spaces collapsed / Нижний регистр, ё -> е, лишние пробелы убраны
    fun norm(s: String): String = s.lowercase().replace('ё', 'е').replace(SPACES, " ").trim()

    private val SPACES = Regex(" {2,}")

    private val LATIN: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g", 'ў' to "u",
        'ә' to "a", 'ө' to "o", 'ү' to "u", 'ғ' to "g", 'ҡ' to "k", 'ң' to "n", 'ҙ' to "z", 'ҫ' to "s", 'һ' to "h", 'җ' to "zh",
        'á' to "a", 'à' to "a", 'â' to "a", 'ä' to "a", 'ã' to "a", 'å' to "a",
        'é' to "e", 'è' to "e", 'ê' to "e", 'ë' to "e",
        'í' to "i", 'ì' to "i", 'î' to "i", 'ï' to "i",
        'ó' to "o", 'ò' to "o", 'ô' to "o", 'ö' to "o", 'õ' to "o",
        'ú' to "u", 'ù' to "u", 'û' to "u", 'ü' to "u",
        'ñ' to "n", 'ç' to "c"
    )

    // Variants of Latin spelling are merged: kh->h, ts->c, j->y, w->v, x->ks, q->k, ie->e, ye->e
    // Варианты латиницы сводятся к одному: kh->h, ts->c, j->y, w->v, x->ks, q->k, ie->e, ye->e
    private fun fuzzLatin(s: String): String =
        s.replace("kh", "h").replace("ts", "c").replace("j", "y").replace("w", "v")
            .replace("x", "ks").replace("q", "k").replace("ie", "e").replace("ye", "e")

    // norm(s) -> Latin letters -> merged variants / norm(s) -> латиница -> сведённые варианты
    fun fuzz(norm: String): String {
        val out = StringBuilder(norm.length + 8)
        for (ch in norm) {
            if (ch.code < 128) out.append(ch) else out.append(LATIN[ch] ?: ch.toString())
        }
        return fuzzLatin(out.toString())
    }

    // Search key of a channel: "normalized|latin" / Ключ поиска канала: «нормализованный|латиница»
    fun searchKey(title: String): String {
        val n = norm(title)
        return n + "|" + fuzz(n)
    }

    fun matches(key: String, qNorm: String, qFuzz: String): Boolean {
        if (qNorm.isEmpty()) return false
        if (key.contains(qNorm)) return true
        return qFuzz.isNotEmpty() && key.contains(qFuzz)
    }

    // Channels whose name contains the query / Каналы, в названии которых есть запрос
    fun search(channels: List<Channel>, query: String): List<Channel> {
        val qn = norm(query)
        val qf = fuzz(qn)
        return channels.filter { matches(searchKey(it.name), qn, qf) }
    }
}
