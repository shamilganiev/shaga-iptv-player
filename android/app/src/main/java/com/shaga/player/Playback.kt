// ShaGa IPTV Player for Android — Playback.kt
// The Media3 (ExoPlayer) player. One player for the whole app; channels are switched in it.
// Headers from the playlist, start timeout, errors, start details in the log.
// Плеер Media3 (ExoPlayer). Один плеер на всё приложение, каналы переключаются в нём.
// Заголовки из плейлиста, тайм-аут старта, ошибки, подробности запуска в журнале.
package com.shaga.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

// What the screen shows / Что показывает экран
enum class PlayState { IDLE, STARTING, BUFFERING, PLAYING, ERROR }

class PlayerEngine(context: Context, private val onState: (PlayState) -> Unit) {
    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    val exo: ExoPlayer = ExoPlayer.Builder(appContext).build()

    private var channel: Channel? = null     // null = nothing is playing / ничего не играет
    private var started = false              // the picture has appeared / картинка появилась
    private var triedHls = false
    private var t0 = 0L
    private var loadLines = 0                // log limits per channel / пределы журнала на канал
    private var errorLines = 0
    private var stateLines = 0
    private var released = false

    private fun ms(): Long = System.currentTimeMillis() - t0

    private val startTimeout = Runnable {
        if (channel != null && !started) {
            AppLog.e("PLAY", "start timeout " + Config.START_TIMEOUT_MS + " ms")
            exo.stop()
            onState(PlayState.ERROR)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (channel == null) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (started && stateLines < 40) {
                        stateLines++
                        AppLog.i("PLAY", "buffering +" + ms() + " ms")
                    }
                    onState(if (started) PlayState.BUFFERING else PlayState.STARTING)
                }
                Player.STATE_READY -> {
                    if (!started) {
                        started = true
                        handler.removeCallbacks(startTimeout)
                        val f = exo.videoFormat
                        val video = if (f == null) "no video" else "" + f.width + "x" + f.height + " " + (f.sampleMimeType ?: "?")
                        AppLog.i("PLAY", "started in " + ms() + " ms, " + video)
                    }
                    onState(PlayState.PLAYING)
                }
                Player.STATE_ENDED -> {
                    AppLog.w("PLAY", "stream ended +" + ms() + " ms")
                    onState(PlayState.ERROR)
                }
                else -> {}
            }
        }

        override fun onRenderedFirstFrame() {
            if (channel != null && stateLines < 40) {
                stateLines++
                AppLog.i("PLAY", "first frame +" + ms() + " ms")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (channel == null) return
            val cause = error.cause
            AppLog.e("PLAY", "error " + error.errorCodeName + ": " + (error.message ?: "") +
                (if (cause != null) " / " + cause.javaClass.simpleName + ": " + (cause.message ?: "") else "") +
                " +" + ms() + " ms")
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
            handler.removeCallbacks(startTimeout)
            onState(PlayState.ERROR)
        }
    }

    // Start details: what was downloaded before the picture appeared, and every failed
    // download. Shows why a channel "hangs" without an error.
    // Подробности запуска: что скачано до появления картинки и каждая неудачная загрузка.
    // Показывает, почему канал «висит» без ошибки.
    private val analytics = object : AnalyticsListener {
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData
        ) {
            if (channel == null || started || loadLines >= 12) return
            loadLines++
            AppLog.i("PLAY", "loaded " + typeName(mediaLoadData.dataType) + " " + (loadEventInfo.bytesLoaded / 1024) +
                " KB in " + loadEventInfo.loadDurationMs + " ms +" + ms() + " ms " + Mask.url(loadEventInfo.uri.toString()))
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData,
            error: IOException, wasCanceled: Boolean
        ) {
            if (channel == null || errorLines >= 20) return
            errorLines++
            AppLog.w("PLAY", "load error " + typeName(mediaLoadData.dataType) + " " + error.javaClass.simpleName + ": " +
                (error.message ?: "") + " +" + ms() + " ms " + Mask.url(loadEventInfo.uri.toString()))
        }
    }

    private fun typeName(dataType: Int): String = when (dataType) {
        C.DATA_TYPE_MANIFEST -> "playlist"
        C.DATA_TYPE_MEDIA -> "media"
        C.DATA_TYPE_MEDIA_INITIALIZATION -> "init"
        else -> "data$dataType"
    }

    init {
        exo.addListener(listener)
        exo.addAnalyticsListener(analytics)
    }

    fun play(ch: Channel) {
        if (released) return
        handler.removeCallbacks(startTimeout)
        channel = ch
        started = false; triedHls = false
        loadLines = 0; errorLines = 0; stateLines = 0
        t0 = System.currentTimeMillis()
        val mime = guessMime(ch.url)
        AppLog.i("PLAY", "#" + ch.n + " " + ch.name + " " + Mask.url(ch.url) + " as " + (mime ?: "auto"))
        onState(PlayState.STARTING)
        open(mime)
        handler.postDelayed(startTimeout, Config.START_TIMEOUT_MS)
    }

    private fun open(mime: String?) {
        val ch = channel ?: return
        // Headers from #EXTVLCOPT of this channel / Заголовки из #EXTVLCOPT этого канала
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        if (ch.userAgent.isNotEmpty()) http.setUserAgent(ch.userAgent)
        if (ch.referrer.isNotEmpty()) http.setDefaultRequestProperties(mapOf("Referer" to ch.referrer))
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(appContext, http))

        val item = MediaItem.Builder().setUri(ch.url)
        if (mime != null) item.setMimeType(mime)
        exo.setMediaSource(sources.createMediaSource(item.build()))
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
        if (released) return
        handler.removeCallbacks(startTimeout)
        val ch = channel
        channel = null
        if (ch != null) AppLog.i("PLAY", "stopped #" + ch.n)
        exo.stop()
        exo.clearMediaItems()
        onState(PlayState.IDLE)
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(startTimeout)
        channel = null
        exo.removeListener(listener)
        exo.removeAnalyticsListener(analytics)
        exo.release()
    }
}
