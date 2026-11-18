// ShaGa IPTV Player for Android — Epg.kt
// The TV guide (XMLTV), SPEC.md section 9: the file is read as a stream of bytes, only finished
// <channel> and <programme> elements are parsed, the tail waits for the next piece. Kept: the
// channels of the playlist, the window "-24 h ... +24 h", a short description (200 characters)
// and the byte place of each programme in the file (the full description is asked by HTTP Range).
// gzip (by ".gz" or the bytes 1F 8B) and the encoding from <?xml encoding="..."?> are supported.
// No Android code here.
// Программа передач (XMLTV), SPEC.md раздел 9: файл читается потоком байтов, разбираются только
// законченные <channel> и <programme>, хвост ждёт следующего куска. Хранится: каналы плейлиста,
// окно «−24 ч … +24 ч», короткое описание (200 символов) и байтовое место каждой передачи
// в файле (полное описание просим через HTTP Range). Поддержаны gzip (по «.gz» и байтам 1F 8B)
// и кодировка из <?xml encoding="..."?>. Кода Android здесь нет.
package com.shaga.player

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

// s, e = start and stop, ms UTC; d = short description; off = byte place in the file (-1 = none);
// cid = the channel id in the file.
// s, e = начало и конец, мс UTC; d = короткое описание; off = место в файле (-1 = нет);
// cid = id канала в файле.
class Prog(val s: Long, val e: Long, val t: String, val d: String, val off: Long, val cid: String)

class EpgStats {
    var bytes = 0L
    var epgChannels = 0
    var byId = 0
    var byName = 0
    var progs = 0
    var kept = 0
    var noChan = 0
    var badTime = 0
    var outWindow = 0
}

object EpgText {
    const val SHORT = 200

    // The key of a channel: tvg-id, without it the normalized name.
    // Ключ канала: tvg-id, а без него — нормализованное название.
    fun key(ch: Channel): String = if (ch.tvgId.isNotEmpty()) ch.tvgId else "n:" + norm(ch.name)

    private val NOT_KEY = Regex("[^a-zа-я0-9+]")

    fun norm(s: String): String = s.lowercase().replace('ё', 'е').replace(NOT_KEY, "")

    // The first real http(s) link of url-tvg; "only4;iptvx.one" and the like are not links.
    // Первая настоящая http(s)-ссылка из url-tvg; «only4;iptvx.one» и т. п. ссылками не считаются.
    fun pickUrl(raw: String): String {
        for (part in raw.split(Regex("[,;\\s]+"))) {
            val low = part.lowercase()
            if (low.startsWith("http://") || low.startsWith("https://")) return part
        }
        return ""
    }

    private val TIME = Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})?\\s*([+-])?(\\d{2})?(\\d{2})?")

    // "20261107030000 +0300" -> ms UTC; 0 = not parsed / 0 = не разобрано
    fun parseTime(s: String): Long {
        val m = TIME.find(s.trim()) ?: return 0L
        val g = m.groupValues
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), if (g[6].isEmpty()) 0 else g[6].toInt())
        var ms = c.timeInMillis
        if (g[7].isNotEmpty() && g[8].isNotEmpty()) {
            val off = (g[8].toInt() * 60 + (if (g[9].isEmpty()) 0 else g[9].toInt())) * 60_000L
            ms -= if (g[7] == "-") -off else off
        }
        return ms
    }

    private val CDATA = Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>")
    private val HEX = Regex("&#x([0-9a-fA-F]+);")
    private val DEC = Regex("&#(\\d+);")

    fun decode(s: String): String {
        var r = s
        if (r.contains("<![CDATA[")) r = r.replace(CDATA) { it.groupValues[1] }
        if (r.indexOf('&') >= 0) {
            r = r.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            r = r.replace(HEX) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
            r = r.replace(DEC) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            r = r.replace("&amp;", "&")
        }
        return r.trim()
    }

    // An attribute of a start tag, in double or single quotes / Атрибут тега в двойных или одинарных кавычках
    fun attr(tag: String, name: String): String {
        var i = 0
        while (true) {
            i = tag.indexOf(name, i)
            if (i < 0) return ""
            val before = if (i == 0) ' ' else tag[i - 1]
            var j = i + name.length
            if (before.isWhitespace() || before == '<') {
                while (j < tag.length && tag[j].isWhitespace()) j++
                if (j < tag.length && tag[j] == '=') {
                    j++
                    while (j < tag.length && tag[j].isWhitespace()) j++
                    if (j < tag.length && (tag[j] == '"' || tag[j] == '\'')) {
                        val q = tag[j]
                        val end = tag.indexOf(q, j + 1)
                        if (end > j) return tag.substring(j + 1, end)
                    }
                }
            }
            i += name.length
        }
    }

    // The text of the first <name>...</name> / Текст первого <name>...</name>
    fun inner(body: String, name: String, from: Int = 0): String? {
        var i = body.indexOf("<$name", from)
        while (i >= 0) {
            val c = body.getOrNull(i + name.length + 1)
            if (c == '>' || c == ' ' || c == '\t' || c == '\n' || c == '\r') break
            i = body.indexOf("<$name", i + 1)
        }
        if (i < 0) return null
        val open = body.indexOf('>', i)
        if (open < 0) return null
        if (body[open - 1] == '/') return ""
        val close = body.indexOf("</$name>", open)
        if (close < 0) return null
        return body.substring(open + 1, close)
    }

    fun short(d: String): String = if (d.length > SHORT) d.substring(0, SHORT - 3) + "..." else d
}

// The parser of one file. feed() takes the bytes as they come, finish() parses the tail.
// Разборщик одного файла. feed() принимает байты по мере поступления, finish() разбирает хвост.
class EpgParser(channels: List<Channel>, nowMs: Long, private val withPlaces: Boolean) {
    private val from = nowMs - 24 * 3600_000L
    private val to = nowMs + 24 * 3600_000L
    private val wantId = HashMap<String, String>()     // tvg-id -> key / ключ
    private val nameKey = HashMap<String, String>()    // norm(name) -> key / ключ
    private val alias = HashMap<String, String>()      // id in the file -> key / id в файле -> ключ
    private val cidPool = HashMap<String, String>()
    val byKey = HashMap<String, ArrayList<Prog>>()
    val stats = EpgStats()
    var charset: Charset = Charsets.UTF_8
        private set

    private var buf = ByteArray(1 shl 20)
    private var len = 0
    private var base = 0L                              // file place of buf[0] / место buf[0] в файле
    private var headerSeen = false

    init {
        for (ch in channels) {
            val k = EpgText.key(ch)
            if (ch.tvgId.isNotEmpty()) wantId[ch.tvgId] = k
            nameKey[EpgText.norm(ch.name)] = k
            if (ch.tvgName.isNotEmpty()) nameKey.putIfAbsent(EpgText.norm(ch.tvgName), k)
        }
    }

    fun feed(data: ByteArray, n: Int) {
        if (n <= 0) return
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        System.arraycopy(data, 0, buf, len, n)
        len += n
        stats.bytes += n
        if (!headerSeen && len >= 64) readHeader()
        process(false)
    }

    fun finish() {
        if (!headerSeen) readHeader()
        process(true)
        for (list in byKey.values) list.sortBy { it.s }
    }

    // A copy of what is parsed so far, sorted (shown while the file is still loading).
    // Копия того, что уже разобрано, по порядку (показывается, пока файл ещё грузится).
    fun snapshot(): Map<String, List<Prog>> {
        val out = HashMap<String, List<Prog>>(byKey.size * 2)
        for ((k, v) in byKey) out[k] = v.sortedBy { it.s }
        return out
    }

    // <?xml version="1.0" encoding="windows-1251"?>
    private fun readHeader() {
        headerSeen = true
        val head = String(buf, 0, minOf(len, 300), Charsets.ISO_8859_1)
        val m = Regex("encoding\\s*=\\s*[\"']([^\"']+)").find(head) ?: return
        try {
            charset = Charset.forName(m.groupValues[1].trim())
        } catch (e: Exception) {
            AppLog.w("EPG", "unknown encoding " + m.groupValues[1] + ", using UTF-8")
        }
    }

    private fun startsAt(i: Int, word: String): Boolean {
        if (i + word.length >= len) return false
        for (k in word.indices) if (buf[i + k] != word[k].code.toByte()) return false
        val c = buf[i + word.length].toInt()
        return c == ' '.code || c == '>'.code || c == '\t'.code || c == '\n'.code || c == '\r'.code || c == '/'.code
    }

    private fun indexOf(word: String, fromIndex: Int): Int {
        val first = word[0].code.toByte()
        var i = fromIndex
        val last = len - word.length
        while (i <= last) {
            if (buf[i] == first) {
                var k = 1
                while (k < word.length && buf[i + k] == word[k].code.toByte()) k++
                if (k == word.length) return i
            }
            i++
        }
        return -1
    }

    private fun process(final: Boolean) {
        var pos = 0
        val lt = '<'.code.toByte()
        while (pos < len) {
            var i = pos
            while (i < len && buf[i] != lt) i++
            if (i >= len) { pos = len; break }
            val isProg = startsAt(i + 1, "programme")
            val isChan = !isProg && startsAt(i + 1, "channel")
            if (!isProg && !isChan) {
                // Not enough bytes to tell what this tag is: wait for more
                // Байтов не хватает, чтобы понять, что это за тег: ждём ещё
                if (!final && len - i < 12) { pos = i; break }
                pos = i + 1
                continue
            }
            val closeTag = if (isProg) "</programme>" else "</channel>"
            var end = indexOf(closeTag, i)
            if (end >= 0) end += closeTag.length
            if (end < 0 && isChan) {
                // <channel id="x"/> without children / без вложенных элементов
                val gt = indexOf(">", i)
                if (gt > 0 && buf[gt - 1] == '/'.code.toByte()) end = gt + 1
            }
            if (end < 0) {
                if (final) { pos = len; break }
                pos = i
                break
            }
            val text = String(buf, i, end - i, charset)
            if (isProg) handleProg(text, base + i) else handleChannel(text)
            pos = end
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
            base += pos
        }
    }

    private fun handleChannel(text: String) {
        val gt = text.indexOf('>')
        val id = EpgText.attr(text.substring(0, maxOf(0, gt)), "id")
        if (id.isEmpty()) return
        stats.epgChannels++
        val k = wantId[id]
        if (k != null) {
            alias[id] = k
            stats.byId++
            return
        }
        var from = 0
        while (true) {
            val i = text.indexOf("<display-name", from)
            if (i < 0) return
            val open = text.indexOf('>', i)
            val close = text.indexOf("</display-name>", open)
            if (open < 0 || close < 0) return
            val nk = nameKey[EpgText.norm(EpgText.decode(text.substring(open + 1, close)))]
            if (nk != null) {
                alias[id] = nk
                stats.byName++
                return
            }
            from = close + 15
        }
    }

    private fun handleProg(text: String, place: Long) {
        stats.progs++
        val gt = text.indexOf('>')
        val tag = text.substring(0, maxOf(0, gt))
        val cid = EpgText.attr(tag, "channel")
        val k = alias[cid] ?: wantId[cid]
        if (k == null) {
            stats.noChan++
            return
        }
        val s = EpgText.parseTime(EpgText.attr(tag, "start"))
        val e = EpgText.parseTime(EpgText.attr(tag, "stop"))
        if (s == 0L || e == 0L) {
            if (stats.badTime++ == 0) AppLog.w("EPG", "time not parsed: start=\"" + EpgText.attr(tag, "start") + "\"")
            return
        }
        if (e <= from || s >= to) {
            stats.outWindow++
            return
        }
        val body = text.substring(maxOf(0, gt))
        val title = EpgText.decode(EpgText.inner(body, "title") ?: "")
        val desc = EpgText.short(EpgText.decode(EpgText.inner(body, "desc") ?: ""))
        val c = cidPool.getOrPut(cid) { cid }
        byKey.getOrPut(k) { ArrayList() }.add(Prog(s, e, title, desc, if (withPlaces) place else -1L, c))
        stats.kept++
    }
}

class EpgResult(val byKey: Map<String, List<Prog>>, val stats: EpgStats, val gz: Boolean, val charset: String, val size: Long)

// The TV guide in memory, its copy on the device and the network.
// Программа передач в памяти, её копия на устройстве и сеть.
object Epg {
    const val REFRESH_MS = 12 * 3600_000L      // update in the background / обновление в фоне
    const val MAX_AGE_MS = 24 * 3600_000L      // an older copy is not shown / старше не показывается
    private const val TIMEOUT_MS = 30 * 60_000L
    private const val PART_BYTES = 20L * 1024 * 1024
    private const val COPY_VERSION = 1

    @Volatile var byKey: Map<String, List<Prog>> = emptyMap()
    var url = ""
    var loadedAt = 0L
    var gz = false
    var charset = "UTF-8"
    var ranges = true                         // the server gives pieces of the file / сервер отдаёт куски файла
    var channelsWith = 0

    fun clear() {
        byKey = emptyMap(); url = ""; loadedAt = 0L; gz = false; charset = "UTF-8"; ranges = true; channelsWith = 0
    }

    fun list(ch: Channel): List<Prog> = byKey[EpgText.key(ch)] ?: emptyList()

    // The index of the programme on air, or -1 / Номер передачи в эфире или -1
    fun nowIndex(list: List<Prog>, now: Long): Int {
        var lo = 0
        var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = list[mid]
            when {
                now < p.s -> hi = mid - 1
                now >= p.e -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    // The programme on air or the nearest one to now / Передача в эфире или ближайшая к «сейчас»
    fun nearIndex(list: List<Prog>, now: Long): Int {
        if (list.isEmpty()) return -1
        val i = nowIndex(list, now)
        if (i >= 0) return i
        val next = list.indexOfFirst { it.s > now }
        return if (next >= 0) next else list.size - 1
    }

    fun now(ch: Channel, nowMs: Long): Prog? {
        val list = list(ch)
        val i = nowIndex(list, nowMs)
        return if (i >= 0) list[i] else null
    }

    fun next(ch: Channel, n: Int, nowMs: Long): List<Prog> {
        val list = list(ch)
        val out = ArrayList<Prog>()
        for (p in list) {
            if (p.s >= nowMs) {
                out.add(p)
                if (out.size >= n) break
            }
        }
        return out
    }

    fun progress(p: Prog, nowMs: Long): Float =
        if (p.e <= p.s) 0f else ((nowMs - p.s).toFloat() / (p.e - p.s)).coerceIn(0f, 1f)

    fun set(r: EpgResult, from: String, at: Long) {
        byKey = r.byKey
        url = from
        loadedAt = at
        gz = r.gz
        charset = r.charset
        channelsWith = r.byKey.size
        ranges = !r.gz
    }

    // ---------- copy on the device / копия на устройстве ----------

    fun save(f: File) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(FileOutputStream(tmp).buffered(1 shl 16)).use { out ->
            out.writeInt(COPY_VERSION)
            out.writeUTF(url); out.writeLong(loadedAt); out.writeBoolean(gz); out.writeUTF(charset)
            val map = byKey
            out.writeInt(map.size)
            for ((k, list) in map) {
                out.writeUTF(k)
                out.writeInt(list.size)
                for (p in list) {
                    out.writeLong(p.s); out.writeLong(p.e)
                    out.writeUTF(p.t.take(4000)); out.writeUTF(p.d)
                    out.writeLong(p.off); out.writeUTF(p.cid)
                }
            }
        }
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    // The copy, if it is for this link / Копия, если она для этой ссылки
    fun load(f: File, wantUrl: String): Boolean {
        if (!f.exists()) return false
        DataInputStream(FileInputStream(f).buffered(1 shl 16)).use { inp ->
            if (inp.readInt() != COPY_VERSION) return false
            val u = inp.readUTF()
            if (u != wantUrl) return false
            val at = inp.readLong(); val z = inp.readBoolean(); val cs = inp.readUTF()
            val n = inp.readInt()
            val map = HashMap<String, List<Prog>>(n * 2)
            val pool = HashMap<String, String>()
            repeat(n) {
                val k = inp.readUTF()
                val m = inp.readInt()
                val list = ArrayList<Prog>(m)
                repeat(m) {
                    val s = inp.readLong(); val e = inp.readLong()
                    val t = inp.readUTF(); val d = inp.readUTF()
                    val off = inp.readLong(); val cid = inp.readUTF()
                    list.add(Prog(s, e, t, d, off, pool.getOrPut(cid) { cid }))
                }
                map[k] = list
            }
            byKey = map; url = u; loadedAt = at; gz = z; charset = cs; channelsWith = map.size; ranges = !z
        }
        return true
    }

    // ---------- network / сеть ----------

    private fun open(startUrl: String, range: String?): HttpURLConnection {
        var u = URL(startUrl)
        for (step in 0 until 6) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", Config.PLAYLIST_UA)
            // Byte places must be places in the file itself / Места в байтах — места в самом файле
            c.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in 301..308 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw LoadError("http", code)
                u = URL(u, loc)
                continue
            }
            return c
        }
        throw LoadError("network")
    }

    // Blocking: run it off the main thread. onPart gets what is parsed every 20 MB.
    // Блокирующий вызов: не в главном потоке. onPart получает разобранное каждые 20 МБ.
    fun download(link: String, channels: List<Channel>, onPart: (Map<String, List<Prog>>, Long) -> Unit, stop: () -> Boolean): EpgResult {
        val t0 = System.currentTimeMillis()
        val c = open(link, null)
        try {
            val code = c.responseCode
            AppLog.i("EPG", "server: HTTP " + code + ", type " + (c.contentType ?: "-") + ", size " +
                (c.getHeaderField("Content-Length") ?: "-") + ", encoding " + (c.contentEncoding ?: "-"))
            if (code !in 200..299) throw LoadError("http", code)
            val raw = BufferedInputStream(c.inputStream, 1 shl 16)
            raw.mark(4)
            val b1 = raw.read()
            val b2 = raw.read()
            raw.reset()
            val gzip = link.substringBefore('?').lowercase().endsWith(".gz") || (b1 == 0x1f && b2 == 0x8b)
            val input: InputStream = if (gzip) GZIPInputStream(raw, 1 shl 16) else raw
            val parser = EpgParser(channels, System.currentTimeMillis(), withPlaces = !gzip)
            val chunk = ByteArray(1 shl 18)
            var nextPart = PART_BYTES
            var first = true
            input.use {
                while (true) {
                    if (stop()) throw LoadError("stopped")
                    if (System.currentTimeMillis() - t0 > TIMEOUT_MS) throw LoadError("timeout")
                    val n = it.read(chunk)
                    if (n < 0) break
                    if (first && n > 0) {
                        first = false
                        AppLog.i("EPG", "file starts with: " + String(chunk, 0, minOf(n, 200), Charsets.ISO_8859_1)
                            .replace(Regex("\\s+"), " "))
                    }
                    parser.feed(chunk, n)
                    if (parser.stats.bytes >= nextPart) {
                        nextPart += PART_BYTES
                        AppLog.i("EPG", "loading: " + (parser.stats.bytes shr 20) + " MB, " +
                            (System.currentTimeMillis() - t0) / 1000 + " s, kept " + parser.stats.kept)
                        onPart(parser.snapshot(), parser.stats.bytes)
                    }
                }
            }
            parser.finish()
            val st = parser.stats
            AppLog.i("EPG", "done: " + (st.bytes shr 20) + " MB in " + (System.currentTimeMillis() - t0) + " ms" +
                (if (gzip) " (gzip)" else "") + ", " + parser.charset.name() + "; channels in file " + st.epgChannels +
                ", matched by id " + st.byId + " / by name " + st.byName + "; programmes " + st.progs + ", kept " + st.kept +
                "; dropped: no channel " + st.noChan + ", out of window " + st.outWindow + ", bad time " + st.badTime +
                "; channels with guide " + parser.byKey.size)
            return EpgResult(parser.byKey, st, gzip, parser.charset.name(), st.bytes)
        } finally {
            c.disconnect()
        }
    }

    // The full description: ~18 KB around the place of the programme by HTTP Range; the start,
    // the channel and the title are checked. null = could not (the short one stays).
    // Полное описание: ~18 КБ вокруг места передачи через HTTP Range; проверяются начало, канал
    // и название. null = не вышло (остаётся короткое).
    fun fullDesc(p: Prog): String? {
        if (gz || !ranges || p.off < 0 || url.isEmpty()) return null
        val a = maxOf(0L, p.off - 2048)
        val c = open(url, "bytes=" + a + "-" + (a + 18431))
        try {
            val code = c.responseCode
            if (code != 206) {
                if (code == 200) ranges = false
                AppLog.w("EPG", "description: HTTP $code, the server does not give pieces")
                return null
            }
            val bytes = c.inputStream.use { it.readBytes() }
            val cs = try { Charset.forName(charset) } catch (e: Exception) { Charsets.UTF_8 }
            val txt = String(bytes, cs)
            var i = txt.indexOf("<programme")
            while (i >= 0) {
                val end = txt.indexOf("</programme>", i)
                if (end < 0) break
                val el = txt.substring(i, end)
                val gt = el.indexOf('>')
                if (gt > 0) {
                    val tag = el.substring(0, gt)
                    if (EpgText.parseTime(EpgText.attr(tag, "start")) == p.s && EpgText.attr(tag, "channel") == p.cid) {
                        val body = el.substring(gt)
                        if (EpgText.decode(EpgText.inner(body, "title") ?: "") == p.t) {
                            return EpgText.decode(EpgText.inner(body, "desc") ?: "")
                        }
                    }
                }
                i = txt.indexOf("<programme", end)
            }
            AppLog.w("EPG", "description: the programme is not at its place (the file has changed)")
            return null
        } finally {
            c.disconnect()
        }
    }
}
