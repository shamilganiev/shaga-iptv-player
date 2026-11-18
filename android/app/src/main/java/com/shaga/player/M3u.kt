// ShaGa IPTV Player for Android — M3u.kt
// M3U/M3U8 playlist parser, same rules as in Roku and LG (SPEC.md, section 4).
// Разбор плейлистов M3U/M3U8, правила те же, что в Roku и LG (SPEC.md, раздел 4).
//
// #EXTM3U url-tvg="..."
// #EXTINF:-1 tvg-id="..." tvg-logo="..." group-title="Кино",Название
// #EXTVLCOPT:http-user-agent=...
// http://server/123/index.m3u8
package com.shaga.player

class Channel(
    val n: Int,              // number in the playlist, from 1 / номер в плейлисте, с 1
    val name: String,
    val group: String,
    val logo: String,
    val tvgId: String,
    val tvgName: String,
    val url: String,
    val userAgent: String,   // from #EXTVLCOPT:http-user-agent, may be empty / может быть пустым
    val referrer: String,    // from #EXTVLCOPT:http-referrer, may be empty / может быть пустым
    // Archive attributes of the channel (or of the #EXTM3U header): type, days, link template.
    // Атрибуты архива канала (или заголовка #EXTM3U): тип, дни, шаблон ссылки.
    val catchup: String = "",
    val catchupDays: Int = 0,
    val catchupSource: String = ""
)

// catchup / catchup-type, catchup-source / catchup-template, catchup-days / tvg-rec / timeshift;
// days without a type mean shift (as in Roku and LG).
// Дни без типа означают shift (как в Roku и LG).
class CatchupAttr(val t: String, val d: Int, val src: String)

class Playlist(
    val channels: List<Channel>,
    val epgUrl: String,      // url-tvg / x-tvg-url from the header / из заголовка
    val groupCount: Int
)

object M3u {
    // key="value" or key='value' / атрибуты в двойных и одинарных кавычках
    private val ATTR = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    fun parseAttrs(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in ATTR.findAll(s)) {
            val v = m.groups[2]?.value ?: m.groups[3]?.value ?: ""
            out[m.groupValues[1].lowercase()] = v
        }
        return out
    }

    // The title goes after the first comma OUTSIDE quotes: attributes may contain commas too.
    // Название идёт после первой запятой ВНЕ кавычек: в атрибутах тоже бывают запятые.
    fun splitInfo(s: String): Pair<String, String> {
        var quote = ' '          // ' ' = outside quotes / вне кавычек
        for (i in s.indices) {
            val c = s[i]
            if (quote == ' ') {
                if (c == '"' || c == '\'') quote = c
                else if (c == ',') return Pair(s.substring(0, i), s.substring(i + 1))
            } else if (c == quote) {
                quote = ' '
            }
        }
        return Pair(s, "")
    }

    fun catchupOf(a: Map<String, String>): CatchupAttr? {
        var t = (a["catchup"] ?: a["catchup-type"] ?: "").lowercase()
        val src = a["catchup-source"] ?: a["catchup-template"] ?: ""
        val d = (a["catchup-days"] ?: a["tvg-rec"] ?: a["timeshift"] ?: "").trim().toIntOrNull() ?: 0
        if (t.isEmpty() && d > 0) t = "shift"
        if (t.isEmpty()) return null
        return CatchupAttr(t, if (d > 0) d else 3, src)
    }

    fun parse(text: String): Playlist {
        val channels = ArrayList<Channel>()
        val groups = HashSet<String>()
        var epg = ""
        var hdrCatch: CatchupAttr? = null
        var cu: CatchupAttr? = null

        // Data of the channel being read / Данные канала, который сейчас читается
        var has = false
        var name = ""; var group = ""; var logo = ""; var tvgId = ""; var tvgName = ""
        var extGrp = ""; var ua = ""; var ref = ""

        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTM3U", ignoreCase = true)) {
                val h = parseAttrs(line)
                epg = h["url-tvg"] ?: h["x-tvg-url"] ?: ""
                hdrCatch = catchupOf(h)
                continue
            }
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val parts = splitInfo(line.substring(8))
                val a = parseAttrs(parts.first)
                has = true
                name = parts.second.trim()
                group = (a["group-title"] ?: "").trim()
                logo = a["tvg-logo"] ?: ""
                tvgId = a["tvg-id"] ?: ""
                tvgName = a["tvg-name"] ?: ""
                cu = catchupOf(a) ?: hdrCatch
                extGrp = ""; ua = ""; ref = ""
                continue
            }
            if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                extGrp = line.substring(8).trim()
                continue
            }
            if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val opt = line.substring(11)
                val eq = opt.indexOf('=')
                if (eq > 0) {
                    val k = opt.substring(0, eq).trim().lowercase()
                    val v = opt.substring(eq + 1).trim()
                    if (k == "http-user-agent") ua = v
                    else if (k == "http-referrer" || k == "http-referer") ref = v
                }
                continue
            }
            if (line[0] == '#') continue          // other tags / прочие теги

            // A line with the stream link / Строка со ссылкой на поток
            if (SCHEME.containsMatchIn(line)) {
                val n = channels.size + 1
                var g = if (has) group else ""
                if (g.isEmpty() && extGrp.isNotEmpty()) g = extGrp
                var title = if (has) name else ""
                if (title.isEmpty()) title = "Channel $n"
                channels.add(
                    Channel(
                        n = n, name = title, group = g,
                        logo = if (has) logo else "",
                        tvgId = if (has) tvgId else "",
                        tvgName = if (has) tvgName else "",
                        url = line, userAgent = ua, referrer = ref,
                        catchup = if (has) cu?.t ?: "" else "",
                        catchupDays = if (has) cu?.d ?: 0 else 0,
                        catchupSource = if (has) cu?.src ?: "" else ""
                    )
                )
                groups.add(g)
            }
            has = false
            extGrp = ""; ua = ""; ref = ""
        }
        return Playlist(channels, epg, groups.size)
    }
}
