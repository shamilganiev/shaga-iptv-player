' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' MainScene: start, screens, loading, categories, keys.
' Playback, favorites, PIN-opened group: MainPlayback.brs.
' Dialogs: MainDialogs.brs. Settings and PIN dialogs: MainSettings.brs.
' MainScene: запуск, экраны, загрузка, категории, кнопки.
' Воспроизведение, избранное, открытая по PIN-коду группа: MainPlayback.brs.
' Диалоги: MainDialogs.brs. Настройки и окна PIN-кода: MainSettings.brs.
'
' Modes / Режимы (m.mode): "welcome" | "main" | "full"
' Focus in main mode / Фокус в главном режиме (m.focusArea): "cat" | "chan"
' Channel column / Колонка каналов (m.view): "channels" | "settings"
'
' Reminder: associative array keys are case-insensitive (m.catList = m.CATLIST).
' Напоминание: ключи ассоциативных массивов не различают регистр.

sub init()
    ' --- all m.* fields first / сначала все поля m.* ---
    m.mode = "welcome"
    m.focusArea = "cat"
    m.view = "channels"
    m.data = invalid
    m.lang = "en"
    m.S = invalid
    m.channels = invalid
    m.groups = invalid
    m.allIdx = []              ' channels of shown groups / каналы показанных групп
    m.favIdx = []              ' favorites among them / избранные среди них
    m.favSet = {}              ' channel name -> true / название канала -> true
    m.cats = []
    m.curIdx = []
    m.curCat = -1
    m.playingIx = -1
    m.loader = invalid
    m.loadingId = ""
    m.loadingSilent = false
    m.currentId = ""
    m.refreshAfterPlay = ""    ' playlist to refresh once video starts / обновить после старта видео
    m.beaconSent = false
    m.pendingDL = invalid
    m.searchIdx = invalid
    m.searchQuery = ""
    m.dialogOpen = false
    m.dlgNode = invalid
    m.dlgKind = ""
    m.dlgCtx = invalid
    m.kbMode = ""
    m.kbId = ""
    m.kbText = ""
    m.videoState = "none"
    m.lastError = ""          ' text of the last playback error / текст последней ошибки показа
    m.openGroup = invalid      ' name of the group opened with the PIN / группа, открытая PIN-кодом
    m.openGroupSeen = invalid  ' roTimespan since it was last viewed / с момента последнего просмотра
    m.settingsPinOk = false    ' PIN entered in this visit to Settings / PIN введён в этом заходе в Настройки
    m.pinFirst = ""
    m.pinAfter = invalid
    m.settingsRows = []
    m.okFromList = false       ' OK already handled by a list / OK уже обработан списком
    m.okClock = CreateObject("roTimespan")
    m.playlistStale = false    ' fresh copy failed, showing the saved one / свежая копия не загрузилась
    m.settingsPage = "main"
    m.arch = invalid           ' archive playback state / состояние показа архива
    m.pendingNew = invalid     ' playlist being added/edited, saved only after it loads / сохраняется после загрузки
    m.kbError = ""             ' load error shown in the link keyboard / ошибка загрузки в окне ссылки

    ' --- nodes / узлы ---
    m.mainGroup = m.top.findNode("mainGroup")
    m.playlistLabel = m.top.findNode("playlistLabel")
    m.catHeader = m.top.findNode("catHeader")
    m.chanHeader = m.top.findNode("chanHeader")
    m.videoHeader = m.top.findNode("videoHeader")
    m.catList = m.top.findNode("catList")
    m.chanList = m.top.findNode("chanList")
    m.spinner = m.top.findNode("spinner")
    m.vSpinner = m.top.findNode("vSpinner")
    m.msgLabel = m.top.findNode("msgLabel")
    m.chLogo = m.top.findNode("chLogo")
    m.chTitle = m.top.findNode("chTitle")
    m.chGroup = m.top.findNode("chGroup")
    m.previewStatus = m.top.findNode("previewStatus")
    m.hintLabel = m.top.findNode("hintLabel")
    m.video = m.top.findNode("video")
    m.osd = m.top.findNode("osd")
    m.osdTitle = m.top.findNode("osdTitle")
    m.osdSub = m.top.findNode("osdSub")
    m.osdHint = m.top.findNode("osdHint")
    m.welcome = m.top.findNode("welcome")
    m.keyCatcher = m.top.findNode("keyCatcher")
    m.catTimer = m.top.findNode("catTimer")
    m.osdTimer = m.top.findNode("osdTimer")
    m.launchTimer = m.top.findNode("launchTimer")
    m.spinTimer = m.top.findNode("spinTimer")
    m.startTimer = m.top.findNode("startTimer")
    m.lockTimer = m.top.findNode("lockTimer")

    for each sp in [m.spinner, m.vSpinner]
        sp.poster.uri = "pkg:/images/spinner.png"
        sp.poster.width = 96
        sp.poster.height = 96
    end for

    cfg = getAppConfig()
    m.spinTimer.duration = cfg.spinnerDelayMs / 1000.0
    m.startTimer.duration = cfg.startTimeoutSec

    m.catList.observeField("itemFocused", "onCatFocused")
    m.catList.observeField("itemSelected", "onCatSelected")
    m.chanList.observeField("itemFocused", "onChanFocused")
    m.chanList.observeField("itemSelected", "onChanSelected")
    m.video.observeField("state", "onVideoState")
    m.catTimer.observeField("fire", "onCatTimer")
    m.osdTimer.observeField("fire", "onOsdTimer")
    m.launchTimer.observeField("fire", "onLaunchTimer")
    m.spinTimer.observeField("fire", "onSpinTimer")
    m.startTimer.observeField("fire", "onStartTimer")
    m.lockTimer.observeField("fire", "onLockTimer")

    applyDialogPalette()
    epgInit()
    guideInit()

    ' --- saved data and language / сохранённые данные и язык ---
    m.data = storageLoad()
    rebuildFavSet()
    applyLanguage()
    dbg("APP", "saved playlists=" + m.data.playlists.Count().ToStr() + " lastOk=" + m.data.lastOkId + " lang=" + m.lang + " pin=" + (m.data.pin <> "").ToStr() + " favorites=" + m.data.favorites.Count().ToStr() + " zap=" + m.data.zapMode)
    m.lockTimer.control = "start"

    if m.data.playlists.Count() = 0 then
        showWelcome()
        sendLaunchBeacon("welcome")
    else
        showMain()
        m.launchTimer.control = "start"
        openPlaylist(startupPlaylistId())
    end if
end sub

' Last successfully loaded playlist, else the first one
' Последний успешно загруженный плейлист, иначе первый
function startupPlaylistId() as string
    if m.data.lastOkId <> "" and storageFindIndex(m.data, m.data.lastOkId) >= 0 then return m.data.lastOkId
    return m.data.playlists[0].id
end function

' ---------- launch beacon / бикон запуска ----------

sub sendLaunchBeacon(reason as string)
    if m.beaconSent then return
    m.beaconSent = true
    m.launchTimer.control = "stop"
    m.top.signalBeacon("AppLaunchComplete")
    dbg("APP", "AppLaunchComplete (" + reason + ")")
end sub

sub onLaunchTimer()
    ' Safety: home screen with a loading indicator is usable after 10 s
    ' Страховка: главный экран с индикатором загрузки готов через 10 с
    sendLaunchBeacon("timer")
end sub

' ---------- language / язык ----------

sub applyLanguage()
    m.lang = resolveLanguage(m.data.lang)
    m.S = getStrings(m.lang)
    m.top.findNode("wTitle").text = m.S.welcomeTitle
    m.top.findNode("wText").text = m.S.welcomeText
    m.top.findNode("wHint").text = m.S.welcomeHint
    m.top.findNode("wTip").text = m.S.welcomeQrTip
    m.hintLabel.text = m.S.hintMain
    m.osdHint.text = m.S.hintFull
end sub

' ---------- screens / экраны ----------

sub showWelcome()
    m.mode = "welcome"
    stopVideo()
    m.mainGroup.visible = false
    m.osd.visible = false
    m.welcome.visible = true
    m.keyCatcher.setFocus(true)
    dbg("UI", "welcome screen")
end sub

sub showMain()
    m.mode = "main"
    m.welcome.visible = false
    m.osd.visible = false
    m.mainGroup.visible = true
    setVideoWindow()
    if m.channels = invalid then buildCategories(-1)
    focusArea(m.focusArea)
end sub

sub focusArea(area as string)
    if area = "chan" and m.view = "channels" and m.curIdx.Count() = 0 then area = "cat"
    m.focusArea = area
    if m.mode <> "main" or m.dialogOpen then return
    if area = "prog" then
        m.progList.setFocus(true)
    else if area = "chan" then
        m.chanList.setFocus(true)
    else
        m.catList.setFocus(true)
    end if
end sub

' Show spinner + text in the channel column / Спиннер и текст в колонке каналов
sub showMessage(text as string, busy as boolean)
    m.msgLabel.text = text
    m.msgLabel.visible = (text <> "")
    m.spinner.visible = busy
    if busy then m.spinner.control = "start" else m.spinner.control = "stop"
end sub

' ---------- column headers / заголовки колонок ----------

sub updateCatHeader()
    i = m.catList.itemFocused
    if i >= 0 and i < m.cats.Count() then
        m.catHeader.text = m.cats[i].label
    else
        m.catHeader.text = ""
    end if
end sub

' Channels: "Now watching: X" while playing, else the focused channel.
' Video: programme on the playing channel, else on the focused one
' (until the TV guide exists: the channel name).
' Каналы: «Сейчас смотрите: X» во время показа, иначе выделенный канал.
' Видео: передача на играющем канале, иначе на выделенном
' (пока телепрограммы нет — название канала).
sub updateChanHeader()
    ' Top line: what is playing (live or archive) / Верхняя строка: что показывается
    if m.playingIx >= 0 and m.channels <> invalid then
        m.playlistLabel.text = m.S.nowWatching + " " + m.channels[m.playingIx].t
    else
        m.playlistLabel.text = ""
    end if
    focusedIx = focusedChannel()
    if m.view = "settings" then
        h = m.S.settingsTitle
        if m.settingsPage = "playlist" then h = h + "  >  " + m.S.playlistRow
        if m.settingsPage = "groups" then h = h + "  >  " + m.S.groupsRow
        m.chanHeader.text = h
    else if focusedIx >= 0 then
        m.chanHeader.text = m.channels[focusedIx].t
    else
        m.chanHeader.text = ""
    end if
    target = -1
    if m.playingIx >= 0 and m.channels <> invalid then
        target = m.playingIx
    else if m.view = "channels" then
        target = focusedIx
    end if
    if target < 0 then
        m.videoHeader.text = ""
    else
        pr = epgNowOf(target)
        if target = m.playingIx and m.arch <> invalid then
            m.videoHeader.text = m.S.archLabel + ": " + m.arch.t
        else if pr <> invalid then
            m.videoHeader.text = pr.t
        else
            m.videoHeader.text = m.channels[target].t
        end if
    end if
end sub

' Channel index under the focus in the channel list, -1 if none
' Номер канала под фокусом в списке каналов, -1 если нет
function focusedChannel() as integer
    if m.view <> "channels" or m.channels = invalid then return -1
    p = m.chanList.itemFocused
    if p >= 0 and p < m.curIdx.Count() then return m.curIdx[p]
    return -1
end function

' ---------- loading / загрузка ----------
' openPlaylist: saved copy (cache) first, then the network.
' openPlaylist: сначала сохранённая копия (кэш), потом сеть.

sub openPlaylist(id as string)
    startLoad(id, "cache", true)
end sub

' mode "cache" | "net"; silent = keep the current list, no spinner
' mode "cache" | "net"; silent — оставить текущий список, без спиннера
sub startLoad(id as string, mode as string, silent as boolean)
    if m.pendingNew <> invalid and m.pendingNew.id = id then
        p = m.pendingNew
    else
        i = storageFindIndex(m.data, id)
        if i < 0 then return
        p = m.data.playlists[i]
    end if
    m.loadingId = id
    m.loadingSilent = silent
    dbg("LOAD", "request " + mode + " '" + p.name + "' " + maskUrl(p.url))
    if m.loader <> invalid then m.loader.unobserveField("result")
    m.loader = CreateObject("roSGNode", "PlaylistLoader")
    m.loader.playlistId = id
    m.loader.url = p.url
    m.loader.mode = mode
    m.loader.observeField("result", "onLoaded")
    if not silent then
        if m.channels = invalid then
            m.chanList.content = invalid
            m.curIdx = []
        end if
        showMessage(p.name + Chr(10) + m.S.loading, true)
    end if
    m.loader.control = "RUN"
end sub

sub onLoaded()
    r = m.loader.result
    if r = invalid or r.playlistId <> m.loadingId then return
    id = r.playlistId
    m.loadingId = ""
    if m.pendingNew <> invalid and m.pendingNew.id = id and r.fromCache <> true then
        if not commitPending(r) then return
    end if
    i = storageFindIndex(m.data, id)
    name = ""
    if i >= 0 then name = m.data.playlists[i].name

    if r.fromCache = true then
        if r.ok = true then
            ' Show the saved copy now, refresh from the network after the video starts
            ' Сразу показываем сохранённую копию, из сети обновляем после старта видео
            applyChannels(r, name)
            sendLaunchBeacon("cache")
            if autoplayLast() then
                m.refreshAfterPlay = id
            else
                startLoad(id, "net", true)
            end if
        else
            startLoad(id, "net", m.channels <> invalid and m.currentId = id)
        end if
        return
    end if

    if r.ok = true then
        m.data.lastOkId = id
        storageSave(m.data)
        if m.channels <> invalid and m.currentId = id then
            swapChannels(r, name)
        else
            applyChannels(r, name)
            sendLaunchBeacon("loaded")
            autoplayLast()
        end if
        tryDeepLink()
    else
        dbg("ERR", "playlist '" + name + "' error=" + r.error)
        if m.channels <> invalid and m.currentId = id then
            ' Showing the saved copy; say it could not be updated
            ' Показана сохранённая копия; сообщаем, что обновить не удалось
            showMessage("", false)
            m.playlistStale = true
        else if m.channels = invalid then
            text = name + Chr(10) + Chr(10) + m.S.errTitle + Chr(10) + Chr(10) + loadErrorText(r) + Chr(10) + Chr(10) + m.S.errHint
            showMessage(text, false)
            buildCategories(1)
            focusArea("cat")
        else
            showMessage("", false)
            showInfoDialog(m.S.errTitle, [loadErrorText(r)])
        end if
        sendLaunchBeacon("load-error")
    end if
end sub

' New or edited playlist: save it only if it loaded; otherwise back to the link with the error
' Новый или изменённый плейлист: сохраняем только после загрузки, иначе обратно к ссылке с ошибкой
function commitPending(r as object) as boolean
    pn = m.pendingNew
    m.pendingNew = invalid
    if r.ok <> true then
        dbg("ERR", "new playlist link did not load: " + r.error)
        showMessage("", false)
        m.kbError = m.S.errTitle + ": " + loadErrorText(r)
        openUrlKeyboard(pn.mode, pn.id, pn.url)
        sendLaunchBeacon("load-error")
        return false
    end if
    if pn.mode = "add" then
        m.data.playlists.push({ id: pn.id, name: pn.name, url: pn.url })
        m.data.nextId = m.data.nextId + 1
    else
        i = storageFindIndex(m.data, pn.id)
        if i >= 0 then
            m.data.playlists[i].url = pn.url
            m.data.playlists[i].name = pn.name
        end if
        if m.currentId = pn.id then m.currentId = ""     ' new link: show it fresh / новая ссылка: показать заново
    end if
    storageSave(m.data)
    dbg("UI", "playlist saved " + pn.id + " (" + pn.mode + ")")
    return true
end function

' First show of a playlist / Первый показ плейлиста
sub applyChannels(r as object, name as string)
    stopVideo()
    m.channels = r.channels
    m.groups = r.groups
    m.searchIdx = invalid
    m.searchQuery = ""
    m.currentId = r.playlistId
    m.openGroup = invalid
    showMessage("", false)
    m.playlistStale = false
    m.view = "channels"
    buildCategories(-1)
    selectFirstGroup()
    if m.mode = "main" then focusArea("chan")
    epgPlaylistShown(r.epgUrl)
    dbg("LOAD", "shown " + m.channels.Count().ToStr() + " channels (cache=" + (r.fromCache = true).ToStr() + ")")
end sub

' Fresh copy of the playlist on screen: keep the playing channel and the open category
' Свежая копия показанного плейлиста: сохраняем играющий канал и открытую категорию
sub swapChannels(r as object, name as string)
    playKey = invalid
    if m.playingIx >= 0 then playKey = { t: m.channels[m.playingIx].t, g: m.channels[m.playingIx].g }
    catKey = invalid
    if m.curCat >= 0 and m.curCat < m.cats.Count() then
        c = m.cats[m.curCat]
        catKey = { kind: c.kind, g: "" }
        if c.kind = "group" then catKey.g = m.groups[c.gi].name
    end if
    m.channels = r.channels
    m.groups = r.groups
    m.searchIdx = invalid
    m.playingIx = -1
    if playKey <> invalid then
        k = findChannel(playKey.t, playKey.g)
        if k >= 0 and m.channels[k].u = m.video.content.url then
            m.playingIx = k
        else
            stopVideo()
        end if
    end if
    m.playlistStale = false
    if m.searchQuery <> "" then
        ' Search results recalculated on the fresh list / Результаты поиска — по свежему списку
        buildCategories(-1)
        m.searchIdx = searchIndexes(m.searchQuery)
    end if
    epgPlaylistShown(r.epgUrl)
    if m.view = "settings" then
        buildCategories(m.catList.itemFocused)
        buildSettingsRows(m.chanList.itemFocused)
    else
        buildCategories(-1)
        i = -1
        if catKey <> invalid then
            if catKey.kind = "group" then
                gi = groupIndexByName(catKey.g)
                if gi >= 0 then i = groupCat(gi)
            else
                i = catIndexByKind(catKey.kind)
            end if
        end if
        if i < 0 then i = firstChannelCat()
        if i >= 0 then
            m.catList.jumpToItem = i
            showCategory(i)
        end if
    end if
    dbg("LOAD", "refreshed " + m.channels.Count().ToStr() + " channels, playing kept=" + (m.playingIx >= 0).ToStr())
end sub

function loadErrorText(r as object) as string
    if r.error = "timeout" then return m.S.errTimeout
    if r.error = "http" then return m.S.errHttp + " (HTTP " + r.httpCode.ToStr() + ")."
    if r.error = "empty" then return m.S.errEmpty
    if r.error = "nochannels" then return m.S.errNoChannels
    return m.S.errNetwork
end function

function playlistLabelText() as string
    i = storageFindIndex(m.data, m.currentId)
    if i < 0 or m.channels = invalid then return ""
    return m.data.playlists[i].name + "  ·  " + m.channels.Count().ToStr() + " " + m.S.channels
end function

sub restorePlaylistLabel()
    updateChanHeader()
end sub

' Forget the loaded playlist (e.g. after deleting it) / Сбросить загруженный плейлист
sub clearChannels()
    stopVideo()
    m.channels = invalid
    m.groups = invalid
    m.allIdx = []
    m.favIdx = []
    m.currentId = ""
    m.refreshAfterPlay = ""
    m.openGroup = invalid
    m.searchIdx = invalid
    m.searchQuery = ""
    m.curIdx = []
    m.curCat = -1
    m.view = "channels"
    m.chanList.content = invalid
    m.playlistStale = false
    showMessage("", false)
    clearPreview()
end sub

' ---------- groups / группы ----------

function groupIndexByName(name as string) as integer
    if m.groups = invalid then return -1
    for gi = 0 to m.groups.Count() - 1
        if m.groups[gi].name = name then return gi
    end for
    return -1
end function

' Channel by title + group name, -1 if absent / Канал по названию и группе, -1 если нет
function findChannel(title as string, group as string) as integer
    if m.channels = invalid then return -1
    for k = 0 to m.channels.Count() - 1
        if m.channels[k].t = title and m.channels[k].g = group then return k
    end for
    return -1
end function

' Channels of this group can be listed now / Каналы группы можно показать сейчас
function groupIsOpen(gi as integer) as boolean
    name = m.groups[gi].name
    mode = groupMode(m.data, name)
    if mode = "s" then return true
    if mode = "p" and m.openGroup <> invalid then
        if m.openGroup = name then return true
    end if
    return false
end function

' ---------- categories / категории ----------
' m.cats[i] = { kind: "action", action: "search"|"playlists"|"language"|"settings" }
'           | { kind: "search" } | { kind: "fav" } | { kind: "all" }
'           | { kind: "group", gi: n, locked: bool }
' "All", search, favorites, autoplay and deep links use only SHOWN groups.
' «Все каналы», поиск, избранное, автозапуск и deep link — только ПОКАЗАННЫЕ группы.

sub buildCategories(focusIndex as integer)
    m.cats = [
        { kind: "action", action: "search", label: "> " + m.S.menuSearch }
        { kind: "action", action: "playlists", label: "> " + m.S.menuPlaylists }
        { kind: "action", action: "language", label: "> " + m.S.menuLanguage }
        { kind: "action", action: "settings", label: "> " + m.S.menuSettings }
    ]
    m.allIdx = []
    if m.channels <> invalid then
        shown = []
        groupCats = []
        for gi = 0 to m.groups.Count() - 1
            g = m.groups[gi]
            mode = groupMode(m.data, g.name)
            shown.push(mode = "s")
            nm = g.name
            if nm = "" then nm = m.S.ungrouped
            if mode = "s" then
                groupCats.push({ kind: "group", gi: gi, locked: false, label: nm + "  (" + g.idx.Count().ToStr() + ")" })
            else if mode = "p" then
                ' PIN group: lock mark, no channel count / Группа по PIN: пометка, без счётчика
                groupCats.push({ kind: "group", gi: gi, locked: not groupIsOpen(gi), label: nm + "  " + m.S.lockedMark })
            end if
        end for
        for k = 0 to m.channels.Count() - 1
            if shown[m.channels[k].gi] then m.allIdx.push(k)
        end for
        rebuildFavIdx()
        if m.searchIdx <> invalid then
            m.cats.push({ kind: "search", label: m.S.searchPrefix + " " + m.searchQuery + "  (" + m.searchIdx.Count().ToStr() + ")" })
        end if
        m.cats.push({ kind: "fav", label: m.S.favorites + "  (" + m.favIdx.Count().ToStr() + ")" })
        m.cats.push({ kind: "all", label: m.S.allChannels + "  (" + m.allIdx.Count().ToStr() + ")" })
        m.cats.Append(groupCats)
    end if
    items = []
    for each c in m.cats
        items.push({ title: c.label })
    end for
    root = CreateObject("roSGNode", "ContentNode")
    root.Update({ children: items }, true)
    m.catList.content = root
    if focusIndex >= 0 and focusIndex < m.cats.Count() then m.catList.jumpToItem = focusIndex
    updateCatHeader()
end sub

' Index of the first channel category / Первая категория с каналами
function firstChannelCat() as integer
    for i = 0 to m.cats.Count() - 1
        if m.cats[i].kind <> "action" then return i
    end for
    return -1
end function

function catIndexByKind(kind as string) as integer
    for i = 0 to m.cats.Count() - 1
        if m.cats[i].kind = kind then return i
    end for
    return -1
end function

function allChannelsCat() as integer
    return catIndexByKind("all")
end function

' Index of a group category by group index / Номер категории по номеру группы
function groupCat(gi as integer) as integer
    for i = 0 to m.cats.Count() - 1
        if m.cats[i].kind = "group" and m.cats[i].gi = gi then return i
    end for
    return -1
end function

' Start on the group of the last channel (it will autoplay), else "All channels".
' One jump only: two jumps in a row left the list on the first one and the
' channels were built twice (1.3.0).
' Начинаем с группы последнего канала (он включится сам), иначе «Все каналы».
' Один переход: два перехода подряд оставляли список на первом, каналы строились дважды.
sub selectFirstGroup()
    i = lastChannelCat()
    if i < 0 then i = allChannelsCat()
    if i < 0 then i = firstChannelCat()
    if i < 0 then return
    m.catList.jumpToItem = i
    showCategory(i)
end sub

' Category of the last channel if its group is shown, else -1 / Категория последнего канала или -1
function lastChannelCat() as integer
    lc = m.data.lastChannel
    if lc = invalid or m.channels = invalid then return -1
    k = findChannel(lc.n, lc.g)
    if k < 0 or groupMode(m.data, lc.g) <> "s" then return -1
    return groupCat(m.channels[k].gi)
end function

sub onCatFocused()
    updateCatHeader()
    m.catTimer.control = "stop"
    m.catTimer.control = "start"
end sub

' Debounced: rebuild channels only after the user stops scrolling
' С задержкой: перестраиваем каналы, когда пользователь перестал листать
sub onCatTimer()
    if m.view <> "channels" then return
    i = m.catList.itemFocused
    if i < 0 or i >= m.cats.Count() then return
    if m.cats[i].kind = "action" then return
    if i <> m.curCat then showCategory(i)
end sub

sub onCatSelected()
    markOkFromList()
    i = m.catList.itemSelected
    if i < 0 or i >= m.cats.Count() then return
    c = m.cats[i]
    dbg("KEY", "category OK #" + i.ToStr() + " kind=" + c.kind)
    if c.kind = "action" then
        if c.action = "search" then
            openSearchKeyboard()
        else if c.action = "playlists" then
            openPlaylistsDialog()
        else if c.action = "language" then
            openLanguageDialog()
        else if c.action = "settings" then
            requestSettings()
        end if
    else if c.kind = "group" and c.locked = true then
        openPinCheck({ action: "group", gi: c.gi })
    else
        if m.view <> "channels" then leaveSettings(false)
        if i <> m.curCat then showCategory(i)
        focusArea("chan")
    end if
end sub

sub showCategory(i as integer)
    c = m.cats[i]
    m.curCat = i
    if c.kind = "group" and not groupIsOpen(c.gi) then
        ' Locked group: no channel names until the PIN / Закрытая группа: без названий до PIN-кода
        m.curIdx = []
        m.chanList.content = invalid
        showMessage("", false)
        clearPreview()
        updateChanHeader()
        return
    end if
    if c.kind = "all" then
        idx = m.allIdx
    else if c.kind = "fav" then
        idx = m.favIdx
    else if c.kind = "search" then
        idx = m.searchIdx
    else
        idx = m.groups[c.gi].idx
    end if
    showMessage("", false)
    m.curIdx = idx
    t0 = CreateObject("roTimespan")
    ' Rows without the programme (fast); the programme is filled around the cursor below
    ' Строки без передачи (быстро); передача подставляется около курсора ниже
    items = []
    for each k in idx
        ch = m.channels[k]
        items.push({ title: ch.t, HDPosterUrl: ch.l, shortDescriptionLine1: (k + 1).ToStr(), shortDescriptionLine2: "", epgS: 0, epgE: 0 })
    end for
    root = CreateObject("roSGNode", "ContentNode")
    root.Update({ children: items }, true)
    m.chanList.content = root
    dbg("UI", "category #" + i.ToStr() + " " + idx.Count().ToStr() + " channels in " + t0.TotalMilliseconds().ToStr() + " ms")
    if idx.Count() > 0 then
        posInCat = indexOfChannel(m.playingIx)
        if posInCat < 0 then posInCat = 0
        m.chanList.jumpToItem = posInCat
        showPreviewInfo(idx[posInCat])
        refreshRows(40)
    else
        clearPreview()
    end if
    updateChanHeader()
end sub

' Position of a channel in the current category, -1 if absent / Позиция канала в категории
function indexOfChannel(ix as integer) as integer
    if ix < 0 then return -1
    for p = 0 to m.curIdx.Count() - 1
        if m.curIdx[p] = ix then return p
    end for
    return -1
end function

' ---------- channels and preview / каналы и предпросмотр ----------

sub onChanFocused()
    if m.view = "settings" then return
    p = m.chanList.itemFocused
    if p < 0 or p >= m.curIdx.Count() then return
    showPreviewInfo(m.curIdx[p])
    updateChanHeader()
    refreshRows(12)
end sub

sub onChanSelected()
    markOkFromList()
    p = m.chanList.itemSelected
    if m.view = "settings" then
        onSettingsRowSelected(p)
        return
    end if
    if p < 0 or p >= m.curIdx.Count() then return
    ix = m.curIdx[p]
    dbg("KEY", "channel OK #" + (ix + 1).ToStr())
    if ix = m.playingIx and (m.videoState = "playing" or m.videoState = "buffering" or m.videoState = "paused") then
        enterFull()
    else
        playChannel(ix)
    end if
end sub

' Column 3 follows the playing channel, else the focused one (see MainEpg.brs)
' Колонка 3 — играющий канал, иначе выделенный (см. MainEpg.brs)
sub showPreviewInfo(ix as integer)
    refreshInfoPanel()
    updatePreviewStatus()
end sub

sub clearPreview()
    m.chTitle.text = ""
    m.chGroup.text = ""
    m.chLogo.uri = ""
    m.previewStatus.text = ""
    clearProgramme()
end sub

sub updatePreviewStatus()
    if m.view = "settings" then return
    focusedIx = focusedChannel()
    if focusedIx < 0 then
        m.previewStatus.text = ""
    else if focusedIx = m.playingIx and m.videoState = "error" then
        m.previewStatus.text = m.lastError
    else if focusedIx = m.playingIx and m.arch <> invalid then
        m.previewStatus.text = m.S.archLabel + ": " + hhmm(m.arch.s) + " – " + hhmm(m.arch.e) + "  " + m.arch.t
    else if focusedIx = m.playingIx then
        m.previewStatus.text = m.S.previewPlaying
    else
        m.previewStatus.text = m.S.previewIdle
    end if
end sub

' ---------- search / поиск ----------

sub runSearch(query as string)
    qn = normText(query)
    if qn = "" or m.channels = invalid then return
    res = searchIndexes(query)
    dbg("UI", "search '" + qn + "' -> " + res.Count().ToStr())
    if res.Count() = 0 then
        showInfoDialog(m.S.searchTitle, [m.S.noResults + " «" + query + "»."])
        return
    end if
    if m.view <> "channels" then leaveSettings(false)
    m.searchIdx = res
    m.searchQuery = query
    buildCategories(-1)
    i = catIndexByKind("search")
    m.catList.jumpToItem = i
    showCategory(i)
    focusArea("chan")
end sub

' Channels of shown groups matching the query / Каналы показанных групп по запросу
function searchIndexes(query as string) as object
    qn = normText(query)
    qf = fuzzTranslit(qn)
    res = []
    if qn = "" then return res
    for each k in m.allIdx
        if keyMatches(m.channels[k].k, qn, qf) then res.push(k)
    end for
    return res
end function

' ---------- deep linking ----------

sub onDeepLink()
    dl = m.top.deepLink
    if dl = invalid or dl.contentId = invalid then return
    m.pendingDL = dl
    tryDeepLink()
end sub

sub tryDeepLink()
    if m.pendingDL = invalid then return
    if m.channels = invalid then
        if m.loadingId = "" then
            dbg("DL", "no playlist loaded, deep link ignored")
            m.pendingDL = invalid
        end if
        return
    end if
    cid = LCase(m.pendingDL.contentId)
    m.pendingDL = invalid
    found = -1
    for each k in m.allIdx      ' only shown groups / только показанные группы
        ch = m.channels[k]
        if LCase(ch.id) = cid or LCase(ch.t) = cid then
            found = k
            exit for
        end if
    end for
    if found < 0 then
        dbg("DL", "contentId not found: " + cid)
        if m.mode = "main" then m.previewStatus.text = m.S.dlNotFound
        return
    end if
    dbg("DL", "play #" + (found + 1).ToStr())
    closeDialog()
    if m.view <> "channels" then leaveSettings(false)
    i = allChannelsCat()
    m.catList.jumpToItem = i
    showCategory(i)
    if m.mode <> "main" then showMain()
    playChannel(found)
    enterFull()
end sub

' ---------- keys / кнопки ----------
' OK in a list fires itemSelected AND bubbles here. markOkFromList() remembers
' that the list already handled it, so the scene skips that same press.
' OK в списке вызывает itemSelected И всплывает сюда. markOkFromList() запоминает,
' что список уже обработал нажатие, и сцена его пропускает.

sub markOkFromList()
    m.okFromList = true
    m.okClock.Mark()
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.dialogOpen then
        ' Safety: the flag is set but no dialog is on screen / Страховка: флаг стоит, а окна нет
        if m.top.dialog = invalid then
            dbg("UI", "dialog flag reset: no dialog on screen")
            m.dialogOpen = false
            m.dlgNode = invalid
            m.dlgKind = ""
            m.dlgCtx = invalid
        else
            return false
        end if
    end if

    if key = "OK" and m.okFromList then
        m.okFromList = false
        if m.okClock.TotalMilliseconds() < 1000 then return true
    end if
    m.okFromList = false

    if m.mode = "main" then
        dbg("KEY", key + " main/" + m.focusArea)
    else
        dbg("KEY", key + " " + m.mode)
    end if

    if m.mode = "welcome" then
        if key = "OK" then
            openUrlKeyboard("add", "", "")
            return true
        else if key = "back" then
            requestExit()
            return true
        end if
        return false
    end if

    if m.mode = "full" then
        if key = "back" then
            exitFull()
        else if m.arch <> invalid and (key = "left" or key = "right" or key = "rewind" or key = "fastforward" or key = "replay") then
            ' Archive: seek by restarting the link / Архив: перемотка перезапуском ссылки
            if key = "left" then seekArchive(-60)
            if key = "right" then seekArchive(60)
            if key = "rewind" then seekArchive(-300)
            if key = "fastforward" then seekArchive(300)
            if key = "replay" then seekArchive(-15)
        else if key = "up" or key = "channelUp" then
            zap(zapDelta(true))
        else if key = "down" or key = "channelDown" then
            zap(zapDelta(false))
        else if key = "OK" then
            showOsd()
        else if key = "left" then
            seekBy(-10)
        else if key = "right" then
            seekBy(10)
        else if key = "rewind" then
            seekBy(-30)
        else if key = "fastforward" then
            seekBy(30)
        else if key = "replay" then
            seekBy(-15)
        else if key = "play" then
            togglePause()
        else if key = "options" then
            return false     ' reserved for Roku during video / во время видео — за системой Roku
        end if
        return true
    end if

    ' main / главный экран
    if key = "down" or key = "up" then
        return wrapList(key)
    else if key = "options" then
        openContextMenu()
        return true
    else if m.focusArea = "prog" and (key = "back" or key = "left") then
        closeGuide()
        return true
    else if key = "right" and m.focusArea = "chan" and m.view = "channels" then
        openGuide(focusedChannel())
        return true
    else if key = "back" then
        ' Step back: (programme list ->) channels -> groups -> stop video -> exit window
        ' Шаг назад: (список передач ->) каналы -> группы -> остановить видео -> окно выхода
        if m.focusArea = "chan" then
            if settingsBack() then return true
            if m.view = "settings" then leaveSettings(true)
            focusArea("cat")
        else if m.playingIx >= 0 then
            dbg("UI", "back: stop video")
            stopVideo()
            updatePreviewStatus()
        else
            openExitDialog()
        end if
        return true
    else if key = "right" and m.focusArea = "cat" then
        focusArea("chan")
        return true
    else if key = "left" and m.focusArea = "chan" then
        if m.view = "settings" then leaveSettings(true)
        focusArea("cat")
        return true
    else if key = "play" then
        if m.playingIx >= 0 then
            togglePause()
            return true
        end if
    else if key = "replay" then
        seekBy(-15)
        return true
    end if
    return false
end function

' Cyclic scrolling: the list did not handle up/down at its end, so we wrap.
' One place for both columns (and Settings, which live in the channel column).
' Циклическая прокрутка: список не обработал вверх/вниз на краю — переходим
' на другой конец. Одно место для обеих колонок (и Настроек в колонке каналов).
function wrapList(key as string) as boolean
    if m.focusArea = "prog" then
        lst = m.progList
    else if m.focusArea = "chan" then
        lst = m.chanList
    else
        lst = m.catList
    end if
    if lst.content = invalid then return false
    n = lst.content.getChildCount()
    if n < 2 then return false
    i = lst.itemFocused
    if key = "down" and i = n - 1 then
        lst.jumpToItem = 0
        return true
    else if key = "up" and i = 0 then
        lst.jumpToItem = n - 1
        return true
    end if
    return false
end function
