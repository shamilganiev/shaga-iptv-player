// ShaGa IPTV Player for Android — Storage.kt
// Saved settings: one JSON file, the same scheme as the Roku registry and LG storage.
// Сохранённые настройки: один JSON-файл, схема та же, что в реестре Roku и хранилище LG.
//   { v, lastOkId, nextId, playlists[{id, name, url}] }
// 0.1.0 keeps one playlist; up to 5 come with the "Playlists" window.
// В 0.1.0 хранится один плейлист; до 5 — вместе с окном «Плейлисты».
package com.shaga.player

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class SavedPlaylist(val id: Int, var name: String, var url: String)

object Storage {
    private const val FILE = "settings.json"
    private const val VERSION = 1

    val playlists = ArrayList<SavedPlaylist>()
    var lastOkId = 0
    var nextId = 1

    fun load(ctx: Context) {
        playlists.clear(); lastOkId = 0; nextId = 1
        try {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            lastOkId = o.optInt("lastOkId", 0)
            nextId = o.optInt("nextId", 1)
            val arr = o.optJSONArray("playlists") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val url = p.optString("url", "")
                if (url.isNotEmpty()) playlists.add(SavedPlaylist(p.optInt("id", i + 1), p.optString("name", ""), url))
            }
        } catch (e: Exception) {
            AppLog.e("ERR", "storage: cannot read settings: " + e.javaClass.simpleName)
        }
    }

    fun save(ctx: Context) {
        try {
            val arr = JSONArray()
            for (p in playlists) arr.put(JSONObject().put("id", p.id).put("name", p.name).put("url", p.url))
            val o = JSONObject().put("v", VERSION).put("lastOkId", lastOkId).put("nextId", nextId).put("playlists", arr)
            // Write to a temporary file first, so that a failure cannot spoil the settings.
            // Сначала пишем во временный файл, чтобы сбой не испортил настройки.
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(o.toString())
            val f = File(ctx.filesDir, FILE)
            if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        } catch (e: Exception) {
            AppLog.e("ERR", "storage: cannot save settings: " + e.javaClass.simpleName)
        }
    }

    // The last successfully loaded playlist, else the first one.
    // Последний успешно загруженный плейлист, иначе первый.
    fun current(): SavedPlaylist? = playlists.firstOrNull { it.id == lastOkId } ?: playlists.firstOrNull()

    // 0.1.0: the new link replaces the only playlist. Called only after a successful load.
    // 0.1.0: новая ссылка заменяет единственный плейлист. Вызывается только после успешной загрузки.
    fun setSingle(url: String) {
        val cur = current()
        if (cur != null) {
            cur.url = url
            cur.name = TextUtil.playlistName(url)
            lastOkId = cur.id
        } else {
            val p = SavedPlaylist(nextId++, TextUtil.playlistName(url), url)
            playlists.add(p)
            lastOkId = p.id
        }
    }
}
