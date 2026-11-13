// ShaGa IPTV Player for Android — MainActivity.kt
// The only window of the app: start, link dialog, playlist loading, switching screens,
// log of remote keys.
// Единственное окно приложения: запуск, окно ввода ссылки, загрузка плейлиста,
// переключение экранов, журнал кнопок пульта.
package com.shaga.player

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val state = AppState()
    private val scope = MainScope()
    private var dialog: AlertDialog? = null
    private var loadSeq = 0          // number of the latest load; older answers are ignored
                                     // номер последней загрузки; устаревшие ответы игнорируются

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark system bars with light icons; the app draws edge to edge (required on Android 15+).
        // Тёмные системные панели со светлыми значками; приложение рисует «от края до края»
        // (обязательно на Android 15+).
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))

        state.isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        S.init(Locale.getDefault().language)

        // The first line of the log is the name and the version.
        // Первая строка журнала — название и версия.
        AppLog.start(BuildConfig.VERSION_NAME)
        val dm = resources.displayMetrics
        AppLog.i("APP", "device " + Build.MANUFACTURER + " " + Build.MODEL +
            ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " +
            (if (state.isTv) "TV" else "touch") + ", screen " + dm.widthPixels + "x" + dm.heightPixels +
            " density " + dm.density + ", language " + Locale.getDefault().language + " -> " + S.lang)

        Storage.load(this)
        val saved = Storage.current()
        if (saved == null) {
            AppLog.i("APP", "no playlists: welcome screen")
            state.screen = Screen.WELCOME
        } else {
            state.playlistName = saved.name
            loadPlaylist(saved.url, fromDialog = false)
        }

        setContent {
            AppRoot(
                state = state,
                onEditLink = { showUrlDialog(Storage.current()?.url ?: "", "") },
                onPlay = { index -> play(index) },
                onClosePlayer = { closePlayer("back") }
            )
        }
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

        val d = AlertDialog.Builder(this)
            .setTitle(S.t(if (isEdit) "editTitle" else "urlTitle"))
            .setMessage(if (error.isNotEmpty()) error + "\n\n" + S.t("urlMsg") else S.t("urlMsg"))
            .setView(box)
            .setPositiveButton(S.t("ok")) { _, _ -> submitUrl(input.text.toString()) }
            .setNegativeButton(S.t("cancel")) { _, _ -> AppLog.i("UI", "dialog close: cancel") }
            .create()
        // "Done" on the keyboard = OK / «Готово» на клавиатуре = OK
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                d.dismiss()
                submitUrl(input.text.toString())
                true
            } else {
                false
            }
        }
        dialog = d
        d.show()
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
        val before = state.screen
        state.screen = Screen.LOADING
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
                    state.lastIndex = 0
                }
                state.playlistName = Storage.current()?.name ?: ""
                state.playlist = pl
                state.listError = ""
                state.screen = Screen.LIST
            } else {
                val message = errorText(error)
                if (fromDialog) {
                    state.screen = if (before == Screen.WELCOME) Screen.WELCOME else Screen.LIST
                    showUrlDialog(url, message)
                } else {
                    // Start with a saved link that does not load: empty list with the error text,
                    // the cursor is on "> Change link".
                    // Запуск с сохранённой ссылкой, которая не загрузилась: пустой список
                    // с текстом ошибки, курсор на «> Изменить ссылку».
                    state.playlist = null
                    state.listError = message
                    state.screen = Screen.LIST
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

    // ---------- player / плеер ----------

    private fun play(index: Int) {
        val pl = state.playlist ?: return
        if (index !in pl.channels.indices) return
        state.lastIndex = index
        state.playing = pl.channels[index]
        state.screen = Screen.PLAYER
    }

    private fun closePlayer(reason: String) {
        if (state.screen != Screen.PLAYER) return
        AppLog.i("UI", "player close: $reason")
        state.playing = null
        state.screen = Screen.LIST
    }

    // The app went to the background (Home button and so on): the stream stops.
    // Приложение ушло в фон (кнопка Home и т. п.): поток останавливается.
    override fun onStop() {
        super.onStop()
        AppLog.i("APP", "to background")
        closePlayer("background")
    }

    override fun onStart() {
        super.onStart()
        AppLog.i("APP", "to foreground")
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------- log of remote keys / журнал кнопок пульта ----------

    // Every key press goes to the log with its Android name, so that we can see which
    // keys of a remote reach the app. Digits are not written (a PIN code is typed with them).
    // Text typed in the link dialog does not come here at all.
    // Каждое нажатие пишется в журнал с именем кнопки Android: так видно, какие кнопки
    // пульта доходят до приложения. Цифры не пишутся (ими вводится PIN-код).
    // Текст, который вводят в окне ссылки, сюда вообще не попадает.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val code = event.keyCode
            val digit = code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
                code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9
            val name = if (digit) "DIGIT" else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            AppLog.i("KEY", name + " " + state.screen.name.lowercase())
        }
        return super.dispatchKeyEvent(event)
    }
}
