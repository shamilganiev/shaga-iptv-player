// ShaGa IPTV Player for Android — PlaylistLoader.kt
// Downloads a playlist by its link and parses it. Always past the HTTP cache.
// Загружает плейлист по ссылке и разбирает его. Всегда мимо HTTP-кэша.
package com.shaga.player

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

// code: "timeout" | "http" (status = HTTP code) | "network" | "empty"
class LoadError(val code: String, val status: Int = 0) : Exception(code)

object PlaylistLoader {

    // Blocking call: run it off the main thread. Throws LoadError.
    // Блокирующий вызов: запускать не в главном потоке. Бросает LoadError.
    fun load(url: String): Playlist {
        val t0 = System.currentTimeMillis()
        val deadline = t0 + Config.LOAD_TIMEOUT_MS
        AppLog.i("LOAD", "playlist: start " + Mask.url(url))

        val text: String
        try {
            text = download(url, deadline)
        } catch (e: LoadError) {
            AppLog.e("LOAD", "playlist: " + e.code + (if (e.status > 0) " " + e.status else "") +
                " after " + (System.currentTimeMillis() - t0) + " ms")
            throw e
        } catch (e: SocketTimeoutException) {
            AppLog.e("LOAD", "playlist: timeout after " + (System.currentTimeMillis() - t0) + " ms")
            throw LoadError("timeout")
        } catch (e: IOException) {
            AppLog.e("LOAD", "playlist: network error " + e.javaClass.simpleName + ": " + (e.message ?: ""))
            throw LoadError("network")
        } catch (e: RuntimeException) {
            // e.g. a link that Java cannot understand / например, ссылка, которую Java не понимает
            AppLog.e("LOAD", "playlist: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
            throw LoadError("network")
        }
        val t1 = System.currentTimeMillis()
        AppLog.i("LOAD", "playlist: received " + (text.length / 1024) + " KB in " + (t1 - t0) + " ms")

        val pl = M3u.parse(text)
        AppLog.i("LOAD", "playlist: parsed " + pl.channels.size + " channels, " + pl.groupCount +
            " groups in " + (System.currentTimeMillis() - t1) + " ms; EPG: " +
            (if (pl.epgUrl.isEmpty()) "none" else Mask.url(pl.epgUrl.substringBefore(','))))
        if (pl.channels.isEmpty()) {
            AppLog.w("LOAD", "playlist: no channels, file starts with: " + text.take(120).replace('\n', ' '))
            throw LoadError("empty")
        }
        return pl
    }

    private fun download(startUrl: String, deadline: Long): String {
        var u = URL(startUrl)
        // Redirects are followed by hand: Java does not follow http <-> https by itself.
        // Переадресации обходим сами: Java не переходит между http и https.
        for (step in 0 until 6) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw LoadError("timeout")
            val c = u.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = left.toInt()
                c.readTimeout = left.toInt()
                c.useCaches = false
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", Config.PLAYLIST_UA)
                c.setRequestProperty("Cache-Control", "no-cache")
                c.setRequestProperty("Pragma", "no-cache")
                val code = c.responseCode
                if (code in 301..308 && code != 304) {
                    val loc = c.getHeaderField("Location") ?: throw LoadError("http", code)
                    u = URL(u, loc)
                    AppLog.i("LOAD", "playlist: redirect $code to " + Mask.url(u.toString()))
                    continue
                }
                if (code !in 200..299) throw LoadError("http", code)

                val out = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                c.inputStream.use { input ->
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                        if (out.size() > Config.MAX_PLAYLIST_BYTES) throw LoadError("empty")
                        if (System.currentTimeMillis() > deadline) throw LoadError("timeout")
                    }
                }
                return String(out.toByteArray(), Charsets.UTF_8)
            } finally {
                c.disconnect()
            }
        }
        throw LoadError("network")
    }
}
