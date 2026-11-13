// ShaGa IPTV Player for Android — Ui.kt
// Screens of version 0.1.0: welcome, loading, channel list, full-screen player.
// Every item works both with the remote (focus + OK) and with a finger (tap).
// Экраны версии 0.1.0: приветствие, загрузка, список каналов, плеер на весь экран.
// Каждый элемент работает и с пульта (фокус + OK), и пальцем (нажатие).
package com.shaga.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

// Colours as in the LG version / Цвета как в LG-версии
object Ui {
    val Bg = Color(0xFF141C2E)
    val Panel = Color(0xFF1C2740)
    val Text = Color(0xFFEEF1F7)
    val Muted = Color(0xFF8F9AB4)
    val Amber = Color(0xFFF5B83D)     // focus highlight / подсветка фокуса
    val Ink = Color(0xFF231803)       // text on amber / текст на янтарном
    val Error = Color(0xFFF28B7C)
    val Shade = Color(0xCC141C2E)     // bar over the video / плашка поверх видео
}

enum class Screen { WELCOME, LOADING, LIST, PLAYER }

// Everything the screens show. Changed only by MainActivity.
// Всё, что показывают экраны. Меняет только MainActivity.
class AppState {
    var screen by mutableStateOf(Screen.LOADING)
    var playlist by mutableStateOf<Playlist?>(null)
    var playlistName by mutableStateOf("")
    var listError by mutableStateOf("")
    var playing by mutableStateOf<Channel?>(null)
    var lastIndex by mutableStateOf(0)      // the channel to put the cursor on / канал, на который ставится курсор
    var isTv = false
}

@Composable
fun AppRoot(state: AppState, onEditLink: () -> Unit, onPlay: (Int) -> Unit, onClosePlayer: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ui.Bg)) {
        val ch = state.playing
        if (state.screen == Screen.PLAYER && ch != null) {
            PlayerScreen(ch, onClosePlayer)
        } else {
            // safeDrawingPadding: keeps the content away from system bars and cut-outs on phones.
            // safeDrawingPadding: держит содержимое в стороне от системных панелей и вырезов на телефонах.
            val edge = if (state.isTv) Modifier.padding(horizontal = 48.dp, vertical = 27.dp)
            else Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            Box(Modifier.fillMaxSize().safeDrawingPadding().then(edge)) {
                when (state.screen) {
                    Screen.WELCOME -> WelcomeScreen(onEditLink)
                    Screen.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Label(S.t("loading"), color = Ui.Muted, size = 20)
                    }
                    else -> ListScreen(state, onEditLink, onPlay)
                }
            }
        }
    }
}

// ---------- building blocks / кирпичики ----------

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ui.Text,
    size: Int = 18,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Start
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = align
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines
    )
}

// A selectable item: amber when it has the focus (remote), reacts to OK and to a tap.
// Выбираемый элемент: янтарный, когда на нём фокус (пульт), реагирует на OK и на нажатие.
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    idleColor: Color = Color.Transparent,
    content: @Composable (Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val base = if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier
    Box(
        base
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Ui.Amber else idleColor, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        content(focused)
    }
}

// Puts the cursor on an item when a screen opens. The item may appear a moment later
// than this code runs, so there are three attempts (after 50, 200 and 500 ms).
// Ставит курсор на элемент при открытии экрана. Элемент может появиться чуть позже,
// чем выполнится этот код, поэтому попыток три (через 50, 200 и 500 мс).
@Composable
private fun FocusOnStart(fr: FocusRequester, key: Any?) {
    LaunchedEffect(key) {
        for (wait in longArrayOf(50L, 150L, 300L)) {
            delay(wait)
            try {
                // Newer Compose returns true when the focus is set; older returns nothing.
                // Новый Compose возвращает true, когда фокус поставлен; старый — ничего.
                val result: Any = fr.requestFocus()
                if (result == true) break
            } catch (e: IllegalStateException) {
                // not on the screen yet / ещё не на экране
            }
        }
    }
}

// ---------- screens / экраны ----------

@Composable
private fun WelcomeScreen(onAdd: () -> Unit) {
    val fr = remember { FocusRequester() }
    FocusOnStart(fr, Unit)
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Label(S.t("welcomeTitle"), size = 26, bold = true, align = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Label(
            S.t("welcomeText"), modifier = Modifier.widthIn(max = 560.dp),
            color = Ui.Muted, size = 17, align = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        FocusBox(onClick = onAdd, focusRequester = fr, idleColor = Ui.Panel) { f ->
            Label(S.t("welcomeBtn"), color = if (f) Ui.Ink else Ui.Text, size = 18, bold = true)
        }
    }
}

@Composable
private fun ListScreen(state: AppState, onEditLink: () -> Unit, onPlay: (Int) -> Unit) {
    val pl = state.playlist
    val count = pl?.channels?.size ?: 0
    // Row 0 is "> Change link", channels start from row 1.
    // Строка 0 — «> Изменить ссылку», каналы начинаются со строки 1.
    val focusRow = remember(pl) { if (count > 0) state.lastIndex.coerceIn(0, count - 1) + 1 else 0 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, focusRow - 3))
    val fr = remember { FocusRequester() }
    FocusOnStart(fr, pl)

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Label(Config.APP_NAME, color = Ui.Amber, size = 20, bold = true, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Label("v" + BuildConfig.VERSION_NAME, color = Ui.Muted, size = 14, maxLines = 1)
        }
        if (state.playlistName.isNotEmpty()) {
            Label(
                state.playlistName + " · " + S.t("channels").replace("{n}", count.toString()),
                color = Ui.Muted, size = 14, maxLines = 1
            )
        }
        if (state.listError.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Label(S.t("errTitle"), color = Ui.Error, size = 18, bold = true)
            Label(state.listError, color = Ui.Error, size = 16)
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            item {
                FocusBox(
                    onClick = onEditLink, modifier = Modifier.fillMaxWidth(),
                    focusRequester = if (focusRow == 0) fr else null
                ) { f ->
                    Label("> " + S.t("editLink"), color = if (f) Ui.Ink else Ui.Text, size = 18, maxLines = 1)
                }
            }
            if (pl != null) {
                itemsIndexed(pl.channels) { i, ch ->
                    FocusBox(
                        onClick = { onPlay(i) }, modifier = Modifier.fillMaxWidth(),
                        focusRequester = if (i + 1 == focusRow) fr else null
                    ) { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(
                                ch.n.toString(), modifier = Modifier.width(52.dp),
                                color = if (f) Ui.Ink else Ui.Muted, size = 16, maxLines = 1, align = TextAlign.End
                            )
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Label(ch.name, color = if (f) Ui.Ink else Ui.Text, size = 18, maxLines = 1)
                                Label(
                                    ch.group.ifEmpty { S.t("noGroup") },
                                    color = if (f) Ui.Ink else Ui.Muted, size = 13, maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerScreen(ch: Channel, onClose: () -> Unit) {
    val context = LocalContext.current
    var playback by remember { mutableStateOf<Playback?>(null) }
    var playState by remember { mutableStateOf(PlayState.STARTING) }
    var timedOut by remember { mutableStateOf(false) }
    var showSign by remember { mutableStateOf(false) }     // loading sign / значок загрузки
    var showInfo by remember { mutableStateOf(true) }      // channel bar / плашка канала
    var infoTick by remember { mutableStateOf(0) }
    val fr = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }

    // The player lives while this screen is open / Плеер живёт, пока открыт этот экран
    DisposableEffect(ch) {
        val p = Playback(context, ch) { playState = it }
        playback = p
        onDispose {
            playback = null
            p.release()
        }
    }

    BackHandler { onClose() }
    FocusOnStart(fr, Unit)

    // Channel bar: shown at start and after OK / a tap, hides by itself.
    // Плашка канала: показывается при старте и после OK / нажатия, скрывается сама.
    LaunchedEffect(infoTick) {
        showInfo = true
        delay(Config.INFO_HIDE_MS)
        showInfo = false
    }

    // Loading sign only when buffering lasts long enough.
    // Значок загрузки — только если буферизация длится достаточно долго.
    LaunchedEffect(playState) {
        showSign = false
        if (playState == PlayState.STARTING || playState == PlayState.BUFFERING) {
            delay(Config.BUFFER_SIGN_MS)
            showSign = true
        }
    }

    // Channel start timeout / Тайм-аут старта канала
    LaunchedEffect(ch) {
        delay(Config.START_TIMEOUT_MS)
        val p = playback
        if (p != null && !p.started && playState != PlayState.ERROR) {
            AppLog.e("PLAY", "start timeout " + Config.START_TIMEOUT_MS + " ms")
            timedOut = true
            p.stop()
            playState = PlayState.ERROR
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false          // our own controls / управление своё
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { view -> view.player = playback?.exo },
            onRelease = { view -> view.player = null }
        )

        // Transparent layer over the video: takes OK from the remote and taps.
        // Прозрачный слой поверх видео: принимает OK с пульта и нажатия.
        Box(
            Modifier.fillMaxSize()
                .focusRequester(fr)
                .clickable(interactionSource = interaction, indication = null) { infoTick++ }
        )

        if (playState == PlayState.ERROR) {
            Column(
                Modifier.align(Alignment.Center)
                    .background(Ui.Panel, RoundedCornerShape(12.dp))
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Label(S.t(if (timedOut) "playTimeout" else "playError"), color = Ui.Error, size = 20, bold = true)
                Spacer(Modifier.height(6.dp))
                Label(ch.name, size = 16, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Label(S.t("hintBackList"), color = Ui.Muted, size = 14)
            }
        } else if (showSign) {
            Label(
                S.t("buffering"),
                modifier = Modifier.align(Alignment.Center)
                    .background(Ui.Shade, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                size = 18
            )
        }

        if (showInfo) {
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Ui.Shade)
                    .navigationBarsPadding()
                    .padding(horizontal = 32.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Label(ch.n.toString(), color = Ui.Amber, size = 24, bold = true, maxLines = 1)
                Spacer(Modifier.width(16.dp))
                Column {
                    Label(ch.name, size = 20, bold = true, maxLines = 1)
                    Label(ch.group.ifEmpty { S.t("noGroup") }, color = Ui.Muted, size = 14, maxLines = 1)
                }
            }
        }
    }
}
