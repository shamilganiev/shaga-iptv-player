// ShaGa IPTV Player for Android — MainActivity.kt
// The only window of the app: start, remote keys, Back, dialogs (link, settings, exit),
// playlist loading, the player.
// Единственное окно приложения: запуск, кнопки пульта, Back, окна (ссылка, настройки, выход),
// загрузка плейлиста, плеер.
package com.shaga.player

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark system bars with light icons; the app draws edge to edge (required on Android 15+).
        // Тёмные системные панели со светлыми значками; приложение рисует «от края до края»
        // (обязательно на Android 15+).
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))

        ui.isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        S.init(Locale.getDefault().language)

        // The first line of the log is the name and the version.
        // Первая строка журнала — название и версия.
        AppLog.start(BuildConfig.VERSION_NAME)
        val dm = resources.displayMetrics
        AppLog.i("APP", "device " + Build.MANUFACTURER + " " + Build.MODEL +
            ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " +
            (if (ui.isTv) "TV" else "touch") + ", screen " + dm.widthPixels + "x" + dm.heightPixels +
            " density " + dm.density + ", language " + Locale.getDefault().language + " -> " + S.lang)

        Storage.load(this)
        nav = Nav(this, startInKeyMode = ui.isTv)
        nav.zapInvert = Storage.zapInvert
        engine = PlayerEngine(this) { state -> onPlayState(state) }

        // Back (remote key or gesture) goes by steps / Back (кнопка пульта или жест) идёт по шагам
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ui.screen == Screen.MAIN) {
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
        val where = when (ui.screen) {
            Screen.WELCOME -> "welcome"
            Screen.LOADING -> "loading"
            Screen.MAIN -> nav.place()
        }
        if (down && event.repeatCount == 0) {
            val digit = code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
                code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9
            val name = if (digit) "DIGIT" else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            AppLog.i("KEY", "$name $where")
        }
        val k = navKey(code) ?: return super.dispatchKeyEvent(event)
        if (down) {
            when (ui.screen) {
                Screen.MAIN -> nav.key(k, event.repeatCount > 0)
                Screen.WELCOME -> if (k == NavKey.OK && event.repeatCount == 0) showUrlDialog("", "")
                Screen.LOADING -> {}
            }
        }
        return true
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

    override fun openLinkDialog() {
        showUrlDialog(Storage.current()?.url ?: "", "")
    }

    // 0.2.0: the only setting is the direction of channel switching in full screen.
    // 0.2.0: единственная настройка — направление переключения каналов на полном экране.
    override fun openSettings() {
        dialog?.dismiss()
        AppLog.i("UI", "dialog open: settings")
        val items = arrayOf<CharSequence>(S.t("zapList"), S.t("zapClassic"))
        val d = AlertDialog.Builder(this)
            .setTitle(S.t("sZap"))
            .setSingleChoiceItems(items, if (Storage.zapInvert) 0 else 1) { box, which ->
                Storage.zapInvert = which == 0
                nav.zapInvert = Storage.zapInvert
                Storage.save(this)
                AppLog.i("UI", "setting zapInvert=" + Storage.zapInvert)
                box.dismiss()
            }
            .setNegativeButton(S.t("cancel")) { _, _ -> AppLog.i("UI", "dialog close: cancel") }
            .setOnCancelListener { AppLog.i("UI", "dialog close: back") }
            .create()
        dialog = d
        d.show()
    }

    // The cursor is on "Close" at once; Back in the window returns to the groups.
    // Курсор сразу на «Закрыть»; Back в окне возвращает к группам.
    override fun openExitDialog() {
        dialog?.dismiss()
        AppLog.i("UI", "dialog open: exit")
        val d = AlertDialog.Builder(this)
            .setTitle(S.t("exitTitle"))
            .setPositiveButton(S.t("exit")) { _, _ ->
                AppLog.i("APP", "exit")
                finish()
            }
            .setNegativeButton(S.t("cancel")) { _, _ -> AppLog.i("UI", "dialog close: cancel") }
            .setOnCancelListener { AppLog.i("UI", "dialog close: back") }
            .create()
        dialog = d
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    // ---------- link dialog / окно ввода ссылки ----------

    // System dialog with a text field: the remote and the on-screen keyboard work in it
    // without extra code. On an error it opens again with the same text and the error.
    // Системное окно с полем ввода: пульт и экранная клавиатура работают в нём без
    // дополнительного кода. При ошибке открывается снова с тем же текстом и ошибкой.
    private fun showUrlDialog(text: String, error: String) {
        dialog?.dismiss()
        val isEdit = Storage.current() != null
        AppLog.i("UI", "dialog open: " + (if (isEdit) "edit link" else "new link") +
            (if (error.isNotEmpty()) " (with error)" else ""))

        val input = EditText(this)
        input.setSingleLine(true)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(TextUtil.MAX_URL))
        input.hint = S.t("urlPlaceholder")
        input.setText(text)
        input.setSelection(input.text.length)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this)
        box.setPadding(pad, 0, pad, 0)
        box.addView(input)

        var message = S.t("urlMsg")
        if (ui.isTv) message += "\n\n" + S.t("hintKeyboard")
        if (error.isNotEmpty()) message = error + "\n\n" + message

        val d = AlertDialog.Builder(this)
            .setTitle(S.t(if (isEdit) "editTitle" else "urlTitle"))
            .setMessage(message)
            .setView(box)
            .setPositiveButton(S.t("ok"), null)      // the click is set below / нажатие задаётся ниже
            .setNegativeButton(S.t("cancel")) { _, _ -> AppLog.i("UI", "dialog close: cancel") }
            .setOnCancelListener { AppLog.i("UI", "dialog close: back") }
            .create()

        // One press of OK / "Done" could work twice: in the closing dialog and in the one that
        // opens with the error. For a short time after opening the dialog ignores OK.
        // Одно нажатие OK / «Готово» могло сработать дважды: в закрывающемся окне и в том, что
        // открылось с ошибкой. Короткое время после открытия окно не принимает OK.
        val submit = {
            if (SystemClock.elapsedRealtime() - dialogShownAt >= Config.DIALOG_GUARD_MS) {
                d.dismiss()
                submitUrl(input.text.toString())
            } else {
                AppLog.i("UI", "dialog: early OK ignored")
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        dialog = d
        dialogShownAt = SystemClock.elapsedRealtime()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit() }
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
                nav.setPlaylist(Catalog.build(pl, S.t("allChannels"), S.t("noGroup")), S.t("editLink"), S.t("svcSettings"))
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
                    nav.setPlaylist(emptyList(), S.t("editLink"), S.t("svcSettings"))
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
        nav.stopPlayback()
    }

    override fun onStart() {
        super.onStart()
        AppLog.i("APP", "to foreground")
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        scope.cancel()
        engine.release()
        super.onDestroy()
    }
}
