' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Programme list of one channel (column 3, Right from the channel list),
' the context menu on the * key, programme descriptions.
' Список передач канала (колонка 3, «вправо» из списка каналов),
' контекстное меню по кнопке *, описания передач.
'
' Programme list: yesterday .. tomorrow; the cursor starts on the programme on air.
' OK: past -> archive (if available), on air -> live, future -> description.
' Список передач: вчера .. завтра; курсор встаёт на передачу в эфире.
' OK: прошлая -> архив (если есть), в эфире -> прямой эфир, будущая -> описание.

sub guideInit()
    m.progList = m.top.findNode("progList")
    m.progDesc = m.top.findNode("progDesc")
    m.progIx = -1
    m.progItems = []
    m.descWanted = ""
    m.progList.observeField("itemFocused", "onProgFocused")
    m.progList.observeField("itemSelected", "onProgSelected")
    m.epgSvc.observeField("list", "onEpgList")
    m.epgSvc.observeField("descAns", "onDescAns")
    m.descBox = m.top.findNode("descBox")
    m.descTitle = m.top.findNode("descTitle")
    m.descTime = m.top.findNode("descTime")
    m.descText = m.top.findNode("descText")
    m.descHint = m.top.findNode("descHint")   ' text is set when shown: m.S is not ready in init / текст — при показе: m.S в init ещё нет
    m.descOpen = false
    m.descItem = invalid
    m.descKey = ""
    m.guideFrom = 0         ' 1.5.0: start of the list asked by openGuide (0 = last 24 h) / начало списка
    m.progFrom = 0          ' start of the list on screen / начало списка на экране
    m.progKeepS = 0         ' "Load more": programme to keep the cursor near / передача, у которой держать курсор
end sub

' ---------- programme list / список передач ----------

sub openGuide(ix as integer)
    if ix < 0 or m.channels = invalid then return
    st = m.epgStatus.state
    ' While loading the service answers with what is parsed so far
    ' Во время загрузки служба отвечает тем, что уже разобрано
    if st <> "ready" and st <> "loading" then
        showInfoDialog(m.channels[ix].t, [epgStatusText()])
        return
    end if
    m.progIx = ix
    ' 1.5.0: the last 24 h by default; a search result can ask for an older start
    ' 1.5.0: по умолчанию — последние сутки; результат поиска может попросить начало раньше
    m.progKeepS = 0
    m.epgSvc.listReq = { key: epgKeyOf(m.channels[ix]), from: m.guideFrom }
    m.guideFrom = 0
    if not m.findPeek then dbg("UI", "TV guide of #" + (ix + 1).ToStr())
end sub

sub onEpgList()
    d = m.epgSvc.list
    if m.progIx < 0 or d = invalid or m.channels = invalid then return
    if d.key <> epgKeyOf(m.channels[m.progIx]) then return
    m.progItems = d.items
    m.progFrom = d.from
    ' 1.5.0: older days are kept: the first row loads one more day / есть старые дни: первая строка добавляет сутки
    if d.more = true then
        m.progItems = [{ more: true, s: 0, e: 0, t: m.S.loadMore, d: "" }]
        m.progItems.Append(d.items)
    else if d.items.Count() > 0 then
        ' 1.5.1: no older days: the first row says where the archive of the channel starts
        ' 1.5.1: старых дней нет — первая строка говорит, где начинается архив канала
        info = archiveInfo(m.channels[m.progIx], currentArchive())
        if info <> invalid then
            m.progItems = [{ more: false, s: 0, e: 0, t: m.S.archStart.Replace("{n}", info.d.ToStr()), d: "" }]
            m.progItems.Append(d.items)
        end if
    end if
    if d.items.Count() = 0 then
        msg = m.S.epgNone
        if m.epgStatus.state = "loading" then msg = m.S.epgLoading
        dbg("UI", "TV guide empty: " + m.epgStatus.state)
        showInfoDialog(m.channels[m.progIx].t, [msg])
        m.progIx = -1
        return
    end if
    now = CreateObject("roDateTime").AsSeconds()
    cur = 0
    items = []
    for i = 0 to m.progItems.Count() - 1
        it = m.progItems[i]
        mark = "   "
        if it.DoesExist("more") then
            items.push({ title: it.t, playing: false })
            cur = i + 1
        else if it.s <= now and now < it.e then
            mark = "> "
            cur = i
        else if it.e <= now then
            cur = i + 1
        end if
        if not it.DoesExist("more") then items.push({ title: mark + dayPrefix(it.s) + hhmm(it.s) + "   " + it.t, playing: false })
    end for
    if cur >= items.Count() then cur = items.Count() - 1
    root = CreateObject("roSGNode", "ContentNode")
    root.Update({ children: items }, true)
    m.progList.content = root
    showGuideNodes(true)
    markPlaying()
    ' 1.4.0: from the search — the found programme in the middle of the list
    ' 1.4.0: из поиска — найденная передача в середине списка
    if m.findWant > 0 then
        for i = 0 to m.progItems.Count() - 1
            if m.progItems[i].s = m.findWant then
                cur = i
                exit for
            end if
        end for
        m.findWant = 0
        centerProgramme(cur, items.Count())
    else if m.progKeepS > 0 then
        ' "Load more": the cursor on the last programme of the added day, right above the old first one
        ' «Загрузить ещё»: курсор на последней передаче добавленных суток, прямо над прежней первой
        for i = 0 to m.progItems.Count() - 1
            if m.progItems[i].s = m.progKeepS then
                cur = i - 1
                exit for
            end if
        end for
        if cur < 0 then cur = 0
        if m.progItems[cur].DoesExist("more") and cur + 1 < m.progItems.Count() then cur = cur + 1
        m.progKeepS = 0
        m.progList.jumpToItem = cur
    else
        m.progList.jumpToItem = cur
    end if
    onProgFocused()
    if m.findPeek then return      ' shown under the search results, the focus stays there / фокус остаётся в результатах
    focusArea("prog")
end sub

sub showGuideNodes(on as boolean)
    m.progList.visible = on
    m.progDesc.visible = on
    for each n in [m.nowTitle, m.nowTime, m.nowDesc, m.nextList]
        n.visible = not on
    end for
    if on then
        m.nowBarBg.visible = false
        m.nowBar.visible = false
    end if
end sub

sub closeGuide()
    if m.progIx < 0 and not m.progList.visible then return
    m.progIx = -1
    m.progItems = []
    showGuideNodes(false)
    markPlaying()
    focusArea("chan")
    refreshInfoPanel()
    if m.findShown then peekFound(m.chanList.itemFocused)
end sub

sub onProgFocused()
    i = m.progList.itemFocused
    if i < 0 or i >= m.progItems.Count() then return
    if m.progItems[i].DoesExist("more") then
        if m.progItems[i].more then m.progDesc.text = m.S.loadMoreHint else m.progDesc.text = m.S.archStartHint
        return
    end if
    ' Full title first (the list cuts long ones), then the description / Сначала полное название, потом описание
    d = m.progItems[i].t
    if m.progItems[i].d <> "" then d = d + ". " + m.progItems[i].d
    m.progDesc.text = d
end sub

sub onProgSelected()
    markOkFromList()
    i = m.progList.itemSelected
    if i < 0 or i >= m.progItems.Count() then return
    actOnProgramme(m.progItems[i])
end sub

' OK on a programme / OK на передаче
sub actOnProgramme(it as object)
    if m.progIx < 0 then return
    if it.DoesExist("more") then
        if it.more then loadMoreGuide()      ' the "start of the archive" row does nothing / строка «начало архива» ничего не делает
        return
    end if
    now = CreateObject("roDateTime").AsSeconds()
    ' Second OK on what is already playing: full screen (like a second OK on a channel)
    ' Повторный OK на том, что уже играет, — полный экран (как повторный OK на канале)
    if m.playingIx = m.progIx and m.videoState <> "error" then
        same = false
        if m.arch <> invalid then
            same = (m.arch.s = it.s)
        else
            same = (it.s <= now and now < it.e)
        end if
        if same then
            dbg("UI", "programme already playing: full screen")
            enterFull()
            return
        end if
    end if
    if it.s <= now and now < it.e then
        playChannelFull(m.progIx)     ' 1.5.0: the full screen at once / сразу полный экран
    else if it.e <= now then
        info = archiveInfo(m.channels[m.progIx], currentArchive())
        if info = invalid then
            ' The playlist gives no archive and the setting is "From the playlist" / Архив не настроен
            dbg("UI", "archive not set for this playlist")
            showInfoDialog(it.t, [m.S.archNotSet])
        else if archiveCovers(info, it.s, now) then
            playArchiveAt(m.progIx, it.s, it.e, it.t, 0)
            enterFull()
        else
            dbg("UI", "programme is older than the archive depth")
            showInfoDialog(it.t, [m.S.archUnavailable])
        end if
    else
        showDescription(it, epgKeyOf(m.channels[m.progIx]))
    end if
end sub

' 1.5.0: one more day back: the cursor stays above the old first programme
' 1.5.0: ещё сутки назад: курсор встаёт над прежней первой передачей
sub loadMoreGuide()
    if m.progIx < 0 or m.progItems.Count() < 2 then return
    m.progKeepS = m.progItems[1].s
    m.findPeek = false
    dbg("UI", "TV guide: one more day back")
    m.epgSvc.listReq = { key: epgKeyOf(m.channels[m.progIx]), from: m.progFrom - 86400 }
end sub

' "yesterday " / "tomorrow " / "" by the local date; 1.5.0: older days — "Fri 11/13 "
' «вчера » / «завтра » / "" по местной дате; 1.5.0: более старые дни — «пт 11/13 »
function dayPrefix(secs as integer) as string
    a = CreateObject("roDateTime")
    a.FromSeconds(secs)
    a.ToLocalTime()
    b = CreateObject("roDateTime")
    b.ToLocalTime()
    da = a.GetYear() * 400 + a.GetMonth() * 32 + a.GetDayOfMonth()
    db = b.GetYear() * 400 + b.GetMonth() * 32 + b.GetDayOfMonth()
    if da = db then return ""
    if da > db then return m.S.tomorrow + " "
    y = CreateObject("roDateTime")
    y.FromSeconds(b.AsSeconds() - 86400)
    dy = y.GetYear() * 400 + y.GetMonth() * 32 + y.GetDayOfMonth()
    if da = dy then return m.S.yesterday + " "
    wd = m.S.wdays.Split(",")
    w = ""
    if a.GetDayOfWeek() < wd.Count() then w = wd[a.GetDayOfWeek()] + " "
    return w + epgPad2(a.GetMonth()) + "/" + epgPad2(a.GetDayOfMonth()) + " "
end function

' 1.3.4: own wide window. The short text from memory is shown at once, the full one
' comes from the file (EpgService.answerDesc) and replaces it.
' 1.3.4: своё широкое окно. Сразу — короткий текст из памяти, полный приходит из файла
' (EpgService.answerDesc) и заменяет его.
sub showDescription(it as object, key as string)
    m.descItem = it
    m.descKey = key
    m.descHint.text = m.S.descHint
    m.descTitle.text = it.t
    m.descTime.text = dayPrefix(it.s) + hhmm(it.s) + " – " + hhmm(it.e)
    t = it.d
    if t = "" then t = m.S.descLoading else t = t + Chr(10) + Chr(10) + m.S.descLoading
    setDescText(t)
    m.descBox.visible = true
    m.descOpen = true
    m.keyCatcher.setFocus(true)
    m.epgSvc.descReq = { key: key, s: it.s }
end sub

' Font by length so that the text fits / Шрифт по длине, чтобы текст поместился
sub setDescText(t as string)
    n = t.Len()
    size = 32
    if n > 700 then size = 28
    if n > 1000 then size = 25
    if n > 1400 then size = 22
    m.descText.font.size = size
    m.descText.text = t
end sub

sub onDescAns()
    a = m.epgSvc.descAns
    if a = invalid then return
    autoDescArrived(a)      ' 1.5.0: the column under the video / колонка под видео
    if m.descItem = invalid or a.key <> m.descKey or a.s <> m.descItem.s then return
    d = a.d
    first = d.Left(60).Replace(Chr(10), " ")
    if d = "" then first = "(no description)"
    dbg("UI", "description '" + m.descItem.t + "' " + hhmm(a.s) + ": " + a.src + ", " + d.Len().ToStr() + " chars" + iif(a.why <> "", " (" + a.why + ")", "") + ": " + first)
    if not m.descOpen then return
    if d = "" then d = m.S.noDesc
    setDescText(d)
end sub

function iif(c as boolean, a as string, b as string) as string
    if c then return a
    return b
end function

sub closeDescription()
    m.descBox.visible = false
    m.descOpen = false
    m.descItem = invalid
    dbg("UI", "description closed")
    restoreFocus()
end sub

' Full description of the programme on air: asked from the service / Описание передачи в эфире
sub requestDescription(ix as integer)
    if ix < 0 then return
    st = m.epgStatus.state
    if st <> "ready" and st <> "loading" then
        showInfoDialog(m.channels[ix].t, [epgStatusText()])
        return
    end if
    m.descWanted = epgKeyOf(m.channels[ix])
    m.epgSvc.detailKey = m.descWanted
end sub

' Called from onEpgDetail / Вызывается из onEpgDetail
function guideDetailArrived(d as object) as boolean
    if m.descWanted = "" or d.key <> m.descWanted then return false
    key = m.descWanted
    m.descWanted = ""
    now = CreateObject("roDateTime").AsSeconds()
    if d.items.Count() > 0 and d.items[0].s <= now then
        showDescription(d.items[0], key)
    else if m.epgStatus.state = "loading" then
        showInfoDialog(m.S.ctxDesc, [m.S.epgLoading])
    else
        showInfoDialog(m.S.ctxDesc, [m.S.epgNone])
    end if
    return true
end function

' ---------- context menu on * / контекстное меню по * ----------

sub openContextMenu()
    if m.catSub <> "" and m.focusArea = "cat" then return
    if m.focusArea = "prog" then
        i = m.progList.itemFocused
        if i < 0 or i >= m.progItems.Count() then return
        it = m.progItems[i]
        if it.DoesExist("more") then return
        acts = [{ a: "progdesc", t: m.S.ctxDesc }]
        now = CreateObject("roDateTime").AsSeconds()
        if it.s <= now then acts.push({ a: "watch", t: m.S.ctxWatch })
        showContext(it.t, acts, { item: it })
    else if m.focusArea = "chan" and m.view = "channels" then
        ix = focusedChannel()
        if ix < 0 then return
        ch = m.channels[ix]
        ' 1.4.1: search result — the menu is about the FOUND programme, not the one on air
        ' 1.4.1: результат поиска — меню относится к НАЙДЕННОЙ передаче, а не к той, что в эфире
        if m.findShown and m.findItems <> invalid then
            p = m.chanList.itemFocused
            if p < 0 or p >= m.findItems.Count() then return
            it = m.findItems[p]
            acts = [{ a: "progdesc", t: m.S.ctxDesc }]
            now = CreateObject("roDateTime").AsSeconds()
            if it.s <= now then acts.push({ a: "watch", t: m.S.ctxWatch })
            if not groupIsAdult(m.data, ch.g) then
                if m.favSet.DoesExist(ch.t) then
                    acts.push({ a: "fav", t: m.S.ctxFavRemove })
                else
                    acts.push({ a: "fav", t: m.S.ctxFavAdd })
                end if
            end if
            acts.push({ a: "group", t: m.S.ctxGoGroup })
            showContext(it.t, acts, { ix: ix, item: it, key: epgKeyOf(ch) })
            return
        end if
        acts = []
        ' 1.5.0: channels of 18+ groups do not go to Favorites / каналы групп 18+ в Избранное не попадают
        if not groupIsAdult(m.data, ch.g) then
            if m.favSet.DoesExist(ch.t) then
                acts.push({ a: "fav", t: m.S.ctxFavRemove })
            else
                acts.push({ a: "fav", t: m.S.ctxFavAdd })
            end if
        end if
        acts.push({ a: "desc", t: m.S.ctxDesc })
        acts.push({ a: "guide", t: m.S.ctxGuide })
        pr = epgNowOf(ix)
        now = CreateObject("roDateTime").AsSeconds()
        if pr <> invalid and archiveCovers(archiveInfo(ch, currentArchive()), pr.s, now) then
            acts.push({ a: "start", t: m.S.ctxFromStart })
        end if
        c = invalid
        if m.curCat >= 0 and m.curCat < m.cats.Count() then c = m.cats[m.curCat]
        if c = invalid or c.kind <> "group" then acts.push({ a: "group", t: m.S.ctxGoGroup })
        showContext(ch.t, acts, { ix: ix })
    else if m.focusArea = "cat" then
        i = m.catList.itemFocused
        if i < 0 or i >= m.cats.Count() or m.cats[i].kind <> "group" then return
        gi = m.cats[i].gi
        mode = groupMode(m.data, m.groups[gi].name)
        acts = []
        if mode = "s" then acts.push({ a: "lock", t: m.S.ctxLock })
        acts.push({ a: "hide", t: m.S.ctxHide })
        showContext(groupTitle(gi), acts, { gi: gi })
    end if
end sub

sub showContext(title as string, acts as object, ctx as object)
    buttons = []
    for each a in acts
        buttons.push(a.t)
    end for
    buttons.push(m.S.cancel)
    ctx.acts = acts
    showDialog(newMessageDialog(title, [" "], buttons), "context", ctx)
end sub

sub onContextButton(b as integer, ctx as object)
    closeDialog()
    if b < 0 or b >= ctx.acts.Count() then return
    a = ctx.acts[b].a
    dbg("UI", "menu " + a)
    if a = "fav" then
        toggleFavorite(ctx.ix)
    else if a = "desc" then
        requestDescription(ctx.ix)
    else if a = "guide" then
        openGuide(ctx.ix)
    else if a = "start" then
        pr = epgNowOf(ctx.ix)
        if pr <> invalid then
            playArchiveAt(ctx.ix, pr.s, pr.e, pr.t, 0)
            enterFull()
        end if
    else if a = "group" then
        goToChannelGroup(ctx.ix)
    else if a = "progdesc" then
        if ctx.key <> invalid then
            showDescription(ctx.item, ctx.key)
        else if m.progIx >= 0 then
            showDescription(ctx.item, epgKeyOf(m.channels[m.progIx]))
        end if
    else if a = "watch" then
        if ctx.ix <> invalid then m.progIx = ctx.ix
        actOnProgramme(ctx.item)
    else if a = "hide" then
        applyGroupMode(ctx.gi, "h", -1)
    else if a = "lock" then
        if pinIsSet(m.data) then
            applyGroupMode(ctx.gi, "p", -1)
        else
            startPinCreate({ action: "setGroup", gi: ctx.gi, mode: "p", row: -1 })
        end if
    end if
end sub

sub goToChannelGroup(ix as integer)
    i = groupCat(m.channels[ix].gi)
    if i < 0 then return
    m.catList.jumpToItem = i
    showCategory(i)
    p = indexOfChannel(ix)
    if p >= 0 then m.chanList.jumpToItem = p
    focusArea("chan")
end sub
