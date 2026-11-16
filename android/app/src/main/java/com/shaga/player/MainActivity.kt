// ShaGa IPTV Player for Android — MainActivity.kt
// The only window of the app: start, remote keys (short and long OK, digits), Back, our windows
// (menu, PIN pad, Settings), text input (link, search), playlists and their copies, group
// protection, favourites, the player.
// Единственное окно приложения: запуск, кнопки пульта (короткое и долгое OK, цифры), Back, свои
// окна (меню, клавиатура PIN, Настройки), ввод текста (ссылка, поиск), плейлисты и их копии,
// защита групп, избранное, плеер.
package com.shaga.player

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), NavHost {
    private val ui = UiState()
    private lateinit var nav: Nav
    private lateinit var engine: PlayerEngine
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())

    private var input: AlertDialog? = null          // the system text input window / системное окно ввода текста
    private var inputShownAt = 0L
    private var barsHidden = false

    private var playlist: Playlist? = null          // the channels on the screen / каналы на экране
    private var currentId = 0                       // its id in Storage / его id в Storage
    private var loadSeq = 0                         // the latest load; older answers are ignored
                                                    // последняя загрузка; устаревшие ответы игнорируются
    private var searchQuery = ""                     // the last search / последний поиск
    private var searchResult: List<Channel>? = null  // null = there was no search / поиска не было

    private var okDown = false       // OK is being held (pressed in our window) / OK удерживается (нажат в нашем окне)
    private var okLong = false       // the long press has already worked / долгое нажатие уже сработало
    private var settingsPinOk = false // the PIN was entered once in this visit of Settings / PIN введён в этот заход в Настройки

    // Background update of the playlist after the start of the video.
    // Фоновое обновление плейлиста после старта видео.
    private var refreshWanted = 0                    // id of the playlist to update / id плейлиста для обновления
    private val numberCommit = Runnable { commitNumber() }
    private val refreshNow = Runnable { backgroundRefresh() }
    private val lockCheck = object : Runnable {
        override fun run() {
            checkLock()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark system bars with light icons; the app draws edge to edge (required on Android 15+).
        // Тёмные системные панели со светлыми значками; приложение рисует «от края до края»
        // (обязательно на Android 15+).
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))

        ui.isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        Storage.load(this)
        S.init(Storage.lang, Locale.getDefault().language)
        ui.lang = S.lang

        // The first line of the log is the name and the version.
        // Первая строка журнала — название и версия.
        AppLog.start(BuildConfig.VERSION_NAME)
        val dm = resources.displayMetrics
        AppLog.i("APP", "device " + Build.MANUFACTURER + " " + Build.MODEL +
            ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " +
            (if (ui.isTv) "TV" else "touch") + ", screen " + dm.widthPixels + "x" + dm.heightPixels +
            " density " + dm.density + ", language " + Locale.getDefault().language + " -> " + S.lang +
            ", playlists " + Storage.playlists.size + ", PIN " + (if (Protection.hasPin()) "set" else "none"))

        nav = Nav(this, startInKeyMode = ui.isTv)
        nav.zapInvert = Storage.zapInvert
        engine = PlayerEngine(this) { state -> onPlayState(state) }

        // Back (remote key or gesture) goes by steps; our windows close first.
        // Back (кнопка пульта или жест) идёт по шагам; сначала закрываются наши окна.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AppLog.i("KEY", "BACK " + where())
                onBack()
            }
        })

        val saved = Storage.current()
        if (saved == null) {
            AppLog.i("APP", "no playlists: welcome screen")
            ui.screen = Screen.WELCOME
        } else {
            openSaved(saved, startup = true)
        }

        setContent {
            AppRoot(
                ui, nav, engine.exo,
                UiActions(
                    onWelcome = { addPlaylistDialog() },
                    onPopupTap = { i -> ui.popup?.let { p -> p.select(i); pickPopup(p) } },
                    onPopupOutside = { closePopup(true) },
                    onPinTap = { i -> ui.pin?.let { p -> p.press(i); refresh() } },
                    onPinOutside = { closePin(true) },
                    onSettingsTap = { i -> ui.settings?.let { p -> p.select(i); settingsOk(p) } },
                    onSettingsBack = { settingsBack() }
                )
            )
        }
        handler.postDelayed(lockCheck, 30_000L)
    }

    private fun refresh() {
        ui.tick++
    }

    private fun where(): String = when {
        ui.pin != null -> "pin"
        ui.popup != null -> "popup"
        ui.settings != null -> "settings"
        ui.screen == Screen.MAIN -> nav.place()
        else -> ui.screen.name.lowercase()
    }

    // ---------- keys / кнопки ----------

    private fun navKey(code: Int): NavKey? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> NavKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> NavKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> NavKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> NavKey.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> NavKey.OK
        KeyEvent.KEYCODE_PAGE_UP -> NavKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> NavKey.PAGE_DOWN
        KeyEvent.KEYCODE_CHANNEL_UP -> NavKey.CH_UP
        KeyEvent.KEYCODE_CHANNEL_DOWN -> NavKey.CH_DOWN
        else -> null
    }

    private fun digitOf(code: Int): Int = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
        else -> -1
    }

    // Every key press goes to the log with its Android name and the place of the cursor. Digits
    // are not written (a PIN code is typed with them). Text typed in the input window does not
    // come here at all. Back goes to onBack() through the system.
    // Каждое нажатие пишется в журнал с именем кнопки Android и местом курсора. Цифры не
    // пишутся (ими вводится PIN-код). Текст из окна ввода сюда вообще не попадает. Back идёт
    // в onBack() через систему.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        val first = down && event.repeatCount == 0
        val digit = digitOf(code)
        if (first && code != KeyEvent.KEYCODE_BACK) {
            val name = if (digit >= 0) "DIGIT" else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            AppLog.i("KEY", name + " " + where())
        }
        val k = navKey(code)

        // Our windows: keys work when pressed / Наши окна: кнопки срабатывают при нажатии
        val pin = ui.pin
        val popup = ui.popup
        val settings = ui.settings
        if (pin != null || popup != null || settings != null) {
            if (digit < 0 && k == null) return super.dispatchKeyEvent(event)
            okDown = false
            if (!down) return true
            when {
                pin != null -> pinKey(pin, k, digit, first)
                popup != null -> if (k != null) popupKey(popup, k, first)
                settings != null -> if (k != null) settingsKey(settings, k, event.repeatCount > 0, first)
            }
            return true
        }

        // INFO and MENU keys of a remote open the context menu, like a long OK.
        // Кнопки INFO и MENU пульта открывают контекстное меню, как долгое OK.
        if ((code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_MENU) && ui.screen == Screen.MAIN) {
            if (first) longOk()
            return true
        }

        // Digits: the channel number / Цифры: номер канала
        if (digit >= 0 && ui.screen == Screen.MAIN) {
            if (first) pressDigit(digit)
            return true
        }
        if (k == null) return super.dispatchKeyEvent(event)

        // OK: a short press works when the key is released; held for LONG_PRESS_MS it is a long
        // press (context menu) and the release does nothing. A release without our own press
        // (the press was made in a window that has just closed) is ignored.
        // OK: короткое нажатие срабатывает при отпускании; удержание LONG_PRESS_MS — долгое
        // нажатие (контекстное меню), и отпускание уже ничего не делает. Отпускание без нашего
        // нажатия (нажали в окне, которое только что закрылось) игнорируется.
        if (k == NavKey.OK) {
            val held = event.eventTime - event.downTime
            if (down) {
                if (event.repeatCount == 0) {
                    okDown = true
                    okLong = false
                } else if (okDown && !okLong && held >= Config.LONG_PRESS_MS) {
                    okLong = true
                    longOk()
                }
            } else if (up && okDown) {
                okDown = false
                if (!okLong) {
                    if (held >= Config.LONG_PRESS_MS) longOk() else shortOk()
                }
            }
            return true
        }

        if (down && ui.screen == Screen.MAIN) {
            clearNumber()
            nav.key(k, event.repeatCount > 0)
        }
        return true
    }

    private fun shortOk() {
        when (ui.screen) {
            Screen.WELCOME -> addPlaylistDialog()
            Screen.MAIN -> if (ui.numBuf.isNotEmpty()) commitNumber() else nav.key(NavKey.OK, false)
            Screen.LOADING -> {}
        }
    }

    private fun longOk() {
        if (ui.screen != Screen.MAIN) return
        clearNumber()
        AppLog.i("KEY", "long OK " + nav.place())
        nav.longOk()
    }

    private fun onBack() {
        when {
            ui.pin != null -> closePin(true)
            ui.popup != null -> closePopup(true)
            ui.settings != null -> settingsBack()
            ui.numBuf.isNotEmpty() -> clearNumber()
            ui.screen == Screen.MAIN -> nav.back()
            else -> finish()              // welcome or loading: Back leaves / приветствие или загрузка: выход
        }
    }

    // ---------- our windows / наши окна ----------

    private fun showPopup(p: Popup) {
        AppLog.i("UI", "window open: " + p.what)
        ui.popup = p
        refresh()
    }

    private fun closePopup(cancelled: Boolean) {
        val p = ui.popup ?: return
        ui.popup = null
        refresh()
        if (cancelled) {
            AppLog.i("UI", "window close: " + p.what)
            p.onCancel()
        }
    }

    private fun pickPopup(p: Popup) {
        AppLog.i("UI", "window " + p.what + ": item " + (p.sel + 1))
        ui.popup = null
        refresh()
        p.onPick(p.sel)
    }

    private fun popupKey(p: Popup, k: NavKey, first: Boolean) {
        when (k) {
            NavKey.UP -> p.move(-1)
            NavKey.DOWN -> p.move(1)
            NavKey.OK -> if (first) { pickPopup(p); return }
            else -> {}
        }
        refresh()
    }

    // A message with one button / Сообщение с одной кнопкой
    private fun showMessage(what: String, title: String, text: String) {
        showPopup(Popup(what, title, text, listOf(PopItem(S.t("ok"))), 0, {}))
    }

    // A question: OK = yes (the cursor is on it), Cancel / Вопрос: OK = да (курсор на нём), Отмена
    private fun ask(what: String, title: String, text: String, yes: String, onYes: () -> Unit) {
        showPopup(Popup(what, title, text, listOf(PopItem(yes), PopItem(S.t("cancel"))), 0, { if (it == 0) onYes() }))
    }

    private fun closePin(cancelled: Boolean) {
        val p = ui.pin ?: return
        ui.pin = null
        refresh()
        if (cancelled) {
            AppLog.i("UI", "window close: " + p.what)
            p.onCancel()
        }
    }

    private fun pinKey(p: PinPad, k: NavKey?, digit: Int, first: Boolean) {
        if (digit >= 0) {
            if (first) p.digit(digit)
        } else {
            when (k) {
                NavKey.UP -> p.move(0, -1)
                NavKey.DOWN -> p.move(0, 1)
                NavKey.LEFT -> p.move(-1, 0)
                NavKey.RIGHT -> p.move(1, 0)
                NavKey.OK -> if (first) p.press(p.sel)
                else -> {}
            }
        }
        refresh()
    }

    // ---------- PIN code / PIN-код ----------

    // Asks the existing PIN. The master code leads to "Reset PIN?". onOk gets the PIN entered.
    // Спрашивает заданный PIN. Мастер-код ведёт к «Сбросить PIN-код?».
    private fun askPin(what: String, onOk: () -> Unit, onCancel: () -> Unit = {}) {
        AppLog.i("UI", "window open: pin $what")
        ui.pin = PinPad("pin $what", S.t("pinTitle"), S.t("pinEnter"), S.t("pinEnterHint"), { code ->
            val pad = ui.pin
            when {
                Protection.checkPin(code) -> {
                    AppLog.i("UI", "pin: correct")
                    closePin(false)
                    onOk()
                }
                Protection.isMaster(code) -> {
                    AppLog.i("UI", "pin: master code")
                    closePin(false)
                    ask("reset pin", S.t("pinResetTitle"), S.t("pinResetMsg"), S.t("pinReset")) { removePin("master code") }
                }
                else -> {
                    AppLog.i("UI", "pin: wrong")
                    pad?.reset(S.t("pinWrong"), true)
                    refresh()
                }
            }
        }, onCancel)
        refresh()
    }

    // Creates a new PIN: 4 digits, then the same 4 again. message = why it is needed.
    // Создаёт новый PIN: 4 цифры, затем те же 4 ещё раз. message = зачем он нужен.
    private fun createPin(message: String, onDone: () -> Unit) {
        var firstCode = ""
        AppLog.i("UI", "window open: pin new")
        val start = if (message.isEmpty()) S.t("pinNew") else message
        ui.pin = PinPad("pin new", S.t("pinNew"), start, "", { code ->
            val pad = ui.pin
            if (firstCode.isEmpty()) {
                firstCode = code
                pad?.reset(S.t("pinRepeat"), false)
            } else if (code == firstCode) {
                Protection.setPin(code)
                Storage.save(this)
                AppLog.i("UI", "pin: saved")
                closePin(false)
                toast(S.t("pinSaved"))
                onDone()
                updateSettingsPage()
            } else {
                AppLog.i("UI", "pin: repeat does not match")
                firstCode = ""
                pad?.reset(S.t("pinMismatch"), true)
            }
            refresh()
        }, {})
        refresh()
    }

    // Deleting or resetting the PIN: groups "Locked with PIN" become hidden.
    // Удаление или сброс PIN: группы «По PIN-коду» становятся скрытыми.
    private fun removePin(why: String) {
        Protection.removePin()
        Storage.save(this)
        AppLog.i("UI", "pin: removed ($why)")
        toast(S.t("pinRemoved"))
        afterProtectionChange()
    }

    // ---------- group protection / защита групп ----------

    private fun modeText(m: GroupMode): String = S.t(
        when (m) {
            GroupMode.SHOWN -> "gsVisible"
            GroupMode.PIN -> "gsLocked"
            GroupMode.HIDDEN -> "gsHidden"
        }
    )

    // Weaker protection (Hidden -> anything, Locked -> Shown) needs the PIN once per visit of
    // Settings; without a PIN it must be created first. "Locked" without a PIN also creates one.
    // Ослабление защиты (Скрыта -> любое, По PIN -> Показана) требует PIN один раз за заход в
    // Настройки; без PIN его сначала надо создать. «По PIN» без PIN тоже сначала создаёт его.
    private fun setGroupMode(g: String, next: GroupMode, after: () -> Unit = {}) {
        val cur = Protection.mode(g)
        if (next == cur) {
            after()
            return
        }
        val weaker = cur == GroupMode.HIDDEN || (cur == GroupMode.PIN && next == GroupMode.SHOWN)
        val apply = {
            Protection.modes[g] = next
            if (Protection.openGroup == g) Protection.close()
            Storage.save(this)
            AppLog.i("UI", "group mode: " + cur.code + " -> " + next.code)
            afterProtectionChange()
            after()
        }
        guardWeaker(weaker, next == GroupMode.PIN, apply)
    }

    private fun guardWeaker(weaker: Boolean, needsPin: Boolean, apply: () -> Unit) {
        when {
            !Protection.hasPin() && (weaker || needsPin) ->
                createPin(if (weaker) S.t("pinCreateFirst") else "") { settingsPinOk = true; apply() }
            weaker && !settingsPinOk -> askPin("weaker", { settingsPinOk = true; apply() })
            else -> apply()
        }
    }

    // The "18+ group" mark: putting it on needs nothing, taking it off is like weaker protection.
    // Отметка «Группа 18+»: поставить можно без PIN, снять — как ослабление защиты.
    private fun setAdult(g: String, on: Boolean, after: () -> Unit = {}) {
        val apply = {
            Protection.adult[g] = on
            Storage.save(this)
            AppLog.i("UI", "group 18+ mark: $on")
            afterProtectionChange()
            after()
        }
        if (on) apply() else guardWeaker(true, false, apply)
    }

    // After any change of protection: favourites are checked, the rows rebuilt, a channel that
    // can no longer be watched stops, the Settings page is redrawn.
    // После любого изменения защиты: избранное сверяется, строки пересобираются, канал, который
    // больше нельзя смотреть, останавливается, страница Настроек перерисовывается.
    private fun afterProtectionChange() {
        purgeFavorites()
        val p = nav.playing
        if (p != null && !Protection.viewable(p.group)) {
            AppLog.i("PLAY", "stopped: the group is closed now")
            nav.stopPlayback()
        }
        rebuildRows(reset = false)
        updateSettingsPage()
    }

    // Channels of groups with the 18+ mark never stay in the favourites (SPEC, section 7).
    // Каналы групп с отметкой 18+ в избранном не остаются (SPEC, раздел 7).
    private fun purgeFavorites() {
        val adult = Catalog.adultNames(playlist)
        if (adult.isEmpty()) return
        val before = Storage.favorites.size
        Storage.favorites.removeAll { it in adult }
        val removed = before - Storage.favorites.size
        if (removed > 0) {
            Storage.save(this)
            AppLog.i("UI", "favourites: $removed removed (18+ groups)")
        }
    }

    override fun openLockedGroup(cat: Category) {
        val g = cat.group ?: return
        askPin("group", {
            Protection.open(g, System.currentTimeMillis())
            AppLog.i("UI", "pin: group opened")
            rebuildRows(reset = false)
            nav.openCategory(cat.id, null)
        })
    }

    // The flag "PIN entered" is dropped after 10 minutes with nothing of its group playing.
    // Флажок «PIN введён» сбрасывается через 10 минут, если ничего из его группы не играет.
    private fun checkLock() {
        val p = nav.playing
        val playingGroup = if (p != null && ui.playState != PlayState.IDLE) p.group else null
        if (Protection.tick(System.currentTimeMillis(), playingGroup)) {
            AppLog.i("UI", "pin: group closed after 10 minutes")
            rebuildRows(reset = false)
        }
    }

    // Long OK on a group: lock it with the PIN or hide it (stronger protection, no PIN needed).
    // Долгое OK на группе: закрыть PIN-кодом или скрыть (усиление, PIN не нужен).
    override fun openGroupMenu(cat: Category) {
        val g = cat.group ?: return
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (Protection.mode(g) == GroupMode.SHOWN) acts.add(S.t("ctxLock") to { setGroupMode(g, GroupMode.PIN) })
        acts.add(S.t("ctxHide") to { setGroupMode(g, GroupMode.HIDDEN) })
        acts.add(S.t("cancel") to {})
        showPopup(Popup("group menu", cat.title, "", acts.map { PopItem(it.first) }, 0, { acts[it].second() }))
    }

    // ---------- channel number / номер канала ----------

    private fun pressDigit(d: Int) {
        if (playlist == null) return
        var buf = ui.numBuf
        if (buf.length >= 4) buf = ""
        ui.numBuf = buf + d
        handler.removeCallbacks(numberCommit)
        handler.postDelayed(numberCommit, Config.NUMBER_COMMIT_MS)
    }

    private fun clearNumber() {
        handler.removeCallbacks(numberCommit)
        if (ui.numBuf.isNotEmpty()) ui.numBuf = ""
    }

    private fun commitNumber() {
        val n = ui.numBuf.toIntOrNull() ?: 0
        clearNumber()
        if (n <= 0 || ui.screen != Screen.MAIN) return
        if (nav.playNumber(n)) {
            AppLog.i("UI", "channel number: found")
        } else {
            AppLog.i("UI", "channel number: no such channel")
            toast(S.t("noChannel").replace("{n}", n.toString()))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------- NavHost: what the navigation asks for / что просит навигация ----------

    override fun navChanged() {
        refresh()
        // Full screen on a phone: the system bars are hidden (a swipe from the edge shows them).
        // Полный экран на телефоне: системные панели скрыты (жест от края показывает их).
        val hide = nav.fullscreen
        if (hide != barsHidden) {
            barsHidden = hide
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            if (hide) {
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                bars.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // A channel starts. The flag "PIN entered" is dropped when the channel is not from its
    // group; the last channel is remembered.
    // Канал включается. Флажок «PIN введён» сбрасывается, если канал не из его группы;
    // последний канал запоминается.
    override fun startChannel(ch: Channel) {
        engine.play(ch)
        val open = Protection.openGroup
        if (open != null && ch.group != open) {
            Protection.close()
            AppLog.i("UI", "pin: group closed, a channel of another group started")
            // After Nav has finished its step / После того как Nav закончит свой шаг
            handler.post { rebuildRows(reset = false) }
        }
        Storage.lastName = ch.name
        Storage.lastGroup = ch.group
        Storage.lastCat = nav.playCat?.id ?: Catalog.ALL
        Storage.save(this)
    }

    override fun stopChannel() {
        engine.stop()
        Protection.touch(System.currentTimeMillis())
    }

    private fun onPlayState(state: PlayState) {
        ui.playState = state
        nav.playFailed = state == PlayState.ERROR
        // The playlist is updated in the background once the picture has appeared.
        // Плейлист обновляется в фоне, когда появилась картинка.
        if (state == PlayState.PLAYING && refreshWanted != 0) {
            handler.removeCallbacks(refreshNow)
            handler.postDelayed(refreshNow, 1_500L)
        }
    }

    // ---------- left column rows / строки левой колонки ----------

    // reset = true for a new playlist; false keeps the cursor (language, favourites, search, modes).
    // reset = true для нового плейлиста; false сохраняет курсор (язык, избранное, поиск, режимы).
    private fun rebuildRows(reset: Boolean) {
        nav.setRows(Catalog.rows(playlist, Storage.favorites, searchQuery, searchResult) { key -> S.t(key) }, reset)
    }

    // ---------- text input / ввод текста ----------

    // A system window with one text field: the remote and the on-screen keyboard work in it.
    // One press of OK / "Done" could work twice (in the closing window and in the one that opens
    // after it), so for a short time after opening the window ignores OK.
    // Системное окно с одним полем ввода: в нём работают пульт и экранная клавиатура. Одно нажатие
    // OK / «Готово» могло сработать дважды (в закрывающемся окне и в том, что открылось следом),
    // поэтому короткое время после открытия окно не принимает OK.
    private fun showInput(
        what: String, title: String, message: String, text: String, hint: String,
        isLink: Boolean, onSubmit: (String) -> Unit
    ) {
        input?.dismiss()
        AppLog.i("UI", "input open: $what")
        val field = EditText(this)
        field.setSingleLine(true)
        field.inputType = if (isLink) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        else InputType.TYPE_CLASS_TEXT
        field.imeOptions = if (isLink) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_SEARCH
        field.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(if (isLink) TextUtil.MAX_URL else 100))
        field.hint = hint
        field.setText(text)
        field.setSelection(field.text.length)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this)
        box.setPadding(pad, 0, pad, 0)
        box.addView(field)

        val d = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(box)
            .setPositiveButton(S.t("ok"), null)      // the click is set below / нажатие задаётся ниже
            .setNegativeButton(S.t("cancel")) { _, _ -> AppLog.i("UI", "input close: cancel") }
            .setOnCancelListener { AppLog.i("UI", "input close: back") }
            .create()
        val submit = {
            if (SystemClock.elapsedRealtime() - inputShownAt >= Config.DIALOG_GUARD_MS) {
                d.dismiss()
                onSubmit(field.text.toString())
            } else {
                AppLog.i("UI", "input: early OK ignored")
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else {
                false
            }
        }
        input = d
        inputShownAt = SystemClock.elapsedRealtime()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit() }
    }

    // editId = 0: a new playlist; else the link of that playlist is changed.
    // On an error the window opens again with the same text and the error.
    // editId = 0: новый плейлист; иначе меняется ссылка этого плейлиста.
    // При ошибке окно открывается снова с тем же текстом и ошибкой.
    private fun showUrlDialog(editId: Int, text: String, error: String) {
        var message = S.t("urlMsg")
        if (ui.isTv) message += "\n\n" + S.t("hintKeyboard")
        if (error.isNotEmpty()) message = error + "\n\n" + message
        showInput(
            what = (if (editId == 0) "new link" else "edit link") + (if (error.isNotEmpty()) " (with error)" else ""),
            title = S.t(if (editId == 0) "urlTitle" else "editTitle"),
            message = message, text = text, hint = S.t("urlPlaceholder"), isLink = true
        ) { raw -> submitUrl(editId, raw) }
    }

    private fun addPlaylistDialog() {
        if (Storage.playlists.size >= Storage.MAX_PLAYLISTS) {
            showMessage("limit", S.t("playlistsTitle"), S.t("limitReached").replace("{n}", Storage.MAX_PLAYLISTS.toString()))
            return
        }
        showUrlDialog(0, "", "")
    }

    private fun submitUrl(editId: Int, raw: String) {
        val url = TextUtil.normalizeUrl(raw)
        if (url.isEmpty()) {
            AppLog.i("UI", "input: empty")
            return
        }
        if (!TextUtil.isValidUrl(url)) {
            AppLog.i("UI", "input: not a web link")
            showUrlDialog(editId, raw, S.t("invalidUrl"))
            return
        }
        // A link that is already saved opens that playlist / Уже сохранённая ссылка открывает тот плейлист
        val dup = Storage.byUrl(url)
        if (dup != null && dup.id != editId) {
            AppLog.i("UI", "input: the playlist is already saved")
            toast(S.t("dupOpened"))
            openSaved(dup, startup = false)
            return
        }
        if (dup != null && dup.id == editId && dup.id == currentId) return
        loadFromInput(editId, url)
    }

    // A typed link: saved only after a successful load; on an error the window opens again and
    // the old channel list stays.
    // Введённая ссылка: сохраняется только после успешной загрузки; при ошибке окно открывается
    // снова, а прежний список каналов остаётся.
    private fun loadFromInput(editId: Int, url: String) {
        val seq = ++loadSeq
        ui.busy = S.t("loading")
        scope.launch {
            var result: Loaded? = null
            var error: LoadError? = null
            withContext(Dispatchers.IO) {
                try {
                    result = PlaylistLoader.load(url)
                } catch (e: LoadError) {
                    error = e
                } catch (e: Exception) {
                    AppLog.e("ERR", "playlist: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
                    error = LoadError("network")
                }
            }
            if (seq != loadSeq || isFinishing) return@launch
            ui.busy = ""
            val r = result
            if (r == null) {
                showUrlDialog(editId, url, errorText(error))
                return@launch
            }
            val p: SavedPlaylist
            val old = if (editId != 0) Storage.byId(editId) else null
            if (old != null) {
                old.url = url
                old.name = TextUtil.playlistName(url)
                p = old
                AppLog.i("UI", "playlist link changed")
            } else {
                p = Storage.add(url)
                AppLog.i("UI", "playlist added: " + Storage.playlists.size + " saved")
            }
            Storage.writeCache(this@MainActivity, p.id, r.text)
            showPlaylist(p, r.playlist, startup = false)
            ui.stale = false
        }
    }

    // ---------- playlists / плейлисты ----------

    // A saved playlist: the copy is shown at once and the list is updated in the background
    // after the video starts; without a copy it is loaded from the network.
    // Сохранённый плейлист: копия показывается сразу, а список обновляется в фоне после старта
    // видео; без копии он загружается из сети.
    private fun openSaved(p: SavedPlaylist, startup: Boolean) {
        val seq = ++loadSeq
        if (ui.screen == Screen.MAIN) nav.stopPlayback()
        if (ui.screen != Screen.MAIN) ui.screen = Screen.LOADING else ui.busy = S.t("loading")
        scope.launch {
            var copy: Playlist? = null
            withContext(Dispatchers.IO) {
                val text = Storage.readCache(this@MainActivity, p.id)
                if (text != null) copy = PlaylistLoader.parse(text, "copy")
            }
            if (seq != loadSeq || isFinishing) return@launch
            val c = copy
            if (c != null && c.channels.isNotEmpty()) {
                ui.busy = ""
                ui.stale = false
                showPlaylist(p, c, startup)
                // The fresh list is loaded after the video starts, or soon if nothing plays.
                // Свежий список грузится после старта видео или скоро, если ничего не играет.
                refreshWanted = p.id
                handler.removeCallbacks(refreshNow)
                handler.postDelayed(refreshNow, if (nav.playing != null) 15_000L else 1_500L)
                return@launch
            }

            var result: Loaded? = null
            var error: LoadError? = null
            withContext(Dispatchers.IO) {
                try {
                    result = PlaylistLoader.load(p.url)
                } catch (e: LoadError) {
                    error = e
                } catch (e: Exception) {
                    AppLog.e("ERR", "playlist: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
                    error = LoadError("network")
                }
            }
            if (seq != loadSeq || isFinishing) return@launch
            ui.busy = ""
            val r = result
            if (r != null) {
                Storage.writeCache(this@MainActivity, p.id, r.text)
                ui.stale = false
                showPlaylist(p, r.playlist, startup)
            } else if (startup || playlist == null) {
                // No copy and no network: the error text in the channel column, the cursor on
                // "> Playlists", no window.
                // Нет ни копии, ни сети: текст ошибки в колонке каналов, курсор на «> Плейлисты»,
                // без окна.
                currentId = p.id
                playlist = null
                searchQuery = ""
                searchResult = null
                ui.playlistName = p.name
                ui.listError = errorText(error)
                rebuildRows(reset = true)
                ui.screen = Screen.MAIN
            } else {
                showMessage("playlist error", S.t("errTitle"), errorText(error))
            }
        }
    }

    // A new channel list on the screen / Новый список каналов на экране
    private fun showPlaylist(p: SavedPlaylist, pl: Playlist, startup: Boolean) {
        val samePlaylist = p.id == currentId && playlist != null
        if (!samePlaylist) nav.stopPlayback()
        currentId = p.id
        Storage.lastOkId = p.id
        playlist = pl
        searchQuery = ""
        searchResult = null
        if (Protection.noteGroups(Catalog.groupsOf(pl).keys)) AppLog.i("UI", "new groups checked for 18+")
        Storage.save(this)
        purgeFavorites()
        ui.playlistName = p.name
        ui.listError = ""
        rebuildRows(reset = true)
        ui.screen = Screen.MAIN
        if (startup) autoStartLast()
    }

    // The last channel starts if its group is Shown and has no 18+ mark; otherwise the cursor
    // stays on "All channels", channel 1.
    // Последний канал включается, если его группа «Показана» и без отметки 18+; иначе курсор
    // остаётся на «Все каналы», канал 1.
    private fun autoStartLast() {
        if (Storage.lastName.isEmpty()) return
        if (!Protection.general(Storage.lastGroup)) {
            AppLog.i("APP", "last channel: not started (its group is hidden, locked or 18+)")
            return
        }
        if (nav.autoStart(Storage.lastCat, Storage.lastName, Storage.lastGroup)) {
            AppLog.i("APP", "last channel: started")
        } else {
            AppLog.i("APP", "last channel: not in this playlist")
        }
    }

    // The fresh copy of the current playlist; the cursor and the playing channel stay.
    // Свежая копия текущего плейлиста; курсор и играющий канал остаются.
    private fun backgroundRefresh() {
        val id = refreshWanted
        refreshWanted = 0
        val p = Storage.byId(id) ?: return
        if (id != currentId) return
        updatePlaylist(p, manual = false)
    }

    private fun updatePlaylist(p: SavedPlaylist, manual: Boolean) {
        AppLog.i("LOAD", "playlist: update" + if (manual) " (manual)" else " (background)")
        scope.launch {
            var result: Loaded? = null
            var error: LoadError? = null
            withContext(Dispatchers.IO) {
                try {
                    result = PlaylistLoader.load(p.url)
                } catch (e: LoadError) {
                    error = e
                } catch (e: Exception) {
                    error = LoadError("network")
                }
            }
            if (isFinishing || p.id != currentId) return@launch
            val r = result
            if (r == null) {
                ui.stale = true
                AppLog.w("LOAD", "playlist: update failed, the saved copy stays")
                toast(if (manual) S.t("refreshFail").replace("{error}", errorText(error)) else S.t("staleNote"))
                updateSettingsPage()
                return@launch
            }
            ui.stale = false
            val old = Storage.readCache(this@MainActivity, p.id)
            Storage.writeCache(this@MainActivity, p.id, r.text)
            if (old != r.text) {
                playlist = r.playlist
                if (Protection.noteGroups(Catalog.groupsOf(r.playlist).keys)) Storage.save(this@MainActivity)
                purgeFavorites()
                // The search is made again on the new list / Поиск выполняется заново на новом списке
                val q = searchQuery
                if (searchResult != null) searchResult = findChannels(q)
                if (searchResult?.isEmpty() == true) { searchResult = null; searchQuery = "" }
                rebuildRows(reset = false)
                AppLog.i("LOAD", "playlist: updated, " + r.playlist.channels.size + " channels")
            } else {
                AppLog.i("LOAD", "playlist: no changes")
            }
            if (manual) toast(S.t("sRefreshed").replace("{count}", r.playlist.channels.size.toString()))
            updateSettingsPage()
        }
    }

    override fun openPlaylists() {
        val list = Storage.playlists
        val items = ArrayList<PopItem>()
        for (p in list) items.add(PopItem(p.name, p.id == currentId))
        items.add(PopItem(S.t("addPlaylist")))
        items.add(PopItem(S.t("close")))
        val cur = maxOf(0, list.indexOfFirst { it.id == currentId })
        showPopup(Popup("playlists", S.t("playlistsTitle"), S.t("playlistsMsg"), items, cur, { i ->
            when {
                i < list.size -> playlistActions(list[i])
                i == list.size -> addPlaylistDialog()
                else -> {}
            }
        }))
    }

    // Open / Change link / Delete (with a question) / Открыть / Изменить ссылку / Удалить (с вопросом)
    private fun playlistActions(p: SavedPlaylist) {
        val items = listOf(PopItem(S.t("open")), PopItem(S.t("editLink")), PopItem(S.t("del")), PopItem(S.t("cancel")))
        showPopup(Popup("playlist", p.name, "", items, 0, { i ->
            when (i) {
                0 -> if (p.id != currentId || playlist == null) openSaved(p, startup = false)
                1 -> showUrlDialog(p.id, p.url, "")
                2 -> ask("delete playlist", S.t("deleteTitle"), p.name + "\n\n" + S.t("deleteMsg"), S.t("del")) { deletePlaylist(p) }
                else -> {}
            }
        }))
    }

    // Deleting the current playlist stops the video and opens the first of the others;
    // with no playlists left — the welcome screen.
    // Удаление текущего плейлиста останавливает видео и открывает первый из оставшихся;
    // если плейлистов не осталось — приветствие.
    private fun deletePlaylist(p: SavedPlaylist) {
        val wasCurrent = p.id == currentId
        Storage.remove(this, p.id)
        Storage.save(this)
        AppLog.i("UI", "playlist deleted: " + Storage.playlists.size + " left")
        if (!wasCurrent) return
        nav.stopPlayback()
        playlist = null
        currentId = 0
        val next = Storage.current()
        if (next != null) {
            openSaved(next, startup = false)
        } else {
            loadSeq++
            searchQuery = ""
            searchResult = null
            ui.playlistName = ""
            ui.listError = ""
            rebuildRows(reset = true)
            ui.screen = Screen.WELCOME
        }
    }

    private fun errorText(e: LoadError?): String = when (e?.code) {
        "timeout" -> S.t("errTimeout")
        "http" -> S.t("errHttp").replace("{code}", (e?.status ?: 0).toString())
        "empty" -> S.t("errEmpty")
        else -> S.t("errNetwork")
    }

    // ---------- search / поиск ----------

    override fun openSearch() {
        if (playlist == null) return
        showInput(
            what = "search", title = S.t("searchTitle"), message = S.t("findMaskHint"),
            text = searchQuery, hint = "", isLink = false
        ) { raw -> runSearch(raw.trim()) }
    }

    // Masks * and ?, transliteration; hidden, locked and 18+ groups are not searched.
    // Маски * и ?, транслит; скрытые, закрытые и группы 18+ не ищутся.
    private fun findChannels(q: String): List<Channel> {
        val pl = playlist ?: return emptyList()
        val rx = TextUtil.findRegex(q) ?: return emptyList()
        return pl.channels.filter { Protection.general(it.group) && rx.containsMatchIn(it.name) }
    }

    // The results become the category "Search: ..." with the cursor on its first channel.
    // Nothing found = a window, the previous results stay.
    // Результаты становятся категорией «Поиск: ...» с курсором на первом канале.
    // Ничего не найдено = окно, прежние результаты остаются.
    private fun runSearch(q: String) {
        if (TextUtil.findRegex(q) == null) return
        val found = findChannels(q)
        AppLog.i("UI", "search: " + q.length + " characters -> " + found.size + " channels")
        if (found.isEmpty()) {
            showMessage("nothing found", S.t("searchTitle"), S.t("noResults").replace("{q}", q))
            return
        }
        searchQuery = q
        searchResult = found
        rebuildRows(reset = false)
        nav.openCategory(Catalog.SEARCH, null)
    }

    // ---------- language / язык ----------

    override fun openLanguage() {
        val codes = listOf("") + S.LANGS
        val items = codes.map { PopItem(if (it.isEmpty()) S.t("langAuto") else S.nameOf(it), it == Storage.lang) }
        showPopup(Popup("language", S.t("langTitle"), "", items, maxOf(0, codes.indexOf(Storage.lang)), { which ->
            Storage.lang = codes[which]
            Storage.save(this)
            S.init(Storage.lang, Locale.getDefault().language)
            AppLog.i("UI", "language: " + Storage.lang.ifEmpty { "auto" } + " -> " + S.lang)
            ui.lang = S.lang
            rebuildRows(reset = false)
        }))
    }

    // ---------- channel menu, favourites / меню канала, избранное ----------

    // Long OK (or touch and hold) on a channel. Channels of 18+ groups cannot be added to the
    // favourites; "Go to the group" only when its own list is not open and it can be viewed.
    // Долгое OK (или долгое нажатие) на канале. Каналы групп 18+ в избранное не добавляются;
    // «Перейти в группу» — только если открыт не её список и группу можно смотреть.
    override fun openChannelMenu(ch: Channel, ownGroup: Boolean) {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (ch.name in Storage.favorites) acts.add(S.t("ctxFavRemove") to { toggleFavorite(ch) })
        else if (Protection.general(ch.group)) acts.add(S.t("ctxFavAdd") to { toggleFavorite(ch) })
        if (!ownGroup && Protection.viewable(ch.group)) {
            acts.add(S.t("ctxGoGroup") to { nav.openCategory(Catalog.groupId(ch.group), ch) })
        }
        acts.add(S.t("cancel") to {})
        showPopup(Popup("channel menu", ch.name, "", acts.map { PopItem(it.first) }, 0, { acts[it].second() }))
    }

    private fun toggleFavorite(ch: Channel) {
        val fav = Storage.favorites
        if (fav.remove(ch.name)) {
            toast(S.t("favRemoved").replace("{name}", ch.name))
        } else if (fav.size >= Config.MAX_FAVORITES) {
            toast(S.t("favLimit").replace("{n}", Config.MAX_FAVORITES.toString()))
            return
        } else {
            fav.add(ch.name)
            toast(S.t("favAdded").replace("{name}", ch.name))
        }
        Storage.save(this)
        AppLog.i("UI", "favourites: " + fav.size)
        rebuildRows(reset = false)
    }

    // ---------- Settings / Настройки ----------

    // Settings open over the main screen; the video goes on behind them.
    // Настройки открываются поверх главного экрана; видео идёт позади них.
    override fun openSettings() {
        settingsPinOk = false
        AppLog.i("UI", "settings open")
        ui.settings = SettingsPage(SetPage.MAIN, S.t("settings"), "", mainRows(), 0)
        refresh()
    }

    private fun mainRows(): List<SetRow> {
        val p = Storage.byId(currentId)
        var plValue = p?.name ?: ""
        val pl = playlist
        if (pl != null) plValue += " · " + S.t("channels").replace("{n}", pl.channels.size.toString())
        if (ui.stale) plValue += " " + S.t("staleShort")
        val groups = if (pl == null) 0 else Catalog.groupsOf(pl).size
        return listOf(
            SetRow("playlist", S.t("playlistRow") + " >", plValue),
            SetRow("refresh", S.t("sRefresh")),
            SetRow("groups", S.t("sGroups") + " >", S.t("groupsValue").replace("{n}", groups.toString())),
            SetRow("pin", S.t("sPin"), S.t(if (Protection.hasPin()) "sPinOn" else "sPinOff")),
            SetRow("zap", S.t("sZap"), S.t(if (Storage.zapInvert) "zapList" else "zapClassic")),
            SetRow("log", S.t("sLog") + " >", S.t("sLogValue").replace("{n}", AppLog.size().toString())),
            SetRow("about", S.t("aboutRow"), "v" + BuildConfig.VERSION_NAME),
            SetRow("close", S.t("sClose"))
        )
    }

    private fun groupRows(): List<SetRow> {
        val pl = playlist ?: return emptyList()
        return Catalog.groupsOf(pl).keys.map { g ->
            var value = modeText(Protection.mode(g))
            if (Protection.isAdult(g)) value += " · " + S.t("adultTag")
            SetRow("g:$g", g.ifEmpty { S.t("noGroup") }, value)
        }
    }

    private fun logRows(): List<SetRow> = AppLog.lines().mapIndexed { i, line -> SetRow(i.toString(), line) }

    private fun updateSettingsPage() {
        val page = ui.settings ?: return
        when (page.page) {
            SetPage.MAIN -> page.update(mainRows())
            SetPage.GROUPS -> page.update(groupRows())
            SetPage.LOG -> {}
        }
        refresh()
    }

    private fun settingsKey(page: SettingsPage, k: NavKey, repeat: Boolean, first: Boolean) {
        when (k) {
            NavKey.UP -> page.move(-1, repeat)
            NavKey.DOWN -> page.move(1, repeat)
            NavKey.PAGE_UP, NavKey.CH_UP -> page.page(-1)
            NavKey.PAGE_DOWN, NavKey.CH_DOWN -> page.page(1)
            NavKey.LEFT -> if (first) { settingsBack(); return }
            NavKey.OK -> if (first) { settingsOk(page); return }
            NavKey.RIGHT -> {}
        }
        refresh()
    }

    private fun settingsBack() {
        val page = ui.settings ?: return
        if (page.page == SetPage.MAIN) {
            AppLog.i("UI", "settings close")
            ui.settings = null
        } else {
            val from = if (page.page == SetPage.GROUPS) "groups" else "log"
            ui.settings = SettingsPage(SetPage.MAIN, S.t("settings"), "", mainRows(), mainRows().indexOfFirst { it.id == from })
        }
        refresh()
    }

    private fun settingsOk(page: SettingsPage) {
        val row = page.current() ?: return
        when (page.page) {
            SetPage.MAIN -> when (row.id) {
                "playlist" -> openPlaylists()
                "refresh" -> Storage.byId(currentId)?.let { updatePlaylist(it, manual = true) }
                "groups" -> {
                    ui.settings = SettingsPage(SetPage.GROUPS, S.t("sGroups"), S.t("sGroupsSub"), groupRows(), 0)
                    refresh()
                }
                "pin" -> pinSettings()
                "zap" -> zapChoice()
                "log" -> {
                    val rows = logRows()
                    ui.settings = SettingsPage(SetPage.LOG, S.t("sLog"), S.t("sLogSub"), rows, rows.size - 1)
                    refresh()
                }
                "about" -> showMessage(
                    "about", S.t("aboutRow"),
                    S.t("aboutText").replace("{v}", Config.APP_NAME + " v" + BuildConfig.VERSION_NAME) + "\n\n" +
                        S.t("privacyLabel") + " " + Config.PRIVACY_URL + "\n" +
                        S.t("supportLabel") + " " + Config.SUPPORT_URL
                )
                "close" -> openExitDialog()
            }
            SetPage.GROUPS -> groupModeWindow(row.id.removePrefix("g:"))
            SetPage.LOG -> ask("clear log", S.t("sLog"), "", S.t("logClear")) {
                AppLog.clear()
                toast(S.t("logCleared"))
                ui.settings = SettingsPage(SetPage.LOG, S.t("sLog"), S.t("sLogSub"), logRows(), 0)
                refresh()
            }
        }
    }

    // OK on a group: three modes (the current one is marked), the 18+ switch, Close.
    // After a change the window opens again, so the new mark and [x] are seen at once.
    // OK на группе: три режима (текущий отмечен), переключатель 18+, Закрыть.
    // После изменения окно открывается снова, чтобы новая отметка и [x] были видны сразу.
    private fun groupModeWindow(g: String, start: Int = -1) {
        val cur = Protection.mode(g)
        val modes = listOf(GroupMode.SHOWN, GroupMode.PIN, GroupMode.HIDDEN)
        val items = modes.map { PopItem(modeText(it), it == cur) } +
            PopItem(S.t(if (Protection.isAdult(g)) "adultOn" else "adultOff")) + PopItem(S.t("close"))
        val first = if (start >= 0) start else modes.indexOf(cur)
        showPopup(Popup("group mode", g.ifEmpty { S.t("noGroup") }, S.t("groupModeMsg"), items, first, { i ->
            when (i) {
                in 0..2 -> setGroupMode(g, modes[i]) { groupModeWindow(g, i) }
                3 -> setAdult(g, !Protection.isAdult(g)) { groupModeWindow(g, 3) }
                else -> {}
            }
        }))
    }

    // No PIN: create one. A PIN: Change / Delete (both ask the current PIN or the master code).
    // PIN не задан: создать. Задан: Сменить / Удалить (оба спрашивают текущий PIN или мастер-код).
    private fun pinSettings() {
        if (!Protection.hasPin()) {
            createPin("") {}
            return
        }
        val items = listOf(PopItem(S.t("sPinChange")), PopItem(S.t("sPinRemove")), PopItem(S.t("cancel")))
        showPopup(Popup("pin settings", S.t("sPin"), "", items, 0, { i ->
            when (i) {
                0 -> askPin("change", { createPin("") {} })
                1 -> askPin("remove", { removePin("settings") })
                else -> {}
            }
        }))
    }

    // Channel switching in full screen / Переключение каналов на полном экране
    private fun zapChoice() {
        val items = listOf(PopItem(S.t("zapList"), Storage.zapInvert), PopItem(S.t("zapClassic"), !Storage.zapInvert))
        showPopup(Popup("channel switching", S.t("sZap"), "", items, if (Storage.zapInvert) 0 else 1, { which ->
            Storage.zapInvert = which == 0
            nav.zapInvert = Storage.zapInvert
            Storage.save(this)
            AppLog.i("UI", "setting zapInvert=" + Storage.zapInvert)
            updateSettingsPage()
        }))
    }

    // The cursor is on "Close" at once; Back in the window returns to where it was.
    // Курсор сразу на «Закрыть»; Back в окне возвращает туда, где был.
    override fun openExitDialog() {
        ask("exit", S.t("exitTitle"), "", S.t("exit")) {
            AppLog.i("APP", "exit")
            finish()
        }
    }

    // ---------- app life / жизнь приложения ----------

    // The app went to the background (Home button and so on): the stream stops, the group
    // opened with the PIN closes.
    // Приложение ушло в фон (кнопка Home и т. п.): поток останавливается, открытая PIN-кодом
    // группа закрывается.
    override fun onStop() {
        super.onStop()
        AppLog.i("APP", "to background")
        okDown = false
        clearNumber()
        nav.stopPlayback()
        if (Protection.openGroup != null) {
            Protection.close()
            if (playlist != null) rebuildRows(reset = false)
        }
    }

    override fun onStart() {
        super.onStart()
        AppLog.i("APP", "to foreground")
    }

    override fun onDestroy() {
        input?.dismiss()
        input = null
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        engine.release()
        super.onDestroy()
    }
}
