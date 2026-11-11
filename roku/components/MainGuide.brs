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
    m.epgSvc.listKey = epgKeyOf(m.channels[ix])
    dbg("UI", "TV guide of #" + (ix + 1).ToStr())
end sub

sub onEpgList()
    d = m.epgSvc.list
    if m.progIx < 0 or d = invalid or m.channels = invalid then return
    if d.key <> epgKeyOf(m.channels[m.progIx]) then return
    m.progItems = d.items
    if m.progItems.Count() = 0 then
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
        if it.s <= now and now < it.e then
            mark = "> "
            cur = i
        else if it.e <= now then
            cur = i + 1
        end if
        items.push({ title: mark + dayPrefix(it.s) + hhmm(it.s) + "   " + it.t })
    end for
    if cur >= items.Count() then cur = items.Count() - 1
    root = CreateObject("roSGNode", "ContentNode")
    root.Update({ children: items }, true)
    m.progList.content = root
    showGuideNodes(true)
    m.progList.jumpToItem = cur
    onProgFocused()
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
    focusArea("chan")
    refreshInfoPanel()
end sub

sub onProgFocused()
    i = m.progList.itemFocused
    if i < 0 or i >= m.progItems.Count() then return
    d = m.progItems[i].d
    if d = "" then d = m.progItems[i].t
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
        playChannel(m.progIx)
    else if it.e <= now then
        info = archiveInfo(m.channels[m.progIx], currentArchive())
        if info = invalid then
            ' The playlist gives no archive and the setting is "From the playlist" / Архив не настроен
            dbg("UI", "archive not set for this playlist")
            showInfoDialog(it.t, [m.S.archNotSet])
        else if archiveCovers(info, it.s, now) then
            playArchiveAt(m.progIx, it.s, it.e, it.t, 0)
        else
            dbg("UI", "programme is older than the archive depth")
            showInfoDialog(it.t, [m.S.archUnavailable])
        end if
    else
        showDescription(it)
    end if
end sub

' "yesterday " / "tomorrow " / "" by the local date / по местной дате
function dayPrefix(secs as integer) as string
    a = CreateObject("roDateTime")
    a.FromSeconds(secs)
    a.ToLocalTime()
    b = CreateObject("roDateTime")
    b.ToLocalTime()
    da = a.GetYear() * 400 + a.GetMonth() * 32 + a.GetDayOfMonth()
    db = b.GetYear() * 400 + b.GetMonth() * 32 + b.GetDayOfMonth()
    if da = db then return ""
    if da < db then return m.S.yesterday + " "
    return m.S.tomorrow + " "
end function

sub showDescription(it as object)
    d = it.d
    if d = "" then d = m.S.noDesc
    showInfoDialog(it.t, [dayPrefix(it.s) + hhmm(it.s) + " – " + hhmm(it.e), d])
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
    m.descWanted = ""
    now = CreateObject("roDateTime").AsSeconds()
    if d.items.Count() > 0 and d.items[0].s <= now then
        showDescription(d.items[0])
    else if m.epgStatus.state = "loading" then
        showInfoDialog(m.S.ctxDesc, [m.S.epgLoading])
    else
        showInfoDialog(m.S.ctxDesc, [m.S.epgNone])
    end if
    return true
end function

' ---------- context menu on * / контекстное меню по * ----------

sub openContextMenu()
    if m.focusArea = "prog" then
        i = m.progList.itemFocused
        if i < 0 or i >= m.progItems.Count() then return
        it = m.progItems[i]
        acts = [{ a: "progdesc", t: m.S.ctxDesc }]
        now = CreateObject("roDateTime").AsSeconds()
        if it.s <= now then acts.push({ a: "watch", t: m.S.ctxWatch })
        showContext(it.t, acts, { item: it })
    else if m.focusArea = "chan" and m.view = "channels" then
        ix = focusedChannel()
        if ix < 0 then return
        ch = m.channels[ix]
        acts = []
        if m.favSet.DoesExist(ch.t) then
            acts.push({ a: "fav", t: m.S.ctxFavRemove })
        else
            acts.push({ a: "fav", t: m.S.ctxFavAdd })
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
        if pr <> invalid then playArchiveAt(ctx.ix, pr.s, pr.e, pr.t, 0)
    else if a = "group" then
        goToChannelGroup(ctx.ix)
    else if a = "progdesc" then
        showDescription(ctx.item)
    else if a = "watch" then
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
