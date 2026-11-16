// ShaGa IPTV Player for Android — Overlays.kt
// Our own windows drawn over the main screen: a menu / choice / question (Popup), the PIN
// keypad (PinPad) and the pages of Settings (SettingsPage). Only the state and the keys are
// here, no Android code; Ui.kt draws them, MainActivity fills them and sends the keys.
// Text input (links, search) stays in the system window: the on-screen keyboard needs it.
// Свои окна поверх главного экрана: меню / выбор / вопрос (Popup), клавиатура PIN-кода
// (PinPad) и страницы Настроек (SettingsPage). Здесь только состояние и кнопки, кода Android
// нет; рисует Ui.kt, заполняет и присылает кнопки MainActivity.
// Ввод текста (ссылки, поиск) остаётся в системном окне: он нужен экранной клавиатуре.
package com.shaga.player

// mark = the current value, drawn with "* " in front / текущее значение, рисуется с «* » впереди
class PopItem(val label: String, val mark: Boolean = false)

// A vertical list of items; one step is cyclic. what = the name for the log.
// Вертикальный список пунктов; шаг на одну строку циклический. what = имя для журнала.
class Popup(
    val what: String,
    val title: String,
    val message: String,
    val items: List<PopItem>,
    start: Int,
    val onPick: (Int) -> Unit,
    val onCancel: () -> Unit = {}
) {
    var sel = start.coerceIn(0, maxOf(0, items.size - 1))
        private set

    fun move(d: Int) {
        if (items.isEmpty()) return
        sel = ((sel + d) % items.size + items.size) % items.size
    }

    fun select(i: Int) {
        if (i in items.indices) sel = i
    }
}

// 4 digits. Keys of the pad: 1..9, then Del, 0, OK (indexes 0..11), arrows without wrapping.
// The 4th digit submits at once; the owner answers with reset() (wrong PIN) or closes the pad.
// 4 цифры. Кнопки: 1..9, затем Стереть, 0, OK (номера 0..11), стрелки без зацикливания.
// 4-я цифра сразу отправляет код; владелец отвечает reset() (неверный PIN) или закрывает окно.
class PinPad(
    val what: String,
    val title: String,
    message: String,
    val hint: String,
    val onDone: (String) -> Unit,
    val onCancel: () -> Unit
) {
    var message = message
        private set
    var error = false                   // the message is an error / сообщение — ошибка
        private set
    var digits = ""
        private set
    var sel = 4                         // the cursor starts on "5" / курсор сначала на «5»
        private set

    fun label(i: Int): String = when (i) {
        in 0..8 -> (i + 1).toString()
        9 -> S.t("keyDel")
        10 -> "0"
        else -> S.t("ok")
    }

    fun move(dx: Int, dy: Int) {
        val col = (sel % 3 + dx).coerceIn(0, 2)
        val row = (sel / 3 + dy).coerceIn(0, 3)
        sel = row * 3 + col
    }

    fun press(i: Int) {
        sel = i
        when (i) {
            9 -> digits = digits.dropLast(1)
            11 -> if (digits.length == 4) onDone(digits)
            else -> digit(if (i == 10) 0 else i + 1)
        }
    }

    fun digit(d: Int) {
        if (digits.length >= 4) return
        digits += d.toString()
        if (digits.length == 4) onDone(digits)
    }

    fun reset(newMessage: String, isError: Boolean) {
        digits = ""
        message = newMessage
        error = isError
    }
}

// value = the text on the right of a row / текст справа в строке
class SetRow(val id: String, val label: String, val value: String = "")

enum class SetPage { MAIN, GROUPS, LOG }

// A page of Settings: a list of rows with the cursor; one step is cyclic, paging stops at the edges.
// Страница Настроек: список строк с курсором; шаг циклический, листание останавливается у краёв.
class SettingsPage(val page: SetPage, val title: String, val sub: String, rows: List<SetRow>, start: Int) {
    var rows: List<SetRow> = rows
        private set
    var sel = start.coerceIn(0, maxOf(0, rows.size - 1))
        private set
    var perPage = 8

    fun move(d: Int, repeat: Boolean) {
        val n = rows.size
        if (n == 0) return
        var i = sel + d
        if (i < 0 || i >= n) {
            if (repeat) return
            i = (i + n) % n
        }
        sel = i
    }

    fun page(d: Int) {
        if (rows.isEmpty()) return
        sel = (sel + d * perPage).coerceIn(0, rows.size - 1)
    }

    fun select(i: Int) {
        if (i in rows.indices) sel = i
    }

    // New rows, the cursor stays on the row with the same id / Новые строки, курсор остаётся на той же строке
    fun update(newRows: List<SetRow>) {
        val id = rows.getOrNull(sel)?.id
        rows = newRows
        val i = newRows.indexOfFirst { it.id == id }
        sel = if (i >= 0) i else sel.coerceIn(0, maxOf(0, newRows.size - 1))
    }

    fun current(): SetRow? = rows.getOrNull(sel)
}
