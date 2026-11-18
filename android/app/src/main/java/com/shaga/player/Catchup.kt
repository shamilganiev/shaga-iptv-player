// ShaGa IPTV Player for Android — Catchup.kt
// Archive (catchup) links, one to one with Roku (source/catchup.brs) and LG (js/catchup.js).
// Ссылки на архив (catchup), один к одному с Roku и LG.
// Types / Типы:
//   shift      link + ?utc=START&lutc=NOW / ссылка + ?utc=НАЧАЛО&lutc=СЕЙЧАС
//   append     link + catchup-source (with substitutions) / ссылка + catchup-source (с подстановками)
//   default    catchup-source is the whole link / catchup-source — вся ссылка
//   flussonic  .../name.m3u8 -> .../name-START-DURATION.m3u8 ; /mpegts -> /timeshift_abs-START.ts
//   xc         Xtream: /live/user/pass/ID.ext -> /timeshift/user/pass/MINUTES/YYYY-MM-DD:HH-MM/ID.ts
// Substitutions / Подстановки: {utc} {start} ${start} {utcend} {end} ${end} {duration} ${duration}
//   {lutc} {now} ${now} {timestamp} {offset} ${offset} {Y} {m} {d} {H} {M} {S}
// Time is in seconds UTC / Время — секунды UTC.
package com.shaga.player

import java.util.Calendar
import java.util.TimeZone

// The archive of a channel: type, days, template / Архив канала: тип, дни, шаблон
class ArchInfo(val t: String, val d: Int, val src: String)

object Catchup {
    // The archive setting of a playlist: t = auto (from the playlist) | off | shift | flussonic | xc.
    // Настройка архива плейлиста: t = auto (из плейлиста) | off | shift | flussonic | xc.
    val TYPES = listOf("auto", "shift", "flussonic", "xc", "off")

    fun info(ch: Channel, archT: String, archD: Int): ArchInfo? {
        if (archT == "off") return null
        if (archT == "auto") {
            if (ch.catchup.isEmpty()) return null
            return ArchInfo(normType(ch.catchup, ch.catchupSource), if (ch.catchupDays > 0) ch.catchupDays else 3, ch.catchupSource)
        }
        return ArchInfo(archT, if (archD > 0) archD else 3, "")
    }

    fun normType(type: String, src: String): String {
        val t = type.lowercase()
        if (t == "fs" || t.startsWith("flussonic")) return "flussonic"
        if (t == "xc" || t == "xtream") return "xc"
        if (t == "append" || t == "default" || t == "shift") return t
        return if (src.isNotEmpty()) "default" else "shift"
    }

    // Can a programme that started at s (seconds) be watched from the archive now?
    // Можно ли сейчас смотреть из архива передачу, начавшуюся в s (секунды)?
    fun covers(info: ArchInfo?, s: Long, now: Long): Boolean =
        info != null && s < now && s >= now - info.d * 86400L

    fun url(url: String, info: ArchInfo, start: Long, finish: Long, now: Long): String {
        val dur = maxOf(60L, finish - start)
        if (info.t == "append") return url + fill(info.src, start, finish, now)
        if (info.t == "default" && info.src.isNotEmpty()) return fill(info.src, start, finish, now)
        if (info.t == "flussonic") return flussonic(url, start, dur)
        if (info.t == "xc") {
            val x = xtream(url, start, dur)
            if (x.isNotEmpty()) return x
        }
        return url + (if (url.contains('?')) "&" else "?") + "utc=" + start + "&lutc=" + now
    }

    private fun p2(n: Int): String = (if (n < 10) "0" else "") + n

    private fun utc(sec: Long): Calendar {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = sec * 1000L
        return c
    }

    fun fill(tpl: String, start: Long, finish: Long, now: Long): String {
        val d = utc(start)
        // The order matters: ${start} before {start} / Порядок важен: ${start} раньше {start}
        val map = linkedMapOf(
            "\${start}" to start.toString(), "{utc}" to start.toString(), "{start}" to start.toString(),
            "\${end}" to finish.toString(), "{utcend}" to finish.toString(), "{end}" to finish.toString(),
            "\${duration}" to (finish - start).toString(), "{duration}" to (finish - start).toString(),
            "\${now}" to now.toString(), "{lutc}" to now.toString(), "{now}" to now.toString(), "{timestamp}" to now.toString(),
            "\${offset}" to (now - start).toString(), "{offset}" to (now - start).toString(),
            "{Y}" to d.get(Calendar.YEAR).toString(), "{m}" to p2(d.get(Calendar.MONTH) + 1), "{d}" to p2(d.get(Calendar.DAY_OF_MONTH)),
            "{H}" to p2(d.get(Calendar.HOUR_OF_DAY)), "{M}" to p2(d.get(Calendar.MINUTE)), "{S}" to p2(d.get(Calendar.SECOND))
        )
        var s = tpl
        for ((k, v) in map) s = s.replace(k, v)
        return s
    }

    fun flussonic(url: String, start: Long, dur: Long): String {
        val qi = url.indexOf('?')
        val base = if (qi >= 0) url.substring(0, qi) else url
        val q = if (qi >= 0) url.substring(qi) else ""
        if (base.endsWith("/mpegts")) return base.dropLast(7) + "/timeshift_abs-" + start + ".ts" + q
        if (base.lowercase().endsWith(".m3u8")) return base.dropLast(5) + "-" + start + "-" + dur + ".m3u8" + q
        return base + "/index-" + start + "-" + dur + ".m3u8" + q
    }

    // Xtream timeshift (the start is written in UTC) / Xtream timeshift (начало пишется в UTC)
    fun xtream(url: String, start: Long, dur: Long): String {
        val m = Regex("^(https?://[^/]+)/(.*)$", RegexOption.IGNORE_CASE).find(url) ?: return ""
        val segs = m.groupValues[2].split('/').toMutableList()
        if (segs.isNotEmpty() && segs[0].lowercase() == "live") segs.removeAt(0)
        if (segs.size < 3) return ""
        val id = segs[2].substringBefore('.')
        val d = utc(start)
        val stamp = "" + d.get(Calendar.YEAR) + "-" + p2(d.get(Calendar.MONTH) + 1) + "-" + p2(d.get(Calendar.DAY_OF_MONTH)) + ":" +
            p2(d.get(Calendar.HOUR_OF_DAY)) + "-" + p2(d.get(Calendar.MINUTE))
        return m.groupValues[1] + "/timeshift/" + segs[0] + "/" + segs[1] + "/" + ((dur + 59) / 60) + "/" + stamp + "/" + id + ".ts"
    }
}
