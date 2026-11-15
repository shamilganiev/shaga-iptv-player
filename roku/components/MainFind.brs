' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' 1.4.0: search. "> Search" turns the left column into three kinds of search:
' channels, programmes (by title), descriptions (first 200 characters).
' Masks: * — any number of characters, ? — one character (see buildFindRegex in textutil.brs).
' Programme results are shown in the channel column with taller rows: channel, programme
' title, "Yesterday: 11/14/2026 at 13:45" coloured by time (past / on air / future).
' Column 3 shows the TV guide of the focused result with the found programme in the middle.
' Channels of groups marked 18+ are never searched.
' 1.4.0: поиск. «> Поиск» превращает левую колонку в три вида поиска: каналы, передачи
' (по названию), описания (первые 200 символов). Маски: * — любое число символов, ? — один.
' Результаты поиска передач — в колонке каналов, строки выше: канал, название передачи,
' «Вчера: 11/14/2026 в 13:45» цветом по времени. В колонке 3 — программа канала с найденной
' передачей посередине. Каналы групп с отметкой 18+ не ищутся никогда.

sub findInit()
    m.catSub = ""            ' "search" while the left column shows the search menu / меню поиска в левой колонке
    m.subCats = []
    m.findKind = "chan"      ' chan | title | desc — what the keyboard searches / что ищет клавиатура
    m.findItems = invalid    ' results of the programme search / результаты поиска передач
    m.findIdx = []           ' channel index of every result / номер канала для каждого результата
    m.findQuery = ""
    m.findResKind = ""
    m.findTotal = 0
    m.findShown = false      ' the channel column shows programme results / в колонке каналов — результаты
    m.findWant = 0           ' start time of the programme to put in the middle of the guide
    m.findPeek = false       ' guide is shown without taking the focus / программа показана без фокуса
    m.centerTo = -1
    m.findBusy = false
    m.epgSvc.observeField("findAns", "onFindAns")
    m.centerTimer = m.top.findNode("centerTimer")
    m.centerTimer.observeField("fire", "onCenterTimer")
end sub

' ---------- search menu in the left column / меню поиска в левой колонке ----------

sub openSearchMenu()
    if m.channels = invalid then return
    m.catSub = "search"
    m.subCats = [
        { action: "findChan", label: m.S.findChan }
        { action: "findTitle", label: m.S.findTitle }
        { action: "findDesc", label: m.S.findDesc }
        { action: "back", label: m.S.findBack }
    ]
    renderSearchMenu()
    m.catList.jumpToItem = 0
    dbg("UI", "search menu opened")
end sub

sub renderSearchMenu()
    items = []
    for each c in m.subCats
        items.push({ title: c.label, playing: false })
    end for
    root = CreateObject("roSGNode", "ContentNode")
    root.Update({ children: items }, true)
    m.catList.content = root
    m.catHeader.text = m.S.menuSearch
end sub

' focusKind: category kind to put the cursor on ("" = the "> Search" row)
' focusKind: на какую категорию поставить курсор ("" — строка «> Поиск»)
sub closeSearchMenu(focusKind as string)
    if m.catSub = "" then return
    m.catSub = ""
    i = 0
    buildCategories(-1)
    if focusKind <> "" then i = catIndexByKind(focusKind)
    if i < 0 then i = 0
    m.catList.jumpToItem = i
    dbg("UI", "search menu closed")
end sub

sub onSearchMenuSelected(i as integer)
    if i < 0 or i >= m.subCats.Count() then return
    a = m.subCats[i].action
    dbg("KEY", "search menu " + a)
    if a = "back" then
        closeSearchMenu("")
    else if a = "findChan" then
        openFindKeyboard("chan")
    else if a = "findTitle" then
        openFindKeyboard("title")
    else if a = "findDesc" then
        openFindKeyboard("desc")
    end if
end sub

sub openFindKeyboard(kind as string)
    m.findKind = kind
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    if kind = "chan" then
        dlg.title = m.S.findChan
        dlg.text = m.searchQuery
    else
        if kind = "title" then dlg.title = m.S.findTitle else dlg.title = m.S.findDesc
        dlg.text = m.findQuery
    end if
    dlg.message = [m.S.findMaskHint]
    dlg.buttons = [m.S.ok, m.S.cancel]
    showDialog(dlg, "search", invalid)
end sub

' Called with the text of the keyboard / Вызывается с текстом клавиатуры
sub runFindText(txt as string)
    if m.findKind = "chan" then
        runSearch(txt)
    else
        runFind(m.findKind, txt)
    end if
end sub

' Is the group of this channel marked 18+? Cached per group / Отметка 18+ у группы канала
function adultGroupFlags() as object
    flags = []
    for each g in m.groups
        flags.push(groupIsAdult(m.data, g.name))
    end for
    return flags
end function

sub runFind(kind as string, query as string)
    if buildFindRegex(query) = invalid or m.channels = invalid then return
    st = m.epgStatus.state
    if st <> "ready" and st <> "loading" then
        showInfoDialog(m.S.findTitle, [epgStatusText()])
        return
    end if
    ' Allowed channels: shown groups without the 18+ mark / Разрешённые каналы: показанные группы без отметки 18+
    adult = adultGroupFlags()
    keys = {}
    m.findKeyIx = {}
    for each k in m.allIdx
        ch = m.channels[k]
        if not adult[ch.gi] then
            key = epgKeyOf(ch)
            if not keys.DoesExist(key) then
                keys[key] = true
                m.findKeyIx[key] = k
            end if
        end if
    end for
    m.findBusy = true
    m.chanHeader.text = m.S.searching     ' 1.4.1: the search takes a few seconds / поиск идёт несколько секунд
    dbg("UI", "search " + kind + " '" + normText(query) + "' in " + keys.Count().ToStr() + " channels")
    m.epgSvc.findReq = { kind: kind, q: query, keys: keys }
end sub

sub onFindAns()
    a = m.epgSvc.findAns
    if a = invalid or not m.findBusy then return
    m.findBusy = false
    updateChanHeader()
    items = []
    idx = []
    for each it in a.items
        k = m.findKeyIx[it.key]
        if k <> invalid then
            items.push(it)
            idx.push(k)
        end if
    end for
    dbg("UI", "search result: " + items.Count().ToStr() + " shown of " + a.total.ToStr())
    if items.Count() = 0 then
        msg = m.S.noResults + " «" + a.q + "»."
        if m.epgStatus.state = "loading" then msg = msg + " " + m.S.epgLoading
        showInfoDialog(m.S.findTitle, [msg])
        return
    end if
    if m.view <> "channels" then leaveSettings(false)
    m.findItems = items
    m.findIdx = idx
    m.findQuery = a.q
    m.findResKind = a.kind
    m.findTotal = a.total
    m.catSub = ""
    buildCategories(-1)
    i = catIndexByKind("find")
    m.catList.jumpToItem = i
    showCategory(i)
    ' Fresh results: the cursor on the first one / Свежие результаты: курсор на первом
    if m.chanList.itemFocused <> 0 then m.chanList.jumpToItem = 0
    focusArea("chan")
end sub

function findCatLabel() as string
    p = m.S.findTitlePrefix
    if m.findResKind = "desc" then p = m.S.findDescPrefix
    n = m.findItems.Count().ToStr()
    if m.findTotal > m.findItems.Count() then n = m.S.findFirst.Replace("{n}", n)
    return p + " " + m.findQuery + "  (" + n + ")"
end function

' ---------- results in the channel column / результаты в колонке каналов ----------

' Taller rows for results, normal rows otherwise / Высокие строки для результатов
sub setFindLayout(on as boolean)
    if on = m.findShown then return
    m.findShown = on
    if on then
        m.chanList.itemSize = [620, 124]
        m.chanList.numRows = 6
    else
        m.chanList.itemSize = [620, 84]
        m.chanList.numRows = 9
        if m.findPeek then
            m.findPeek = false
            m.progIx = -1
            m.progItems = []
            showGuideNodes(false)
        end if
    end if
end sub

' "Yesterday: 11/14/2026 at 13:45" / «Вчера: 11/14/2026 в 13:45»
function findDateLine(secs as integer) as string
    a = CreateObject("roDateTime")
    a.FromSeconds(secs)
    a.ToLocalTime()
    b = CreateObject("roDateTime")
    b.ToLocalTime()
    da = a.GetYear() * 400 + a.GetMonth() * 32 + a.GetDayOfMonth()
    db = b.GetYear() * 400 + b.GetMonth() * 32 + b.GetDayOfMonth()
    w = m.S.dayToday
    if da < db then w = m.S.dayYesterday
    if da > db then w = m.S.dayTomorrow
    d = epg2(a.GetMonth()) + "/" + epg2(a.GetDayOfMonth()) + "/" + a.GetYear().ToStr()
    return w + ": " + d + " " + m.S.atWord + " " + epg2(a.GetHours()) + ":" + epg2(a.GetMinutes())
end function

function epg2(n as integer) as string
    if n < 10 then return "0" + n.ToStr()
    return n.ToStr()
end function

sub showFindCategory()
    setFindLayout(true)
    showMessage("", false)
    m.curIdx = m.findIdx
    now = CreateObject("roDateTime").AsSeconds()
    items = []
    for p = 0 to m.findItems.Count() - 1
        it = m.findItems[p]
        ch = m.channels[m.findIdx[p]]
        cat = 2
        if it.e <= now then
            cat = 0
        else if it.s <= now then
            cat = 1
        end if
        items.push({ title: ch.t, HDPosterUrl: ch.l, shortDescriptionLine1: (m.findIdx[p] + 1).ToStr(), shortDescriptionLine2: it.t, epgS: 0, epgE: 0, epgT: 0, findLine: findDateLine(it.s), findCat: cat, playing: false })
    end for
    root = CreateObject("roSGNode", "ContentNode")
    root.Update({ children: items }, true)
    m.chanList.content = root
    ' 1.4.1: the cursor starts on the result that plays, else on the first one
    ' 1.4.1: курсор встаёт на результат, который играет, иначе на первый
    p0 = playingRow()
    if p0 < 0 then p0 = 0
    m.chanList.jumpToItem = p0
    showPreviewInfo(m.findIdx[p0])
    updateChanHeader()
    markPlaying()
    peekFound(p0)
end sub

' Guide of the result's channel in column 3, found programme in the middle, focus stays here
' Программа канала результата в колонке 3, найденная передача посередине, фокус остаётся
sub peekFound(p as integer)
    if not m.findShown or m.findItems = invalid then return
    if p < 0 or p >= m.findItems.Count() then return
    m.findWant = m.findItems[p].s
    m.findPeek = true
    openGuide(m.findIdx[p])
end sub

' OK on a result: live / archive / description, like in the TV guide
' OK на результате: эфир / архив / описание — как в программе канала
sub actOnFound(p as integer)
    if m.findItems = invalid or p < 0 or p >= m.findItems.Count() then return
    m.progIx = m.findIdx[p]
    dbg("KEY", "result OK #" + (p + 1).ToStr())
    actOnProgramme(m.findItems[p])
end sub

' Put item "cur" into the middle of the 6-row programme list: jump below it first,
' then to it (the list scrolls only as much as needed).
' Ставим передачу cur в середину списка из 6 строк: сначала прыжок ниже, потом на неё.
sub centerProgramme(cur as integer, n as integer)
    first = cur + 3
    if first > n - 1 then first = n - 1
    m.progList.jumpToItem = first
    m.centerTo = cur
    m.centerTimer.control = "stop"
    m.centerTimer.control = "start"
end sub

sub onCenterTimer()
    if m.centerTo < 0 then return
    m.progList.jumpToItem = m.centerTo
    m.centerTo = -1
    onProgFocused()
end sub
