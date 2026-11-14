// ShaGa IPTV Player for Android — Ui.kt
// Screens: welcome, loading, main screen (groups, channels, info; video under the list),
// full screen. The screen only draws what Nav holds and reports taps; the remote is handled
// in MainActivity, so nothing here needs the input focus.
// Экраны: приветствие, загрузка, главный экран (группы, каналы, сведения; видео под списком),
// полный экран. Экран только рисует то, что хранит Nav, и сообщает о нажатиях пальцем; пульт
// обрабатывается в MainActivity, поэтому фокус ввода здесь нигде не нужен.
package com.shaga.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

// Colours as in the LG version / Цвета как в LG-версии
object Ui {
    val Bg = Color(0xFF141C2E)
    val Panel = Color(0xFF1C2740)
    val Text = Color(0xFFEEF1F7)
    val Muted = Color(0xFF8F9AB4)
    val Amber = Color(0xFFF5B83D)     // cursor / курсор
    val Ink = Color(0xFF231803)       // text on amber / текст на янтарном
    val Error = Color(0xFFF28B7C)
    val Shade = Color(0xCC141C2E)     // list over the video / список поверх видео
    val Veil = Color(0x66141C2E)      // info column over the video / колонка сведений поверх видео
    val Mark = Color(0x55F5B83D)      // row under the cursor of the other column / строка под курсором другой колонки
}

enum class Screen { WELCOME, LOADING, MAIN }

// What the screens show besides Nav. Changed only by MainActivity.
// Что экраны показывают помимо Nav. Меняет только MainActivity.
class UiState {
    var tick by mutableStateOf(0)           // grows on every change in Nav / растёт при каждом изменении в Nav
    var screen by mutableStateOf(Screen.LOADING)
    var playlistName by mutableStateOf("")
    var listError by mutableStateOf("")
    var playState by mutableStateOf(PlayState.IDLE)
    var isTv = false
}

@Composable
fun AppRoot(ui: UiState, nav: Nav, exo: Player, onWelcome: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ui.Bg)) {
        // Wide screen (TV, tablet): three columns. Narrow (phone): one column at a time.
        // Широкий экран (ТВ, планшет): три колонки. Узкий (телефон): по одной колонке.
        val wide = maxWidth >= 840.dp
        val portrait = maxHeight > maxWidth
        val screenWidth = maxWidth
        when (ui.screen) {
            Screen.WELCOME -> WelcomeScreen(ui.isTv, onWelcome)
            Screen.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Label(S.t("loading"), color = Ui.Muted, size = 20)
            }
            Screen.MAIN -> MainScreen(ui, nav, exo, wide, portrait, screenWidth)
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

// Edge padding: system bars and cut-outs on phones, a margin on TV.
// Отступ от краёв: системные панели и вырезы на телефонах, поле на ТВ.
private fun Modifier.screenEdges(isTv: Boolean): Modifier =
    this.safeDrawingPadding().then(
        if (isTv) Modifier.padding(horizontal = 48.dp, vertical = 27.dp)
        else Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
    )

// A row of a list: fixed height, background by state, reacts to a tap.
// Строка списка: постоянная высота, фон по состоянию, реагирует на нажатие пальцем.
@Composable
private fun ListRow(index: Int, height: Dp, background: Color, onTap: (Int) -> Unit, content: @Composable () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier.fillMaxWidth().height(height)
            .background(background, RoundedCornerShape(8.dp))
            .pointerInput(index) { detectTapGestures(onTap = { tap(index) }) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        content()
    }
}

// Keeps the row with the cursor on the screen and tells how many rows fit on a page.
// The rows have a fixed height, so the page is counted without measuring every row.
// Держит строку с курсором на экране и сообщает, сколько строк помещается на странице.
// Высота строк постоянная, поэтому страница считается без измерения каждой строки.
@Composable
private fun KeepVisible(listState: LazyListState, sel: Int, rowPx: Int, key: Any?, onPerPage: (Int) -> Unit) {
    LaunchedEffect(sel, key) {
        val viewport = listState.layoutInfo.viewportSize.height
        if (viewport <= 0 || rowPx <= 0) return@LaunchedEffect      // not measured yet / ещё не измерен
        val perPage = maxOf(1, viewport / rowPx)
        onPerPage(perPage)
        val top = listState.firstVisibleItemIndex
        val shifted = listState.firstVisibleItemScrollOffset > 0
        if (sel < top || (sel == top && shifted)) {
            listState.scrollToItem(sel)
        } else if (sel > top + perPage - 1) {
            listState.scrollToItem(sel - perPage + 1)
        }
    }
}

@Composable
private fun ColumnHeader(text: String) {
    Label(
        text, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        color = Ui.Amber, size = 16, bold = true, maxLines = 1
    )
}

@Composable
private fun Logo(url: String, width: Dp, height: Dp) {
    if (url.isEmpty()) {
        Spacer(Modifier.size(width, height))
    } else {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(width, height))
    }
}

// ---------- welcome / приветствие ----------

@Composable
private fun WelcomeScreen(isTv: Boolean, onAdd: () -> Unit) {
    val add by rememberUpdatedState(onAdd)
    Column(
        Modifier.fillMaxSize().screenEdges(isTv),
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
        // The only button of the screen: OK on the remote or a tap.
        // Единственная кнопка экрана: OK на пульте или нажатие пальцем.
        Label(
            S.t("welcomeBtn"),
            modifier = Modifier
                .background(Ui.Amber, RoundedCornerShape(8.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = { add() }) }
                .padding(horizontal = 22.dp, vertical = 12.dp),
            color = Ui.Ink, size = 18, bold = true
        )
    }
}

// ---------- main screen / главный экран ----------

@Composable
private fun MainScreen(
    ui: UiState, nav: Nav, exo: Player,
    wide: Boolean, portrait: Boolean, screenWidth: Dp
) {
    // Nav is plain Kotlin and cannot tell the screen about its changes. MainActivity grows
    // ui.tick on every change; reading it here makes this function run again and re-read Nav.
    // Nav — обычный Kotlin и сам сообщить экрану об изменениях не может. MainActivity
    // увеличивает ui.tick при каждом изменении; чтение здесь заставляет эту функцию
    // выполниться снова и заново прочитать Nav.
    val tick = ui.tick
    if (tick < 0) return
    val playing = nav.playing
    val full = nav.fullscreen && playing != null
    // Phone held upright: the video is a box at the top, the list is under it.
    // Otherwise the video takes the whole screen and the list lies over it.
    // Телефон вертикально: видео — прямоугольник вверху, список под ним.
    // Иначе видео занимает весь экран, а список лежит поверх него.
    val topVideo = !wide && portrait && playing != null && !full
    val videoHeight = screenWidth * 9f / 16f
    val shown = nav.shown
    val channels = shown?.channels ?: emptyList()

    Box(Modifier.fillMaxSize()) {
        if (playing != null) {
            VideoLayer(
                exo = exo,
                modifier = if (topVideo) Modifier.statusBarsPadding().fillMaxWidth().height(videoHeight)
                else Modifier.fillMaxSize(),
                onTap = { nav.tapVideo() }
            )
        }

        if (full && playing != null) {
            FullscreenOverlay(playing, ui.playState, nav.infoTick)
        } else {
            if (topVideo) {
                Box(
                    Modifier.statusBarsPadding().fillMaxWidth().height(videoHeight),
                    contentAlignment = Alignment.Center
                ) {
                    PlayStatus(ui.playState)
                }
            }
            Column(
                Modifier.fillMaxSize().screenEdges(ui.isTv).padding(top = if (topVideo) videoHeight else 0.dp)
            ) {
                TopLine(ui, playing)
                Spacer(Modifier.height(8.dp))
                if (wide) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        GroupsColumn(
                            Modifier.weight(0.27f).fillMaxHeight(), nav.rows, nav.groupSel,
                            cursor = nav.keyMode && nav.column == Pane.GROUPS, shown = shown,
                            onTap = { nav.tapGroup(it) }, onPerPage = { nav.groupsPerPage = it }
                        )
                        Spacer(Modifier.width(10.dp))
                        ChannelsColumn(
                            Modifier.weight(0.40f).fillMaxHeight(), channels, nav.chanSel,
                            cursor = nav.keyMode && nav.column == Pane.CHANNELS, playing = playing,
                            backTitle = "", onBack = { nav.back() },
                            onTap = { nav.tapChannel(it) }, onPerPage = { nav.channelsPerPage = it }
                        )
                        Spacer(Modifier.width(10.dp))
                        InfoColumn(
                            Modifier.weight(0.33f).fillMaxHeight(),
                            channel = playing ?: channels.getOrNull(nav.chanSel),
                            isPlaying = playing != null, playState = ui.playState
                        )
                    }
                } else if (nav.column == Pane.GROUPS) {
                    GroupsColumn(
                        Modifier.fillMaxWidth().weight(1f), nav.rows, nav.groupSel,
                        cursor = nav.keyMode, shown = shown,
                        onTap = { nav.tapGroup(it) }, onPerPage = { nav.groupsPerPage = it }
                    )
                } else {
                    ChannelsColumn(
                        Modifier.fillMaxWidth().weight(1f), channels, nav.chanSel,
                        cursor = nav.keyMode, playing = playing,
                        backTitle = shown?.title ?: "", onBack = { nav.back() },
                        onTap = { nav.tapChannel(it) }, onPerPage = { nav.channelsPerPage = it }
                    )
                }
            }
        }
    }
}

// The top line: "Now watching: <channel>"; when nothing plays, the app and the playlist.
// Верхняя строка: «Вы смотрите: <канал>»; когда ничего не играет — приложение и плейлист.
@Composable
private fun TopLine(ui: UiState, playing: Channel?) {
    if (playing != null) {
        Label(S.t("watching") + " " + playing.name, size = 18, bold = true, maxLines = 1)
    } else {
        val name = if (ui.playlistName.isEmpty()) "" else "  ·  " + ui.playlistName
        Label(Config.APP_NAME + " v" + BuildConfig.VERSION_NAME + name, color = Ui.Muted, size = 15, maxLines = 1)
    }
    if (ui.listError.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Label(S.t("errTitle"), color = Ui.Error, size = 18, bold = true)
        Label(ui.listError, color = Ui.Error, size = 16)
    }
}

private fun rowText(row: LeftRow): String {
    val cat = row.category
    return if (cat == null) "> " + row.title else row.title + " (" + cat.channels.size + ")"
}

@Composable
private fun GroupsColumn(
    modifier: Modifier, rows: List<LeftRow>, sel: Int, cursor: Boolean, shown: Category?,
    onTap: (Int) -> Unit, onPerPage: (Int) -> Unit
) {
    val listState = remember(rows) { LazyListState(maxOf(0, sel - 2)) }
    val rowPx = with(LocalDensity.current) { Config.ROW_GROUP_DP.dp.roundToPx() }
    KeepVisible(listState, sel, rowPx, rows, onPerPage)

    Column(modifier.background(Ui.Shade, RoundedCornerShape(10.dp)).padding(vertical = 8.dp, horizontal = 4.dp)) {
        // Above the groups: the highlighted group / Над группами: выделенная группа
        ColumnHeader(rows.getOrNull(sel)?.title ?: "")
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(rows) { i, row ->
                val onCursor = cursor && i == sel
                val marked = !onCursor && (i == sel || (row.category != null && row.category === shown))
                ListRow(
                    index = i, height = Config.ROW_GROUP_DP.dp,
                    background = if (onCursor) Ui.Amber else if (marked) Ui.Mark else Color.Transparent,
                    onTap = onTap
                ) {
                    Label(rowText(row), color = if (onCursor) Ui.Ink else Ui.Text, size = 18, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ChannelsColumn(
    modifier: Modifier, channels: List<Channel>, sel: Int, cursor: Boolean, playing: Channel?,
    backTitle: String, onBack: () -> Unit,
    onTap: (Int) -> Unit, onPerPage: (Int) -> Unit
) {
    val listState = remember(channels) { LazyListState(maxOf(0, sel - 2)) }
    val rowPx = with(LocalDensity.current) { Config.ROW_CHANNEL_DP.dp.roundToPx() }
    KeepVisible(listState, sel, rowPx, channels, onPerPage)
    val back by rememberUpdatedState(onBack)

    Column(modifier.background(Ui.Shade, RoundedCornerShape(10.dp)).padding(vertical = 8.dp, horizontal = 4.dp)) {
        if (backTitle.isNotEmpty()) {
            // Phone: the way back to the groups / Телефон: путь назад к группам
            Label(
                "< " + backTitle,
                modifier = Modifier.fillMaxWidth()
                    .pointerInput(Unit) { detectTapGestures(onTap = { back() }) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                color = Ui.Amber, size = 17, bold = true, maxLines = 1
            )
        } else {
            // Above the channels: the highlighted channel / Над каналами: выделенный канал
            ColumnHeader(channels.getOrNull(sel)?.name ?: "")
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(channels) { i, ch ->
                val onCursor = cursor && i == sel
                val isPlaying = ch === playing
                ListRow(
                    index = i, height = Config.ROW_CHANNEL_DP.dp,
                    background = if (onCursor) Ui.Amber else if (i == sel) Ui.Mark else Color.Transparent,
                    onTap = onTap
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Number: a column for 4 digits, aligned right / Номер: колонка под 4 цифры, вправо
                        Label(
                            ch.n.toString(), modifier = Modifier.width(46.dp),
                            color = if (onCursor) Ui.Ink else Ui.Muted, size = 15, maxLines = 1, align = TextAlign.End
                        )
                        Spacer(Modifier.width(10.dp))
                        Logo(ch.logo, 48.dp, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        // The second line (current programme) comes with the TV guide.
                        // Вторая строка (текущая передача) придёт вместе с программой передач.
                        Label(
                            ch.name,
                            color = if (onCursor) Ui.Ink else if (isPlaying) Ui.Amber else Ui.Text,
                            size = 18, bold = isPlaying, maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

// The third column (wide screens): the playing channel, else the highlighted one.
// The TV guide will live here.
// Третья колонка (широкие экраны): играющий канал, иначе выделенный.
// Здесь будет программа передач.
@Composable
private fun InfoColumn(modifier: Modifier, channel: Channel?, isPlaying: Boolean, playState: PlayState) {
    Column(modifier.background(Ui.Veil, RoundedCornerShape(10.dp)).padding(vertical = 8.dp, horizontal = 4.dp)) {
        ColumnHeader(channel?.name ?: "")
        if (channel != null) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Logo(channel.logo, 96.dp, 72.dp)
                Spacer(Modifier.height(8.dp))
                Label(channel.group.ifEmpty { S.t("noGroup") }, color = Ui.Muted, size = 15, maxLines = 1)
                if (isPlaying) {
                    Spacer(Modifier.height(12.dp))
                    PlayStatus(playState)
                }
            }
        }
    }
}

// ---------- video / видео ----------

@Composable
private fun VideoLayer(exo: Player, modifier: Modifier, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Box(modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false          // our own controls / управление своё
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    player = exo
                }
            },
            onRelease = { view -> view.player = null }
        )
        // Transparent layer over the video for taps / Прозрачный слой поверх видео для нажатий
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { tap() }) })
    }
}

// "Connecting…", "Loading…" (only when it lasts) or the error text.
// «Подключение…», «Загрузка…» (только если это длится) или текст ошибки.
@Composable
private fun PlayStatus(state: PlayState, modifier: Modifier = Modifier) {
    var lasting by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        lasting = false
        if (state == PlayState.STARTING || state == PlayState.BUFFERING) {
            delay(Config.BUFFER_SIGN_MS)
            lasting = true
        }
    }
    val box = modifier.widthIn(max = 520.dp)
        .background(Ui.Shade, RoundedCornerShape(12.dp))
        .padding(horizontal = 18.dp, vertical = 10.dp)
    if (state == PlayState.ERROR) {
        Label(S.t("stError"), modifier = box, color = Ui.Error, size = 17, align = TextAlign.Center)
    } else if (lasting && state == PlayState.STARTING) {
        Label(S.t("stConnecting"), modifier = box, size = 17)
    } else if (lasting && state == PlayState.BUFFERING) {
        Label(S.t("buffering"), modifier = box, size = 17)
    }
}

// Full screen: status in the centre, the channel bar at the bottom (hides by itself).
// Полный экран: состояние по центру, плашка канала внизу (скрывается сама).
@Composable
private fun FullscreenOverlay(ch: Channel, playState: PlayState, infoTick: Int) {
    var showInfo by remember { mutableStateOf(true) }
    LaunchedEffect(infoTick) {
        showInfo = true
        delay(Config.INFO_HIDE_MS)
        showInfo = false
    }
    Box(Modifier.fillMaxSize()) {
        PlayStatus(playState, Modifier.align(Alignment.Center))
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
                Logo(ch.logo, 64.dp, 48.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Label(ch.name, size = 20, bold = true, maxLines = 1)
                    Label(ch.group.ifEmpty { S.t("noGroup") }, color = Ui.Muted, size = 14, maxLines = 1)
                }
            }
        }
    }
}
