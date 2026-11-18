// ShaGa IPTV Player for Android — Storage.kt
// Saved settings: one JSON file, the same scheme as the Roku registry and LG storage:
//   { v, lang, lastOkId, nextId, playlists[{id, name, url, arch{t, d}}], zapInvert, favorites[],
//     groupModes{"g:<group>": s|p|h}, adult{"g:<group>": true|false}, seenGroups[], pin,
//     last{name, group, cat} }
// Copies of playlists (the text of the M3U file) lie next to it: pl_<id>.m3u.
// Сохранённые настройки: один JSON-файл, схема та же, что в реестре Roku и хранилище LG.
// Копии плейлистов (текст M3U-файла) лежат рядом: pl_<id>.m3u.
package com.shaga.player

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// archT / archD: the archive setting of the playlist (Catchup.TYPES, days 1..7).
// archT / archD: настройка архива плейлиста (Catchup.TYPES, дни 1..7).
class SavedPlaylist(val id: Int, var name: String, var url: String, var archT: String = "auto", var archD: Int = 3)

object Storage {
    private const val FILE = "settings.json"
    private const val VERSION = 2
    const val MAX_PLAYLISTS = 5

    val playlists = ArrayList<SavedPlaylist>()
    var lastOkId = 0
    var nextId = 1

    // Channel switching in full screen: true = Up goes to the lower number, as in the list
    // (default); false = classic. The same name as in the LG version.
    // Переключение каналов на полном экране: true = вверх к меньшему номеру, как в списке
    // (по умолчанию); false = классическое. Имя то же, что в LG-версии.
    var zapInvert = true

    // Interface language: "" = automatic (system language) / Язык интерфейса: "" = автоматически
    var lang = ""

    // Favourites: channel names (access keys in links may change), common for all playlists.
    // Избранное: названия каналов (ключи в ссылках могут смениться), общее для всех плейлистов.
    val favorites = ArrayList<String>()

    // The last channel: name, group and the category it was started from ("" = none).
    // Последний канал: название, группа и категория, из которой его включили ("" = нет).
    var lastName = ""
    var lastGroup = ""
    var lastCat = ""

    fun load(ctx: Context) {
        playlists.clear(); lastOkId = 0; nextId = 1; zapInvert = true; lang = ""; favorites.clear()
        lastName = ""; lastGroup = ""; lastCat = ""
        Protection.clear()
        try {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            lastOkId = o.optInt("lastOkId", 0)
            nextId = o.optInt("nextId", 1)
            zapInvert = o.optBoolean("zapInvert", true)
            lang = o.optString("lang", "")
            val arr = o.optJSONArray("playlists") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val url = p.optString("url", "")
                if (url.isNotEmpty() && playlists.size < MAX_PLAYLISTS) {
                    val arch = p.optJSONObject("arch")
                    var t = arch?.optString("t", "auto") ?: "auto"
                    if (t !in Catchup.TYPES) t = "auto"
                    val d = (arch?.optInt("d", 3) ?: 3).coerceIn(1, 7)
                    playlists.add(SavedPlaylist(p.optInt("id", i + 1), p.optString("name", ""), url, t, d))
                }
            }
            for (p in playlists) if (p.id >= nextId) nextId = p.id + 1
            val fav = o.optJSONArray("favorites") ?: JSONArray()
            for (i in 0 until fav.length()) {
                val name = fav.optString(i, "")
                if (name.isNotEmpty() && favorites.size < Config.MAX_FAVORITES) favorites.add(name)
            }
            val modes = o.optJSONObject("groupModes")
            if (modes != null) {
                for (k in modes.keys()) {
                    val m = GroupMode.of(modes.optString(k))
                    if (k.startsWith("g:") && m != null) Protection.modes[k.substring(2)] = m
                }
            }
            val adult = o.optJSONObject("adult")
            if (adult != null) {
                for (k in adult.keys()) if (k.startsWith("g:")) Protection.adult[k.substring(2)] = adult.optBoolean(k)
            }
            val seen = o.optJSONArray("seenGroups") ?: JSONArray()
            for (i in 0 until seen.length()) Protection.seen.add(seen.optString(i, ""))
            Protection.pinHash = o.optString("pin", "")
            val last = o.optJSONObject("last")
            if (last != null) {
                lastName = last.optString("name", "")
                lastGroup = last.optString("group", "")
                lastCat = last.optString("cat", "")
            }
        } catch (e: Exception) {
            AppLog.e("ERR", "storage: cannot read settings: " + e.javaClass.simpleName)
        }
    }

    fun save(ctx: Context) {
        try {
            val arr = JSONArray()
            for (p in playlists) {
                arr.put(JSONObject().put("id", p.id).put("name", p.name).put("url", p.url)
                    .put("arch", JSONObject().put("t", p.archT).put("d", p.archD)))
            }
            val fav = JSONArray()
            for (name in favorites) fav.put(name)
            val modes = JSONObject()
            for ((g, m) in Protection.modes) modes.put("g:$g", m.code)
            val adult = JSONObject()
            for ((g, on) in Protection.adult) adult.put("g:$g", on)
            val seen = JSONArray()
            for (g in Protection.seen) seen.put(g)
            val o = JSONObject()
                .put("v", VERSION)
                .put("lang", lang)
                .put("lastOkId", lastOkId)
                .put("nextId", nextId)
                .put("playlists", arr)
                .put("zapInvert", zapInvert)
                .put("favorites", fav)
                .put("groupModes", modes)
                .put("adult", adult)
                .put("seenGroups", seen)
                .put("pin", Protection.pinHash)
                .put("last", JSONObject().put("name", lastName).put("group", lastGroup).put("cat", lastCat))
            writeSafely(File(ctx.filesDir, FILE), o.toString())
        } catch (e: Exception) {
            AppLog.e("ERR", "storage: cannot save settings: " + e.javaClass.simpleName)
        }
    }

    // Write to a temporary file first, so that a failure cannot spoil the old file.
    // Сначала пишем во временный файл, чтобы сбой не испортил старый файл.
    private fun writeSafely(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    // ---------- playlists / плейлисты ----------

    // The last successfully loaded playlist, else the first one.
    // Последний успешно загруженный плейлист, иначе первый.
    fun current(): SavedPlaylist? = playlists.firstOrNull { it.id == lastOkId } ?: playlists.firstOrNull()

    fun byId(id: Int): SavedPlaylist? = playlists.firstOrNull { it.id == id }

    fun byUrl(url: String): SavedPlaylist? = playlists.firstOrNull { it.url == url }

    // Called only after a successful load / Вызывается только после успешной загрузки
    fun add(url: String): SavedPlaylist {
        val p = SavedPlaylist(nextId++, TextUtil.playlistName(url), url)
        playlists.add(p)
        return p
    }

    fun remove(ctx: Context, id: Int) {
        playlists.removeAll { it.id == id }
        cacheFile(ctx, id).delete()
        if (lastOkId == id) lastOkId = 0
    }

    // ---------- copies of playlists / копии плейлистов ----------

    private fun cacheFile(ctx: Context, id: Int) = File(ctx.filesDir, "pl_$id.m3u")

    fun readCache(ctx: Context, id: Int): String? = try {
        val f = cacheFile(ctx, id)
        if (f.exists()) f.readText() else null
    } catch (e: Exception) {
        AppLog.e("ERR", "storage: cannot read playlist copy: " + e.javaClass.simpleName)
        null
    }

    fun writeCache(ctx: Context, id: Int, text: String) {
        try {
            writeSafely(cacheFile(ctx, id), text)
        } catch (e: Exception) {
            AppLog.e("ERR", "storage: cannot save playlist copy: " + e.javaClass.simpleName)
        }
    }

    fun dropCache(ctx: Context, id: Int) {
        cacheFile(ctx, id).delete()
    }

    // The copy of the TV guide (one, for the current playlist) / Копия программы передач (одна)
    fun epgFile(ctx: Context): File = File(ctx.filesDir, "epg.bin")
}
