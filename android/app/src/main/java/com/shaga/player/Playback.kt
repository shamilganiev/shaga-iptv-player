// ShaGa IPTV Player for Android — Playback.kt
// One channel in the Media3 (ExoPlayer) player: start, headers from the playlist, errors.
// Один канал в плеере Media3 (ExoPlayer): запуск, заголовки из плейлиста, ошибки.
package com.shaga.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

// What the screen shows / Что показывает экран
enum class PlayState { STARTING, BUFFERING, PLAYING, ERROR }

class Playback(
    context: Context,
    private val channel: Channel,
    private val onState: (PlayState) -> Unit
) {
    val exo: ExoPlayer
    var started = false          // the picture has appeared at least once / картинка уже появлялась
        private set
    private var triedHls = false
    private var released = false
    private val t0 = System.currentTimeMillis()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> onState(if (started) PlayState.BUFFERING else PlayState.STARTING)
                Player.STATE_READY -> {
                    if (!started) {
                        started = true
                        val f = exo.videoFormat
                        val video = if (f == null) "no video" else "" + f.width + "x" + f.height + " " + (f.sampleMimeType ?: "?")
                        AppLog.i("PLAY", "started in " + (System.currentTimeMillis() - t0) + " ms, " + video)
                    }
                    onState(PlayState.PLAYING)
                }
                Player.STATE_ENDED -> {
                    AppLog.w("PLAY", "stream ended")
                    onState(PlayState.ERROR)
                }
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause
            AppLog.e("PLAY", "error " + error.errorCodeName + ": " + (error.message ?: "") +
                (if (cause != null) " / " + cause.javaClass.simpleName + ": " + (cause.message ?: "") else ""))
            // A link without ".m3u8" is first tried as a plain stream; when the player does not
            // recognise the container, it is most likely an HLS playlist: try once more as HLS.
            // Ссылка без «.m3u8» сначала пробуется как обычный поток; если плеер не узнал
            // формат, скорее всего это HLS-плейлист: пробуем ещё раз как HLS.
            val unknownContainer = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            if (unknownContainer && !started && !triedHls) {
                triedHls = true
                AppLog.i("PLAY", "retry as HLS")
                open(MimeTypes.APPLICATION_M3U8)
                return
            }
            onState(PlayState.ERROR)
        }
    }

    init {
        // Headers from #EXTVLCOPT of this channel / Заголовки из #EXTVLCOPT этого канала
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        if (channel.userAgent.isNotEmpty()) http.setUserAgent(channel.userAgent)
        if (channel.referrer.isNotEmpty()) http.setDefaultRequestProperties(mapOf("Referer" to channel.referrer))

        val dataSource = DefaultDataSource.Factory(context, http)
        exo = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        exo.addListener(listener)

        val mime = guessMime(channel.url)
        AppLog.i("PLAY", "#" + channel.n + " " + channel.name + " " + Mask.url(channel.url) +
            " as " + (mime ?: "auto"))
        open(mime)
    }

    private fun open(mime: String?) {
        val item = MediaItem.Builder().setUri(channel.url)
        if (mime != null) item.setMimeType(mime)
        exo.setMediaItem(item.build())
        exo.prepare()
        exo.playWhenReady = true
    }

    // Stream type by the link; null = the player decides (ts, mp4, mkv...).
    // Тип потока по ссылке; null = плеер определяет сам (ts, mp4, mkv...).
    private fun guessMime(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    fun stop() {
        if (!released) exo.stop()
    }

    fun release() {
        if (released) return
        released = true
        exo.removeListener(listener)
        exo.release()
        AppLog.i("PLAY", "stopped #" + channel.n)
    }
}
