// ShaGa IPTV Player for Android — Nav.kt
// Main screen logic: left column rows, the cursor in both columns, full screen, channel
// switching, Back by steps, marks of what is playing (SPEC.md, sections 2 and 3). No Android
// code here: the remote and the touch screen come as simple calls, the screen is redrawn
// through NavHost.
// Логика главного экрана: строки левой колонки, курсор в обеих колонках, полный экран,
// переключение каналов, Back по шагам, отметки того, что играет (SPEC.md, разделы 2 и 3).
// Кода Android здесь нет: пульт и сенсорный экран приходят простыми вызовами, экран
// перерисовывается через NavHost.
package com.shaga.player

// A category of the left column. id stays the same when the rows are rebuilt (language,
// favourites, search, group modes), so the cursor can be put back:
// "__search", "__fav", "__all", "g:<group name>".
// group = the playlist group name (only for "g:" categories); locked = "Name [PIN]" without
// a counter; channels of a locked group are empty until the PIN is entered.
// Категория левой колонки. id не меняется при пересборке строк (язык, избранное, поиск,
// режимы групп), чтобы вернуть курсор на место: "__search", "__fav", "__all", "g:<группа>".
// group = название группы плейлиста (только у «g:»); locked = «Название [PIN]» без счётчика;
// каналы закрытой группы пусты, пока не введён PIN.
class Category(
    val id: String,
    val title: String,
    val channels: List<Channel>,
    val group: String? = null,
    val locked: Boolean = false
)

enum class RowKind { SEARCH, PLAYLISTS, LANGUAGE, SETTINGS, CATEGORY }

// A row of the left column: a service row ("> ...") or a category.
// Строка левой колонки: служебная («> ...») или категория.
class LeftRow(val kind: RowKind, val title: String, val category: Category?)

// The same channel in a new copy of the playlist (after an update the objects are new).
// Тот же канал в новой копии плейлиста (после обновления объекты новые).
fun sameChannel(a: Channel?, b: Channel?): Boolean =
    a != null && b != null && (a === b || (a.name == b.name && a.group == b.group))

fun List<Channel>.indexOfChannel(ch: Channel?): Int {
    if (ch == null) return -1
    val i = indexOf(ch)
    return if (i >= 0) i else indexOfFirst { sameChannel(it, ch) }
}

object Catalog {
    const val SEARCH = "__search"
    const val FAV = "__fav"
    const val ALL = "__all"

    fun groupId(group: String): String = "g:$group"

    // The left column (SPEC.md, section 2): service rows; then "Search: ..." (if there was a
    // search), "Favourites", "All channels" and the groups that are not hidden, in the order
    // of the playlist. Protection rules come from Protection; t gives a UI string by its key.
    // Левая колонка (SPEC.md, раздел 2): служебные строки; затем «Поиск: ...» (если был поиск),
    // «Избранное», «Все каналы» и нескрытые группы в порядке плейлиста. Правила защиты — из
    // Protection; t даёт строку интерфейса по ключу.
    fun rows(
        pl: Playlist?, favorites: Collection<String>,
        searchQuery: String, searchResult: List<Channel>?,
        t: (String) -> String
    ): List<LeftRow> {
        val out = ArrayList<LeftRow>()
        out.add(LeftRow(RowKind.SEARCH, t("svcSearch"), null))
        out.add(LeftRow(RowKind.PLAYLISTS, t("svcPlaylists"), null))
        out.add(LeftRow(RowKind.LANGUAGE, t("svcLanguage"), null))
        out.add(LeftRow(RowKind.SETTINGS, t("svcSettings"), null))
        if (pl == null || pl.channels.isEmpty()) return out

        fun add(c: Category) = out.add(LeftRow(RowKind.CATEGORY, c.title, c))
        if (searchResult != null) {
            add(Category(SEARCH, t("searchPrefix") + " " + searchQuery, searchResult.filter { Protection.general(it.group) }))
        }
        val favSet = favorites.toHashSet()
        add(Category(FAV, t("favorites"), pl.channels.filter { it.name in favSet && Protection.general(it.group) }))
        add(Category(ALL, t("allChannels"), pl.channels.filter { Protection.general(it.group) }))
        for ((group, list) in groupsOf(pl)) {
            if (Protection.isHidden(group)) continue
            val locked = Protection.isLocked(group)
            val title = group.ifEmpty { t("noGroup") }
            add(Category(groupId(group), title, if (Protection.isOpen(group)) list else emptyList(), group, locked))
        }
        return out
    }

    // Groups in the order they appear in the playlist / Группы в порядке появления в плейлисте
    fun groupsOf(pl: Playlist): Map<String, List<Channel>> {
        val byGroup = LinkedHashMap<String, ArrayList<Channel>>()
        for (ch in pl.channels) byGroup.getOrPut(ch.group) { ArrayList() }.add(ch)
        return byGroup
    }

    // Names of channels of groups with the 18+ mark: they must not stay in the favourites.
    // Названия каналов групп с отметкой 18+: в избранном их быть не должно.
    fun adultNames(pl: Playlist?): Set<String> {
        if (pl == null) return emptySet()
        val out = HashSet<String>()
        for (ch in pl.channels) if (Protection.isAdult(ch.group)) out.add(ch.name)
        return out
    }
}

enum class NavKey { UP, DOWN, LEFT, RIGHT, OK, PAGE_UP, PAGE_DOWN, CH_UP, CH_DOWN }

enum class Pane { GROUPS, CHANNELS }

// What the logic asks the app to do / Что логика просит сделать приложение
interface NavHost {
    fun navChanged()                    // redraw the screen / перерисовать экран
    fun startChannel(ch: Channel)
    fun stopChannel()
    fun openSearch()
    fun openPlaylists()
    fun openLanguage()
    fun openSettings()
    fun openExitDialog()
    fun openLockedGroup(cat: Category)  // OK on "Name [PIN]" / OK на «Название [PIN]»
    // ownGroup = the list of the channel's own group is open / открыт список группы самого канала
    fun openChannelMenu(ch: Channel, ownGroup: Boolean)
    fun openGroupMenu(cat: Category)
}

class Nav(private val host: NavHost, startInKeyMode: Boolean) {
    var rows: List<LeftRow> = emptyList()
        private set
    var groupSel = 0                    // cursor in the left column / курсор в левой колонке
        private set
    var chanSel = 0                     // cursor in the channel column / курсор в колонке каналов
        private set
    var shown: Category? = null         // whose channels the middle column shows / чьи каналы в средней колонке
        private set
    var column = Pane.GROUPS            // the column with the cursor / колонка, в которой курсор
        private set
    var fullscreen = false
        private set

    var playing: Channel? = null
        private set
    var playCat: Category? = null       // started from it; channels are switched inside it
        private set                     // запущен из неё; внутри неё переключаются каналы
    var playPos = -1
        private set
    var infoTick = 0                    // grows when the channel bar must appear / растёт, когда надо показать плашку
        private set

    // true = the remote is used (the cursor is drawn); false = touch.
    // true = работают с пульта (курсор рисуется); false = пальцем.
    var keyMode = startInKeyMode
        private set

    var playFailed = false              // set by the app: the channel does not play / канал не показывает
    var zapInvert = true                // true: Up = lower number (as in the list) / вверх = меньший номер
    var groupsPerPage = 8               // set by the screen / задаёт экран
    var channelsPerPage = 6

    // ---------- rows / строки ----------

    private fun categoryById(id: String?): Category? =
        if (id == null) null else rows.firstOrNull { it.category?.id == id }?.category

    private fun rowOf(cat: Category?): Int = if (cat == null) -1 else rows.indexOfFirst { it.category === cat }

    // reset = true: a new playlist, everything starts from "All channels".
    // reset = false: the same playlist with new rows (language, favourites, search, modes, an
    // updated copy of the playlist): the cursor, the open category and the playing channel stay
    // where they were, as far as possible.
    // reset = true: новый плейлист, всё начинается со «Всех каналов».
    // reset = false: тот же плейлист с новыми строками (язык, избранное, поиск, режимы, свежая
    // копия плейлиста): курсор, открытая категория и играющий канал остаются на местах,
    // насколько это возможно.
    fun setRows(newRows: List<LeftRow>, reset: Boolean) {
        val oldRow = rows.getOrNull(groupSel)
        val oldShownId = shown?.id
        val oldChannel = shown?.channels?.getOrNull(chanSel)
        val oldPlayId = playCat?.id
        rows = newRows

        if (reset) {
            playing = null; playCat = null; playPos = -1; playFailed = false; fullscreen = false
            val all = categoryById(Catalog.ALL)
            if (all != null) {
                shown = all; groupSel = rowOf(all); chanSel = 0
                column = if (all.channels.isEmpty()) Pane.GROUPS else Pane.CHANNELS
            } else {
                // No channels: the cursor is on "> Playlists" / Каналов нет: курсор на «> Плейлисты»
                shown = null; chanSel = 0; column = Pane.GROUPS
                groupSel = maxOf(0, rows.indexOfFirst { it.kind == RowKind.PLAYLISTS })
            }
            host.navChanged()
            return
        }

        // The open category / Открытая категория
        val cat = categoryById(oldShownId) ?: categoryById(Catalog.ALL)
        shown = cat
        val n = cat?.channels?.size ?: 0
        val keep = cat?.channels?.indexOfChannel(oldChannel) ?: -1
        chanSel = if (keep >= 0) keep else chanSel.coerceIn(0, maxOf(0, n - 1))
        if (n == 0) column = Pane.GROUPS

        // The cursor of the left column / Курсор левой колонки
        var row = -1
        if (oldRow != null) {
            val id = oldRow.category?.id
            row = if (id != null) rows.indexOfFirst { it.category?.id == id }
            else rows.indexOfFirst { it.kind == oldRow.kind }
        }
        if (row < 0 || column == Pane.CHANNELS) row = rowOf(cat)
        groupSel = if (row >= 0) row else 0

        // The playing channel: the same category if it still has it, else "All channels".
        // Играющий канал: та же категория, если он в ней остался, иначе «Все каналы».
        val p = playing
        if (p != null) {
            var pc = categoryById(oldPlayId)
            var pos = pc?.channels?.indexOfChannel(p) ?: -1
            if (pos < 0) {
                pc = categoryById(Catalog.ALL)
                pos = pc?.channels?.indexOfChannel(p) ?: -1
            }
            if (pos >= 0 && pc != null) {
                playing = pc.channels[pos]
                playCat = pc
            } else {
                playCat = null
            }
            playPos = pos
        }
        host.navChanged()
    }

    // Opens a category with the cursor in its channels (search results, the group of a channel,
    // a group after its PIN).
    // Открывает категорию с курсором в её каналах (результаты поиска, группа канала, группа
    // после PIN-кода).
    fun openCategory(id: String, onChannel: Channel?) {
        val cat = categoryById(id) ?: return
        shown = cat
        groupSel = maxOf(0, rowOf(cat))
        val pos = cat.channels.indexOfChannel(onChannel)
        chanSel = if (pos >= 0) pos else 0
        column = if (cat.channels.isEmpty()) Pane.GROUPS else Pane.CHANNELS
        host.navChanged()
    }

    // The channel with this number of the playlist starts in full screen ("All channels").
    // Канал с таким номером в плейлисте включается на весь экран («Все каналы»).
    fun playNumber(n: Int): Boolean {
        val all = categoryById(Catalog.ALL) ?: return false
        val pos = all.channels.indexOfFirst { it.n == n }
        if (pos < 0) return false
        playInFull(all, pos)
        return true
    }

    // The last channel at start: in the category it was started from, else in "All channels".
    // Последний канал при запуске: в категории, из которой его включали, иначе во «Всех каналах».
    fun autoStart(catId: String, name: String, group: String): Boolean {
        val probe = Channel(0, name, group, "", "", "", "", "", "")
        for (id in listOf(catId, Catalog.ALL)) {
            val cat = categoryById(id) ?: continue
            val pos = cat.channels.indexOfChannel(probe)
            if (pos >= 0) {
                playInFull(cat, pos)
                return true
            }
        }
        return false
    }

    private fun playInFull(cat: Category, pos: Int) {
        shown = cat
        groupSel = maxOf(0, rowOf(cat))
        chanSel = pos
        column = Pane.CHANNELS
        start(cat, pos)
        fullscreen = true
        infoTick++
        host.navChanged()
    }

    // ---------- remote / пульт ----------

    // repeat = the key is being held. OK comes here as a short press (the app sends it when
    // the key is released); a long press comes to longOk().
    // repeat = кнопку удерживают. OK приходит сюда коротким нажатием (приложение шлёт его,
    // когда кнопку отпустили); долгое нажатие приходит в longOk().
    fun key(k: NavKey, repeat: Boolean) {
        keyMode = true
        if (fullscreen) fullKey(k, repeat) else listKey(k, repeat)
        host.navChanged()
    }

    private fun listKey(k: NavKey, repeat: Boolean) {
        when (k) {
            NavKey.UP -> move(-1, repeat)
            NavKey.DOWN -> move(1, repeat)
            NavKey.PAGE_UP, NavKey.CH_UP -> page(-1)
            NavKey.PAGE_DOWN, NavKey.CH_DOWN -> page(1)
            NavKey.LEFT -> if (column == Pane.CHANNELS) toGroups()
            NavKey.RIGHT -> if (column == Pane.GROUPS) enterChannels()
            NavKey.OK -> if (!repeat) ok()
        }
    }

    private fun fullKey(k: NavKey, repeat: Boolean) {
        if (repeat) return
        when (k) {
            NavKey.UP, NavKey.CH_UP -> step(if (zapInvert) -1 else 1)
            NavKey.DOWN, NavKey.CH_DOWN -> step(if (zapInvert) 1 else -1)
            NavKey.OK -> if (playFailed) restart() else fold()
            else -> {}
        }
    }

    // One step is cyclic; while the key is held, the cursor stops at the edge.
    // Шаг на одну строку циклический; при удержании кнопки курсор останавливается у края.
    private fun move(d: Int, repeat: Boolean) {
        val n = if (column == Pane.GROUPS) rows.size else (shown?.channels?.size ?: 0)
        if (n == 0) return
        var i = (if (column == Pane.GROUPS) groupSel else chanSel) + d
        if (i < 0 || i >= n) {
            if (repeat) return
            i = (i + n) % n
        }
        if (column == Pane.GROUPS) selectGroup(i) else chanSel = i
    }

    // Paging stops at the edges / Листание страницами останавливается у краёв
    private fun page(d: Int) {
        if (column == Pane.GROUPS) {
            if (rows.isEmpty()) return
            selectGroup((groupSel + d * groupsPerPage).coerceIn(0, rows.size - 1))
        } else {
            val n = shown?.channels?.size ?: 0
            if (n == 0) return
            chanSel = (chanSel + d * channelsPerPage).coerceIn(0, n - 1)
        }
    }

    // The cursor came to a row of the left column: a category shows its channels at once,
    // the cursor of the channels stands on the playing channel if it is there.
    // Курсор пришёл на строку левой колонки: категория сразу показывает свои каналы, курсор
    // каналов стоит на играющем канале, если он там есть.
    private fun selectGroup(i: Int) {
        groupSel = i
        val cat = rows[i].category ?: return
        if (cat === shown) return
        shown = cat
        val pos = cat.channels.indexOfChannel(playing)
        chanSel = if (pos >= 0) pos else 0
    }

    // 1.4.1: leaving the channels for the groups puts the channel cursor back on the playing row.
    // 1.4.1: при уходе из каналов в группы курсор каналов возвращается на играющую строку.
    private fun toGroups() {
        column = Pane.GROUPS
        val cat = shown ?: return
        val pos = cat.channels.indexOfChannel(playing)
        if (pos >= 0) chanSel = pos
    }

    private fun enterChannels() {
        val cat = rows.getOrNull(groupSel)?.category ?: return
        if (cat.channels.isEmpty()) return
        column = Pane.CHANNELS
    }

    private fun ok() {
        if (column == Pane.CHANNELS) {
            activateChannel()
            return
        }
        when (rows.getOrNull(groupSel)?.kind) {
            RowKind.SEARCH -> host.openSearch()
            RowKind.PLAYLISTS -> host.openPlaylists()
            RowKind.LANGUAGE -> host.openLanguage()
            RowKind.SETTINGS -> host.openSettings()
            RowKind.CATEGORY -> {
                val cat = rows[groupSel].category
                if (cat != null && cat.locked && cat.channels.isEmpty()) host.openLockedGroup(cat) else enterChannels()
            }
            null -> {}
        }
    }

    // OK on a channel starts it; OK on the channel that is already playing opens full screen.
    // OK на канале запускает его; OK на канале, который уже играет, открывает полный экран.
    private fun activateChannel() {
        val cat = shown ?: return
        val ch = cat.channels.getOrNull(chanSel) ?: return
        if (sameChannel(playing, ch) && !playFailed) {
            playCat = cat; playPos = chanSel
            fullscreen = true
            infoTick++
        } else {
            start(cat, chanSel)
        }
    }

    private fun start(cat: Category, pos: Int) {
        val ch = cat.channels[pos]
        playing = ch; playCat = cat; playPos = pos; playFailed = false
        host.startChannel(ch)
    }

    private fun restart() {
        val cat = playCat ?: return
        if (playPos in cat.channels.indices) start(cat, playPos)
        infoTick++
    }

    // Next / previous channel of the category in full screen, cyclic.
    // Следующий / предыдущий канал категории на полном экране, по кругу.
    private fun step(d: Int) {
        val cat = playCat ?: return
        val n = cat.channels.size
        if (n == 0) return
        val pos = ((playPos + d) % n + n) % n
        start(cat, pos)
        infoTick++
        if (shown === cat) chanSel = pos
    }

    // 1.4.1: OK in full screen folds the video back to the lists; the cursor goes to the row it
    // was started from (after switching — the playing channel). The video goes on.
    // 1.4.1: OK на полном экране сворачивает видео к спискам; курсор — на строку, с которой
    // запускали (после переключения — на играющий канал). Видео продолжается.
    private fun fold() {
        fullscreen = false
        column = Pane.CHANNELS
        val cat = playCat ?: return
        shown = cat
        chanSel = playPos.coerceIn(0, maxOf(0, cat.channels.size - 1))
        val row = rowOf(cat)
        if (row >= 0) groupSel = row
    }

    // Long OK: the context menu of the place: a channel or a group of the playlist.
    // Долгое OK: контекстное меню места: канал или группа плейлиста.
    fun longOk() {
        keyMode = true
        if (!fullscreen) {
            if (column == Pane.CHANNELS) channelMenu() else groupMenu()
        }
        host.navChanged()
    }

    private fun channelMenu() {
        val cat = shown ?: return
        val ch = cat.channels.getOrNull(chanSel) ?: return
        host.openChannelMenu(ch, cat.id == Catalog.groupId(ch.group))
    }

    private fun groupMenu() {
        val cat = rows.getOrNull(groupSel)?.category ?: return
        if (cat.group != null) host.openGroupMenu(cat)
    }

    // A swipe over the video in full screen (phone): +1 = next channel of the list, -1 = previous.
    // Жест по видео на полном экране (телефон): +1 = следующий канал списка, -1 = предыдущий.
    fun swipe(d: Int) {
        if (!fullscreen) return
        keyMode = false
        step(d)
        host.navChanged()
    }

    // ---------- Back by steps / Back по шагам ----------
    // full screen -> channels -> groups -> stop the video -> exit window
    // полный экран -> каналы -> группы -> остановить видео -> окно выхода
    fun back() {
        when {
            fullscreen -> fold()
            column == Pane.CHANNELS -> toGroups()
            playing != null -> clearPlayback()
            else -> {
                host.openExitDialog()
                return
            }
        }
        host.navChanged()
    }

    private fun clearPlayback() {
        playing = null; playCat = null; playPos = -1; playFailed = false; fullscreen = false
        host.stopChannel()
    }

    // The app goes to the background, the playlist changes, the group of the playing channel was
    // closed: the stream stops, the cursor stays on the channel that was playing.
    // Приложение уходит в фон, меняется плейлист, группу играющего канала закрыли: поток
    // останавливается, курсор остаётся на канале, который играл.
    fun stopPlayback() {
        if (playing == null && !fullscreen) return
        if (fullscreen) fold()
        clearPlayback()
        host.navChanged()
    }

    // ---------- touch / сенсор ----------

    fun tapGroup(i: Int) {
        if (i !in rows.indices) return
        keyMode = false
        column = Pane.GROUPS
        selectGroup(i)
        ok()
        host.navChanged()
    }

    // Touch and hold: the context menu of a group / Долгое нажатие: контекстное меню группы
    fun holdGroup(i: Int) {
        if (i !in rows.indices) return
        keyMode = false
        column = Pane.GROUPS
        selectGroup(i)
        groupMenu()
        host.navChanged()
    }

    // Touch and hold a channel: its context menu / Долгое нажатие на канал: его контекстное меню
    fun holdChannel(i: Int) {
        val n = shown?.channels?.size ?: 0
        if (i !in 0 until n) return
        keyMode = false
        column = Pane.CHANNELS
        chanSel = i
        channelMenu()
        host.navChanged()
    }

    fun tapChannel(i: Int) {
        val n = shown?.channels?.size ?: 0
        if (i !in 0 until n) return
        keyMode = false
        column = Pane.CHANNELS
        chanSel = i
        activateChannel()
        host.navChanged()
    }

    // A tap on the video: from the list to full screen; in full screen it folds back
    // (or retries a channel that did not start).
    // Нажатие на видео: из списка на полный экран; на полном экране сворачивает обратно
    // (или повторяет запуск канала, который не запустился).
    fun tapVideo() {
        if (playing == null) return
        keyMode = false
        if (!fullscreen) {
            fullscreen = true
            infoTick++
        } else if (playFailed) {
            restart()
        } else {
            fold()
        }
        host.navChanged()
    }

    // Where the cursor is, for the log / Где курсор, для журнала
    fun place(): String = if (fullscreen) "full" else if (column == Pane.GROUPS) "groups" else "channels"
}
