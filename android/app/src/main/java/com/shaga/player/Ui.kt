// ShaGa IPTV Player for Android — Ui.kt
// Screens: welcome, loading, main screen (groups, channels, info; video under the list),
// full screen, Settings pages, our own windows (menu, PIN pad), the "busy" sign.
// The screen only draws what Nav and the windows hold and reports taps; the remote is handled
// in MainActivity, so nothing here needs the input focus.
// Экраны: приветствие, загрузка, главный экран (группы, каналы, сведения; видео под списком),
// полный экран, страницы Настроек, свои окна (меню, клавиатура PIN), знак «занят».
// Экран только рисует то, что хранят Nav и окна, и сообщает о нажатиях пальцем; пульт
// обрабатывается в MainActivity, поэтому фокус ввода здесь нигде не нужен.
package com.shaga.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
    val Dim = Color(0x99000000)       // under our windows / под нашими окнами
    val PlayOn = Color(0x55F5B83D)    // playing row, column with the cursor / играющая строка, колонка с курсором
    val PlayOff = Color(0x3DFFFFFF)   // playing row, other column / играющая строка, другая колонка
}

enum class Screen { WELCOME, LOADING, MAIN }

// What the screens show besides Nav. Changed only by MainActivity.
// Что экраны показывают помимо Nav. Меняет только MainActivity.
class UiState {
    var tick by mutableStateOf(0)           // grows on every change of Nav or a window / растёт при каждом изменении
    var screen by mutableStateOf(Screen.LOADING)
    var playlistName by mutableStateOf("")
    var stale by mutableStateOf(false)      // a saved copy, the update failed / сохранённая копия, обновить не удалось
    var listError by mutableStateOf("")
    var playState by mutableStateOf(PlayState.IDLE)
    var lang by mutableStateOf("en")        // a new language redraws everything / новый язык перерисовывает всё
    var numBuf by mutableStateOf("")        // channel number being typed / набираемый номер канала
    var busy by mutableStateOf("")          // "loading..." over the screen / «загрузка...» поверх экрана
    var popup by mutableStateOf<Popup?>(null)
    var pin by mutableStateOf<PinPad?>(null)
    var settings by mutableStateOf<SettingsPage?>(null)
    var isTv = false
}

// What the windows report back / О чём сообщают окна
class UiActions(
    val onWelcome: () -> Unit,
    val onPopupTap: (Int) -> Unit,
    val onPopupOutside: () -> Unit,
    val onPinTap: (Int) -> Unit,
    val onPinOutside: () -> Unit,
    val onSettingsTap: (Int) -> Unit,
    val onSettingsBack: () -> Unit
)

@Composable
fun AppRoot(ui: UiState, nav: Nav, exo: Player, act: UiActions) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ui.Bg)) {
        // Wide screen (TV, tablet): three columns. Narrow (phone): one column at a time.
        // Широкий экран (ТВ, планшет): три колонки. Узкий (телефон): по одной колонке.
        val wide = maxWidth >= 840.dp
        val portrait = maxHeight > maxWidth
        val screenWidth = maxWidth
        // Reading tick redraws the windows after a change inside them.
        // Чтение tick перерисовывает окна после изменения внутри них.
        val tick = ui.tick
        // key(language): after a language change the whole screen is built again.
        // key(язык): после смены языка экран строится заново.
        key(ui.lang) {
            when (ui.screen) {
                Screen.WELCOME -> WelcomeScreen(ui.isTv, act.onWelcome)
                Screen.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Label(S.t("loading"), color = Ui.Muted, size = 20)
                }
                Screen.MAIN -> MainScreen(ui, nav, exo, wide, portrait, screenWidth, act)
            }

            // Channel number typed with digits / Номер канала, набираемый цифрами
            val num = ui.numBuf
            if (num.isNotEmpty()) {
                Label(
                    num + if (num.length < 4) "_" else "",
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding()
                        .padding(top = 27.dp, end = 48.dp)
                        .background(Ui.Shade, RoundedCornerShape(10.dp))
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                    color = Ui.Amber, size = 34, bold = true, maxLines = 1
                )
            }

            val popup = ui.popup
            val pin = ui.pin
            if (pin != null) {
                PinWindow(pin, tick, act.onPinTap, act.onPinOutside)
            } else if (popup != null) {
                PopupWindow(popup, tick, act.onPopupTap, act.onPopupOutside)
            }
            if (ui.busy.isNotEmpty()) {
                Box(Modifier.fillMaxSize().background(Ui.Dim), contentAlignment = Alignment.Center) {
                    Label(
                        ui.busy,
                        modifier = Modifier.background(Ui.Panel, RoundedCornerShape(12.dp))
                            .padding(horizontal = 28.dp, vertical = 18.dp),
                        size = 20
                    )
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
    align: TextAlign = TextAlign.Start,
    mono: Boolean = false
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
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

private fun Modifier.taps(key: Any?, onTap: () -> Unit, onHold: (() -> Unit)? = null): Modifier =
    this.pointerInput(key) {
        if (onHold == null) detectTapGestures(onTap = { onTap() })
        else detectTapGestures(onTap = { onTap() }, onLongPress = { onHold() })
    }

// A row of a list: fixed height, background by state, reacts to a tap and a long tap.
// Строка списка: постоянная высота, фон по состоянию, реагирует на нажатие и долгое нажатие.
@Composable
private fun ListRow(
    index: Int, height: Dp, background: Color,
    onTap: (Int) -> Unit, onHold: (Int) -> Unit,
    content: @Composable () -> Unit
) {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    Box(
        Modifier.fillMaxWidth().height(height)
            .background(background, RoundedCornerShape(8.dp))
            .pointerInput(index) { detectTapGestures(onTap = { tap(index) }, onLongPress = { hold(index) }) }
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
    val perPageOut by rememberUpdatedState(onPerPage)
    // The page size is known as soon as the list is measured (and after a rotation).
    // Размер страницы известен, как только список измерен (и после поворота экрана).
    LaunchedEffect(listState, rowPx) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { h ->
            if (h > 0 && rowPx > 0) perPageOut(maxOf(1, h / rowPx))
        }
    }
    LaunchedEffect(sel, key) {
        val viewport = listState.layoutInfo.viewportSize.height
        if (viewport <= 0 || rowPx <= 0) return@LaunchedEffect      // not measured yet / ещё не измерен
        val perPage = maxOf(1, viewport / rowPx)
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

// Background and text colour of a list row (SPEC 1.4.1, "where it plays"):
// the cursor = amber; the playing row = soft amber in the column with the cursor, greyish in
// the other column; the selected row of the other column = amber text.
// Фон и цвет текста строки списка (SPEC 1.4.1, «где играет»): курсор — янтарный; играющая
// строка — мягкий янтарный в колонке с курсором, сероватый в другой колонке; выделенная
// строка другой колонки — янтарный текст.
private class RowLook(val bg: Color, val fg: Color)

private fun rowLook(onCursor: Boolean, isPlaying: Boolean, focusedColumn: Boolean, selectedHere: Boolean): RowLook = when {
    onCursor -> RowLook(Ui.Amber, Ui.Ink)
    isPlaying -> RowLook(if (focusedColumn) Ui.PlayOn else Ui.PlayOff, if (selectedHere) Ui.Amber else Ui.Text)
    selectedHere -> RowLook(Color.Transparent, Ui.Amber)
    else -> RowLook(Color.Transparent, Ui.Text)
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
                .taps(Unit, { add() })
                .padding(horizontal = 22.dp, vertical = 12.dp),
            color = Ui.Ink, size = 18, bold = true
        )
    }
}

// ---------- main screen / главный экран ----------

@Composable
private fun MainScreen(
    ui: UiState, nav: Nav, exo: Player,
    wide: Boolean, portrait: Boolean, screenWidth: Dp, act: UiActions
) {
    // Nav is plain Kotlin and cannot tell the screen about its changes. MainActivity grows
    // ui.tick on every change; reading it here makes this function run again and re-read Nav.
    // Nav — обычный Kotlin и сам сообщить экрану об изменениях не может. MainActivity
    // увеличивает ui.tick при каждом изменении; чтение здесь заставляет эту функцию
    // выполниться снова и заново прочитать Nav.
    val tick = ui.tick
    if (tick < 0) return
    val playing = nav.playing
    val settings = ui.settings
    val full = nav.fullscreen && playing != null && settings == null
    // Phone held upright: the video is a box at the top, the list is under it.
    // Otherwise the video takes the whole screen and the list lies over it.
    // Телефон вертикально: видео — прямоугольник вверху, список под ним.
    // Иначе видео занимает весь экран, а список лежит поверх него.
    val topVideo = !wide && portrait && playing != null && !full
    val videoHeight = screenWidth * 9f / 16f
    val shown = nav.shown
    val channels = shown?.channels ?: emptyList()
    val emptyText = when {
        shown == null -> ""
        shown.locked && channels.isEmpty() -> S.t("lockedHint")
        shown.id == Catalog.FAV && channels.isEmpty() -> S.t("emptyFav")
        channels.isEmpty() -> S.t("emptyGroup")
        else -> ""
    }

    Box(Modifier.fillMaxSize()) {
        if (playing != null) {
            VideoLayer(
                exo = exo,
                modifier = if (topVideo) Modifier.statusBarsPadding().fillMaxWidth().height(videoHeight)
                else Modifier.fillMaxSize(),
                swipes = full,
                onTap = { nav.tapVideo() },
                onSwipe = { nav.swipe(it) }
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
                if (settings != null) {
                    SettingsPanel(Modifier.fillMaxWidth().weight(1f), settings, tick, ui.isTv, act)
                } else if (wide) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        GroupsColumn(
                            Modifier.weight(0.27f).fillMaxHeight(), nav, tick,
                            onTap = { nav.tapGroup(it) }, onHold = { nav.holdGroup(it) }
                        )
                        Spacer(Modifier.width(10.dp))
                        ChannelsColumn(
                            Modifier.weight(0.40f).fillMaxHeight(), nav, tick, channels,
                            backTitle = "", emptyText = emptyText
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
                        Modifier.fillMaxWidth().weight(1f), nav, tick,
                        onTap = { nav.tapGroup(it) }, onHold = { nav.holdGroup(it) }
                    )
                } else {
                    ChannelsColumn(
                        Modifier.fillMaxWidth().weight(1f), nav, tick, channels,
                        backTitle = shown?.title ?: "", emptyText = emptyText
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
        var name = if (ui.playlistName.isEmpty()) "" else "  ·  " + ui.playlistName
        if (ui.stale) name += " " + S.t("staleShort")
        Label(Config.APP_NAME + " v" + BuildConfig.VERSION_NAME + name, color = Ui.Muted, size = 15, maxLines = 1)
    }
    if (ui.listError.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Label(S.t("errTitle"), color = Ui.Error, size = 18, bold = true)
        Label(ui.listError, color = Ui.Error, size = 16)
    }
}

private fun rowText(row: LeftRow): String {
    val cat = row.category ?: return "> " + row.title
    return if (cat.locked) cat.title + " [PIN]" else cat.title + " (" + cat.channels.size + ")"
}

// tick: Nav is the same object after every change, and Compose skips a function whose
// arguments have not changed. The growing tick makes the list draw the new cursor.
// tick: после каждого изменения Nav остаётся тем же объектом, а Compose пропускает функцию,
// у которой аргументы не изменились. Растущий tick заставляет список нарисовать новый курсор.
@Composable
private fun GroupsColumn(modifier: Modifier, nav: Nav, tick: Int, onTap: (Int) -> Unit, onHold: (Int) -> Unit) {
    if (tick < 0) return
    val rows = nav.rows
    val sel = nav.groupSel
    val focused = nav.column == Pane.GROUPS
    val cursor = nav.keyMode && focused
    val playCat = nav.playCat           // read here: the rows below must see its new value / читаем здесь, чтобы строки видели новое значение
    val listState = remember(rows) { LazyListState(maxOf(0, sel - 2)) }
    val rowPx = with(LocalDensity.current) { Config.ROW_GROUP_DP.dp.roundToPx() }
    KeepVisible(listState, sel, rowPx, rows) { nav.groupsPerPage = it }

    Column(modifier.background(Ui.Shade, RoundedCornerShape(10.dp)).padding(vertical = 8.dp, horizontal = 4.dp)) {
        // Above the groups: the highlighted group / Над группами: выделенная группа
        ColumnHeader(rows.getOrNull(sel)?.title ?: "")
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(rows) { i, row ->
                val cat: Category? = row.category
                val look = rowLook(
                    onCursor = cursor && i == sel,
                    isPlaying = cat != null && cat === playCat,
                    focusedColumn = focused,
                    selectedHere = i == sel && !cursor
                )
                ListRow(index = i, height = Config.ROW_GROUP_DP.dp, background = look.bg, onTap = onTap, onHold = onHold) {
                    Label(rowText(row), color = look.fg, size = 18, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ChannelsColumn(modifier: Modifier, nav: Nav, tick: Int, channels: List<Channel>, backTitle: String, emptyText: String) {
    if (tick < 0) return
    val sel = nav.chanSel
    val focused = nav.column == Pane.CHANNELS
    val cursor = nav.keyMode && focused
    val playing = nav.playing
    val listState = remember(channels) { LazyListState(maxOf(0, sel - 2)) }
    val rowPx = with(LocalDensity.current) { Config.ROW_CHANNEL_DP.dp.roundToPx() }
    KeepVisible(listState, sel, rowPx, channels) { nav.channelsPerPage = it }

    Column(modifier.background(Ui.Shade, RoundedCornerShape(10.dp)).padding(vertical = 8.dp, horizontal = 4.dp)) {
        if (backTitle.isNotEmpty()) {
            // Phone: the way back to the groups / Телефон: путь назад к группам
            Label(
                "< " + backTitle,
                modifier = Modifier.fillMaxWidth()
                    .taps(Unit, { nav.back() })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                color = Ui.Amber, size = 17, bold = true, maxLines = 1
            )
        } else {
            // Above the channels: the highlighted channel / Над каналами: выделенный канал
            ColumnHeader(channels.getOrNull(sel)?.name ?: "")
        }
        if (emptyText.isNotEmpty()) {
            Label(emptyText, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Ui.Muted, size = 16)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(channels) { i, ch ->
                val isPlaying = sameChannel(ch, playing)
                val look = rowLook(
                    onCursor = cursor && i == sel,
                    isPlaying = isPlaying,
                    focusedColumn = focused,
                    selectedHere = i == sel && !cursor && !focused
                )
                ListRow(
                    index = i, height = Config.ROW_CHANNEL_DP.dp, background = look.bg,
                    onTap = { nav.tapChannel(it) }, onHold = { nav.holdChannel(it) }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Number: a column for 4 digits, aligned right / Номер: колонка под 4 цифры, вправо
                        Label(
                            ch.n.toString(), modifier = Modifier.width(46.dp),
                            color = if (look.fg == Ui.Ink) Ui.Ink else Ui.Muted, size = 15, maxLines = 1, align = TextAlign.End
                        )
                        Spacer(Modifier.width(10.dp))
                        Logo(ch.logo, 48.dp, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        // The second line (current programme) comes with the TV guide.
                        // Вторая строка (текущая передача) придёт вместе с программой передач.
                        Label(ch.name, color = look.fg, size = 18, bold = isPlaying, maxLines = 1)
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

// ---------- settings / настройки ----------

@Composable
private fun SettingsPanel(modifier: Modifier, page: SettingsPage, tick: Int, isTv: Boolean, act: UiActions) {
    if (tick < 0) return
    val rows = page.rows
    val sel = page.sel
    val log = page.page == SetPage.LOG
    val rowDp = if (log) 30 else Config.ROW_GROUP_DP + 8
    val listState = remember(page) { LazyListState(maxOf(0, sel - 2)) }
    val rowPx = with(LocalDensity.current) { rowDp.dp.roundToPx() }
    KeepVisible(listState, sel, rowPx, page) { page.perPage = it }

    Column(modifier.background(Ui.Shade, RoundedCornerShape(10.dp)).padding(vertical = 10.dp, horizontal = 6.dp)) {
        Label(
            (if (isTv) "" else "< ") + page.title,
            modifier = Modifier.fillMaxWidth().taps(page, act.onSettingsBack).padding(horizontal = 12.dp, vertical = 4.dp),
            color = Ui.Amber, size = 20, bold = true, maxLines = 1
        )
        if (page.sub.isNotEmpty()) {
            Label(page.sub, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = Ui.Muted, size = 15)
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(rows) { i, row ->
                val on = i == sel
                ListRow(
                    index = i, height = rowDp.dp,
                    background = if (on) Ui.Amber else Color.Transparent,
                    onTap = act.onSettingsTap, onHold = act.onSettingsTap
                ) {
                    if (log) {
                        Label(row.label, color = if (on) Ui.Ink else Ui.Text, size = 13, maxLines = 1, mono = true)
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Label(row.label, modifier = Modifier.weight(1f), color = if (on) Ui.Ink else Ui.Text, size = 18, maxLines = 1)
                            if (row.value.isNotEmpty()) {
                                Spacer(Modifier.width(12.dp))
                                Label(
                                    row.value, modifier = Modifier.widthIn(max = 520.dp),
                                    color = if (on) Ui.Ink else Ui.Muted, size = 16, maxLines = 1, align = TextAlign.End
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- our windows / наши окна ----------

// A plain Column, not a lazy list: a lazy list keeps its rows when the Popup object is the
// same, and the cursor would not move (the menus have few items anyway).
// Обычный Column, а не ленивый список: ленивый список сохраняет строки, если объект Popup тот
// же, и курсор бы не двигался (пунктов в меню всё равно немного).
@Composable
private fun PopupWindow(p: Popup, tick: Int, onTap: (Int) -> Unit, onOutside: () -> Unit) {
    if (tick < 0) return
    val sel = p.sel
    Box(Modifier.fillMaxSize().background(Ui.Dim).taps(p, onOutside), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 680.dp).padding(24.dp)
                .background(Ui.Panel, RoundedCornerShape(14.dp))
                .taps("inside", {})                       // taps inside do not close / нажатия внутри не закрывают
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            if (p.title.isNotEmpty()) Label(p.title, size = 21, bold = true, maxLines = 2)
            if (p.message.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Label(p.message, color = Ui.Muted, size = 16)
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                for (i in p.items.indices) {
                    val it = p.items[i]
                    val on = i == sel
                    ListRow(index = i, height = 46.dp, background = if (on) Ui.Amber else Color.Transparent, onTap = onTap, onHold = onTap) {
                        Label((if (it.mark) "* " else "") + it.label, color = if (on) Ui.Ink else Ui.Text, size = 18, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PinWindow(p: PinPad, tick: Int, onTap: (Int) -> Unit, onOutside: () -> Unit) {
    if (tick < 0) return
    Box(Modifier.fillMaxSize().background(Ui.Dim).taps(p, onOutside), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(16.dp)
                .background(Ui.Panel, RoundedCornerShape(14.dp))
                .taps("inside", {})
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Label(p.title, size = 21, bold = true, maxLines = 1)
            if (p.message.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Label(
                    p.message, modifier = Modifier.widthIn(max = 420.dp),
                    color = if (p.error) Ui.Error else Ui.Muted, size = 16, align = TextAlign.Center
                )
            }
            Spacer(Modifier.height(10.dp))
            // Entered digits are shown as stars / Введённые цифры показаны звёздочками
            val shownDigits = (0 until 4).joinToString(" ") { if (it < p.digits.length) "*" else "_" }
            Label(shownDigits, color = Ui.Amber, size = 32, bold = true, mono = true)
            Spacer(Modifier.height(10.dp))
            for (r in 0 until 4) {
                Row {
                    for (c in 0 until 3) {
                        val i = r * 3 + c
                        val on = i == p.sel
                        Box(
                            Modifier.padding(4.dp).size(104.dp, 58.dp)
                                .background(if (on) Ui.Amber else Ui.Bg, RoundedCornerShape(8.dp))
                                .taps(i, { onTap(i) }),
                            contentAlignment = Alignment.Center
                        ) {
                            Label(p.label(i), color = if (on) Ui.Ink else Ui.Text, size = 22, bold = true, maxLines = 1)
                        }
                    }
                }
            }
            if (p.hint.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Label(p.hint, color = Ui.Muted, size = 15, align = TextAlign.Center)
            }
        }
    }
}

// ---------- video / видео ----------

@Composable
private fun VideoLayer(exo: Player, modifier: Modifier, swipes: Boolean, onTap: () -> Unit, onSwipe: (Int) -> Unit) {
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    val minSwipePx = with(LocalDensity.current) { Config.SWIPE_DP.dp.toPx() }
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
        // Transparent layer over the video for taps and, in full screen, vertical swipes:
        // a swipe up = the next channel of the list, a swipe down = the previous one.
        // Прозрачный слой поверх видео для нажатий и, на полном экране, вертикальных жестов:
        // жест вверх = следующий канал списка, жест вниз = предыдущий.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }
                .pointerInput(swipes) {
                    if (swipes) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total <= -minSwipePx) swipe(1) else if (total >= minSwipePx) swipe(-1)
                            },
                            onVerticalDrag = { _, dragAmount -> total += dragAmount }
                        )
                    }
                }
        )
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
