// ShaGa IPTV Player for Android — MainActivity.kt
// The only window of the app: start, remote keys (short and long OK, digits), Back, dialogs
// (link, search, language, settings, channel menu, exit), playlist loading, favourites, the player.
// Единственное окно приложения: запуск, кнопки пульта (короткое и долгое OK, цифры), Back, окна
// (ссылка, поиск, язык, настройки, меню канала, выход), загрузка плейлиста, избранное, плеер.
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
    private var dialog: AlertDialog? = null
    private var dialogShownAt = 0L
    private var loadSeq = 0          // number of the latest load; older answers are ignored
                                     // номер последней загрузки; устаревшие ответы игнорируются
    private var barsHidden = false

    private var playlist: Playlist? = null
    private var searchQuery = ""                     // the last search / последний поиск
    private var searchResult: List<Channel>? = null  // null = there was no search / поиска не было

    private val handler = Handler(Looper.getMainLooper())
    private var okDown = false       // OK is being held (pressed in our window) / OK удерживается (нажат в нашем окне)
    private var okLong = false       // the long press has already worked / долгое нажатие уже сработало
    private val numberCommit = Runnable { commitNumber() }

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
            " density " + dm.density + ", language " + Locale.getDefault().language + " -> " + S.lang)

        nav = Nav(this, startInKeyMode = ui.isTv)
        nav.zapInvert = Storage.zapInvert
        engine = PlayerEngine(this) { state -> onPlayState(state) }

        // Back (remote key or gesture) goes by steps / Back (кнопка пульта или жест) идёт по шагам
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ui.numBuf.isNotEmpty()) {
                    // Back cancels the number being typed / Back отменяет набираемый номер
                    clearNumber()
                } else if (ui.screen == Screen.MAIN) {
                    AppLog.i("UI", "back from " + nav.place())
                    nav.back()
                } else {
                    // Welcome or loading: Back leaves the app / Приветствие или загрузка: Back выходит
                    finish()
                }
            }
        })

        val saved = Storage.current()
        if (saved == null) {
            AppLog.i("APP", "no playlists: welcome screen")
            ui.screen = Screen.WELCOME
        } else {
            ui.playlistName = saved.name
            loadPlaylist(saved.url, fromDialog = false)
        }

        setContent {
            AppRoot(ui, nav, engine.exo, onWelcome = { showUrlDialog("", "") })
        }
    }

    // ---------- remote / пульт ----------

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

    // Every key press goes to the log with its Android name and the place of the cursor, so that
    // we can see which keys of a remote reach the app. Digits are not written (a PIN code is
    // typed with them). Text typed in a dialog does not come here at all.
    // The navigation keys are handled here, the screen itself does not use the input focus.
    // Каждое нажатие пишется в журнал с именем кнопки Android и местом курсора: так видно, какие
    // кнопки пульта доходят до приложения. Цифры не пишутся (ими вводится PIN-код). Текст,
    // который вводят в окне, сюда вообще не попадает.
    // Кнопки навигации обрабатываются здесь, сам экран фокусом ввода не пользуется.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        val digit = digitOf(code)
        if (down && event.repeatCount == 0) {
            val where = when (ui.screen) {
                Screen.WELCOME -> "welcome"
                Screen.LOADING -> "loading"
                Screen.MAIN -> nav.place()
            }
            val name = if (digit >= 0) "DIGIT" else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            AppLog.i("KEY", "$name $where")
        }

        // Digits: the channel number / Цифры: номер канала
        if (digit >= 0 && ui.screen == Screen.MAIN) {
            if (down && event.repeatCount == 0) pressDigit(digit)
            return true
        }

        val k = navKey(code) ?: return super.dispatchKeyEvent(event)

        // OK: a short press works when the key is released; held for LONG_PRESS_MS it is a long
        // press (context menu) and the release does nothing. A release without our own press
        // (the press was made in a dialog that has just closed) is ignored.
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
            Screen.WELCOME -> showUrlDialog("", "")
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
        ui.tick++
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

    override fun startChannel(ch: Channel) {
        engine.play(ch)
    }

    override fun stopChannel() {
        engine.stop()
    }

    private fun onPlayState(state: PlayState) {
        ui.playState = state
        nav.playFailed = state == PlayState.ERROR
    }

    // ---------- left column rows / строки левой колонки ----------

    // reset = true for a new playlist; false keeps the cursor (language, favourites, search).
    // reset = true для нового плейлиста; false сохраняет курсор (язык, избранное, поиск).
    private fun rebuildRows(reset: Boolean) {
        nav.setRows(Catalog.rows(playlist, Storage.favorites, searchQuery, searchResult) { key -> S.t(key) }, reset)
    }

    // ---------- small dialogs / простые окна ----------

    private fun show(d: AlertDialog) {
        dialog?.dismiss()
        dialog = d
        dialogShownAt = SystemClock.elapsedRealtime()
        d.show()
    }

    private fun builder(what: String): AlertDialog.Builder {
        AppLog.i("UI", "dialog open: $what")
        return AlertDialog.Builder(this)
            .setOnCancelListener { AppLog.i("UI", "dialog close: back") }
    }

    private fun showMessage(what: String, title: String, text: String) {
        show(builder(what).setTitle(title).setMessage(text).setPositiveButton(S.t("ok"), null).create())
    }

    // A list of actions; the window closes after a choice.
    // Список действий; после выбора окно закрывается.
    private fun showMenu(what: String, title: String, labels: List<String>, onPick: (Int) -> Unit) {
        val items = labels.toTypedArray<CharSequence>()
        show(builder(what).setTitle(title).setItems(items) { _, which ->
            AppLog.i("UI", "menu $what: item " + (which + 1))
            onPick(which)
        }.create())
    }

    // One of several values; the current one is marked and has the cursor.
    // Одно значение из нескольких; текущее отмечено, курсор сразу на нём.
    private fun showChoice(what: String, title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        val items = labels.toTypedArray<CharSequence>()
        show(builder(what).setTitle(title).setSingleChoiceItems(items, current) { box, which ->
            AppLog.i("UI", "choice $what: item " + (which + 1))
            box.dismiss()
            onPick(which)
        }.setNegativeButton(S.t("cancel")) { _, _ -> AppLog.i("UI", "dialog close: cancel") }.create())
    }

    // A window with one text field: the remote and the on-screen keyboard work in it without
    // extra code. One press of OK / "Done" could work twice (in the closing window and in the
    // one that opens after it), so for a short time after opening the window ignores OK.
    // Окно с одним полем ввода: пульт и экранная клавиатура работают в нём без дополнительного
    // кода. Одно нажатие OK / «Готово» могло сработать дважды (в закрывающемся окне и в том, что
    // открылось следом), поэтому короткое время после открытия окно не принимает OK.
    private fun showInput(
        what: String, title: String, message: String, text: String, hint: String,
        isLink: Boolean, onSubmit: (String) -> Unit
    ) {
        val input = EditText(this)
        input.setSingleLine(true)
        input.inputType = if (isLink) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        else InputType.TYPE_CLASS_TEXT
        input.imeOptions = if (isLink) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_SEARCH
        input.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(if (isLink) TextUtil.MAX_URL else 100))
        input.hint = hint
        input.setText(text)
        input.setSelection(input.text.length)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this)
        box.setPadding(pad, 0, pad, 0)
        box.addView(input)

        val d = builder(what)
            .setTitle(title)
            .setMessage(message)
            .setView(box)
            .setPositiveButton(S.t("ok"), null)      // the click is set below / нажатие задаётся ниже
            .setNegativeButton(S.t("cancel")) { _, _ -> AppLog.i("UI", "dialog close: cancel") }
            .create()
        val submit = {
            if (SystemClock.elapsedRealtime() - dialogShownAt >= Config.DIALOG_GUARD_MS) {
                d.dismiss()
                onSubmit(input.text.toString())
            } else {
                AppLog.i("UI", "dialog: early OK ignored")
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else {
                false
            }
        }
        show(d)
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit() }
    }

    // ---------- link / ссылка ----------

    override fun openLinkDialog() {
        showUrlDialog(Storage.current()?.url ?: "", "")
    }

    // On an error the window opens again with the same text and the error.
    // При ошибке окно открывается снова с тем же текстом и ошибкой.
    private fun showUrlDialog(text: String, error: String) {
        val isEdit = Storage.current() != null
        var message = S.t("urlMsg")
        if (ui.isTv) message += "\n\n" + S.t("hintKeyboard")
        if (error.isNotEmpty()) message = error + "\n\n" + message
        showInput(
            what = (if (isEdit) "edit link" else "new link") + (if (error.isNotEmpty()) " (with error)" else ""),
            title = S.t(if (isEdit) "editTitle" else "urlTitle"),
            message = message, text = text, hint = S.t("urlPlaceholder"), isLink = true
        ) { raw -> submitUrl(raw) }
    }

    private fun submitUrl(raw: String) {
        val url = TextUtil.normalizeUrl(raw)
        if (url.isEmpty()) {
            AppLog.i("UI", "dialog close: empty")
            return
        }
        if (!TextUtil.isValidUrl(url)) {
            AppLog.i("UI", "dialog: not a web link")
            showUrlDialog(raw, S.t("invalidUrl"))
            return
        }
        AppLog.i("UI", "dialog close: ok")
        loadPlaylist(url, fromDialog = true)
    }

    // ---------- search / поиск ----------

    override fun openSearch() {
        showInput(
            what = "search", title = S.t("searchTitle"), message = S.t("searchMsg"),
            text = searchQuery, hint = "", isLink = false
        ) { raw -> runSearch(raw.trim()) }
    }

    // The results become the category "Search: ..." with the cursor on its first channel.
    // Nothing found = a window, the previous results stay.
    // Результаты становятся категорией «Поиск: ...» с курсором на первом канале.
    // Ничего не найдено = окно, прежние результаты остаются.
    private fun runSearch(q: String) {
        val pl = playlist
        if (q.isEmpty() || pl == null) return
        val found = TextUtil.search(pl.channels, q)
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
        val labels = codes.map { if (it.isEmpty()) S.t("langAuto") else S.nameOf(it) }
        showChoice("language", S.t("langTitle"), labels, maxOf(0, codes.indexOf(Storage.lang))) { which ->
            Storage.lang = codes[which]
            Storage.save(this)
            S.init(Storage.lang, Locale.getDefault().language)
            AppLog.i("UI", "language: " + Storage.lang.ifEmpty { "auto" } + " -> " + S.lang)
            ui.lang = S.lang
            rebuildRows(reset = false)
        }
    }

    // ---------- settings / настройки ----------

    // "Close the app" is the last item / «Закрыть приложение» — последний пункт
    override fun openSettings() {
        val zap = S.t("sZap") + ": " + S.t(if (Storage.zapInvert) "zapList" else "zapClassic")
        showMenu("settings", S.t("settings"), listOf(zap, S.t("aboutRow"), S.t("sClose"))) { which ->
            when (which) {
                0 -> openZapChoice()
                1 -> showMessage(
                    "about", S.t("aboutRow"),
                    S.t("aboutText").replace("{v}", Config.APP_NAME + " v" + BuildConfig.VERSION_NAME) + "\n\n" +
                        S.t("privacyLabel") + "\n" + Config.PRIVACY_URL + "\n\n" +
                        S.t("supportLabel") + "\n" + Config.SUPPORT_URL
                )
                else -> openExitDialog()
            }
        }
    }

    // Channel switching in full screen / Переключение каналов на полном экране
    private fun openZapChoice() {
        showChoice("channel switching", S.t("sZap"), listOf(S.t("zapList"), S.t("zapClassic")), if (Storage.zapInvert) 0 else 1) { which ->
            Storage.zapInvert = which == 0
            nav.zapInvert = Storage.zapInvert
            Storage.save(this)
            AppLog.i("UI", "setting zapInvert=" + Storage.zapInvert)
        }
    }

    // The cursor is on "Close" at once; Back in the window returns to the groups.
    // Курсор сразу на «Закрыть»; Back в окне возвращает к группам.
    override fun openExitDialog() {
        val d = builder("exit")
            .setTitle(S.t("exitTitle"))
            .setPositiveButton(S.t("exit")) { _, _ ->
                AppLog.i("APP", "exit")
                finish()
            }
            .setNegativeButton(S.t("cancel")) { _, _ -> AppLog.i("UI", "dialog close: cancel") }
            .create()
        show(d)
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    // ---------- channel menu, favourites / меню канала, избранное ----------

    // Long OK (or touch and hold) on a channel / Долгое OK (или долгое нажатие) на канале
    override fun openChannelMenu(ch: Channel, ownGroup: Boolean) {
        val labels = ArrayList<String>()
        labels.add(S.t(if (ch.name in Storage.favorites) "ctxFavRemove" else "ctxFavAdd"))
        if (!ownGroup) labels.add(S.t("ctxGoGroup"))
        showMenu("channel", ch.name, labels) { which ->
            if (which == 0) toggleFavorite(ch) else nav.openCategory(Catalog.groupId(ch.group), ch)
        }
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

    // ---------- playlist / плейлист ----------

    // fromDialog = true: the link has just been typed. It is saved only after a successful
    // load; on an error the dialog opens again and the old channel list stays.
    // fromDialog = true: ссылку только что ввели. Она сохраняется только после успешной
    // загрузки; при ошибке окно открывается снова, а прежний список каналов остаётся.
    private fun loadPlaylist(url: String, fromDialog: Boolean) {
        val seq = ++loadSeq
        val before = ui.screen
        // Changing the playlist stops the video / Смена плейлиста останавливает видео
        if (before == Screen.MAIN) nav.stopPlayback()
        ui.screen = Screen.LOADING
        scope.launch {
            var result: Playlist? = null
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

            val pl = result
            if (pl != null) {
                if (fromDialog) {
                    Storage.setSingle(url)
                    Storage.save(this@MainActivity)
                }
                ui.playlistName = Storage.current()?.name ?: ""
                ui.listError = ""
                // A new channel list: the search is dropped / Новый список каналов: поиск сбрасывается
                playlist = pl
                searchQuery = ""
                searchResult = null
                rebuildRows(reset = true)
                ui.screen = Screen.MAIN
            } else {
                val message = errorText(error)
                if (fromDialog) {
                    ui.screen = if (before == Screen.WELCOME) Screen.WELCOME else Screen.MAIN
                    showUrlDialog(url, message)
                } else {
                    // Start with a saved link that does not load: no channels, the error text
                    // on the screen, the cursor is on "> Change link".
                    // Запуск с сохранённой ссылкой, которая не загрузилась: каналов нет, текст
                    // ошибки на экране, курсор на «> Изменить ссылку».
                    ui.listError = message
                    playlist = null
                    searchQuery = ""
                    searchResult = null
                    rebuildRows(reset = true)
                    ui.screen = Screen.MAIN
                }
            }
        }
    }

    private fun errorText(e: LoadError?): String = when (e?.code) {
        "timeout" -> S.t("errTimeout")
        "http" -> S.t("errHttp").replace("{code}", (e?.status ?: 0).toString())
        "empty" -> S.t("errEmpty")
        else -> S.t("errNetwork")
    }

    // ---------- app life / жизнь приложения ----------

    // The app went to the background (Home button and so on): the stream stops.
    // Приложение ушло в фон (кнопка Home и т. п.): поток останавливается.
    override fun onStop() {
        super.onStop()
        AppLog.i("APP", "to background")
        okDown = false
        clearNumber()
        nav.stopPlayback()
    }

    override fun onStart() {
        super.onStart()
        AppLog.i("APP", "to foreground")
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        handler.removeCallbacks(numberCommit)
        scope.cancel()
        engine.release()
        super.onDestroy()
    }
}
