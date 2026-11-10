' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Playback, full screen, channel switching, favorites, autoplay of the last
' channel, and the group opened with the PIN.
' Воспроизведение, полный экран, переключение каналов, избранное, автозапуск
' последнего канала и группа, открытая PIN-кодом.

' ---------- playback / воспроизведение ----------

sub playChannel(ix as integer)
    if m.channels = invalid or ix < 0 or ix >= m.channels.Count() then return
    ch = m.channels[ix]
    c = CreateObject("roSGNode", "ContentNode")
    c.url = ch.u
    c.title = ch.t
    c.streamFormat = ch.f
    c.live = (ch.v <> true)
    headers = []
    if ch.ua <> "" then headers.push("User-Agent:" + ch.ua)
    if ch.rf <> "" then headers.push("Referer:" + ch.rf)
    if headers.Count() > 0 then c.HttpHeaders = headers
    m.playingIx = ix
    m.lastError = ""
    m.video.control = "stop"
    m.video.content = c
    m.video.visible = true
    m.video.control = "play"
    m.startTimer.control = "stop"
    m.startTimer.control = "start"
    dbg("PLAY", "#" + (ix + 1).ToStr() + " '" + ch.t + "' fmt=" + ch.f + " url=" + maskUrl(ch.u))
    updatePreviewStatus()
    updateChanHeader()
    refreshInfoPanel()
    checkOpenGroup()
    if m.mode = "full" then showOsd()
end sub

sub stopVideo()
    wasPlaying = (m.playingIx >= 0)
    ' Stopping does not close the PIN group; its 10-minute timer starts now
    ' Остановка не закрывает группу по PIN; с этого момента идут её 10 минут
    if wasPlaying and m.openGroup <> invalid then
        if openGroupPlaying() then m.openGroupSeen.Mark()
    end if
    m.playingIx = -1
    m.videoState = "none"
    m.startTimer.control = "stop"
    hideVideoSpinner()
    if wasPlaying then
        m.video.control = "stop"
        m.video.content = invalid
    end if
    m.video.visible = false
    updateChanHeader()
    refreshInfoPanel()
end sub

sub onVideoState()
    st = m.video.state
    if m.playingIx < 0 then return     ' nothing of ours is playing / ничего нашего не играет
    m.videoState = st
    if st = "buffering" then
        m.spinTimer.control = "stop"
        m.spinTimer.control = "start"
    else
        hideVideoSpinner()
    end if
    if st = "playing" then
        m.startTimer.control = "stop"
        rememberLastChannel()
        refreshAfterStart()
        if m.epgPending then m.epgStartTimer.control = "start"
    end if
    if st = "error" then
        m.startTimer.control = "stop"
        m.lastError = m.S.playError
        dbg("ERR", "video error code=" + m.video.errorCode.ToStr() + " msg=" + m.video.errorMsg + " str=" + m.video.errorStr)
        showPlayError()
        refreshAfterStart()
    else
        dbg("PLAY", "state=" + st)
    end if
    if m.mode = "main" then updatePreviewStatus()
end sub

' Channel did not start in time / Канал не начал показ вовремя
sub onStartTimer()
    if m.playingIx < 0 or m.videoState = "playing" or m.videoState = "paused" then return
    dbg("ERR", "start timeout #" + (m.playingIx + 1).ToStr())
    m.video.control = "stop"
    m.videoState = "error"
    m.lastError = m.S.startTimeout
    hideVideoSpinner()
    showPlayError()
    refreshAfterStart()
    if m.mode = "main" then updatePreviewStatus()
end sub

sub showPlayError()
    if m.mode = "full" then
        m.osdSub.text = m.lastError
        m.osd.visible = true
        m.osdTimer.control = "stop"
    end if
end sub

' Fresh playlist after the first channel started (or failed)
' Свежий плейлист после старта первого канала (или его ошибки)
sub refreshAfterStart()
    if m.refreshAfterPlay = "" then return
    id = m.refreshAfterPlay
    m.refreshAfterPlay = ""
    startLoad(id, "net", true)
end sub

' Our spinner only when buffering lasts longer than 0.7 s
' Наш значок загрузки — только если буферизация дольше 0,7 с
sub onSpinTimer()
    if m.videoState <> "buffering" then return
    if m.mode = "full" then
        m.vSpinner.translation = [912, 492]
    else
        m.vSpinner.translation = [1482, 271]
    end if
    m.vSpinner.visible = true
    m.vSpinner.control = "start"
end sub

sub hideVideoSpinner()
    m.spinTimer.control = "stop"
    m.vSpinner.control = "stop"
    m.vSpinner.visible = false
end sub

sub setVideoWindow()
    m.video.translation = [1240, 156]
    m.video.width = 580
    m.video.height = 326
end sub

sub enterFull()
    if m.playingIx < 0 then return
    m.mode = "full"
    m.mainGroup.visible = false
    m.video.translation = [0, 0]
    m.video.width = 1920
    m.video.height = 1080
    m.keyCatcher.setFocus(true)
    if m.vSpinner.visible then onSpinTimer()
    showOsd()
    dbg("UI", "full screen")
end sub

sub exitFull()
    m.mode = "main"
    m.osdTimer.control = "stop"
    m.osd.visible = false
    setVideoWindow()
    if m.vSpinner.visible then onSpinTimer()
    m.mainGroup.visible = true
    posInCat = indexOfChannel(m.playingIx)
    if posInCat >= 0 then m.chanList.jumpToItem = posInCat
    focusArea("chan")
    updatePreviewStatus()
    dbg("UI", "main screen")
end sub

sub showOsd()
    if m.playingIx < 0 then return
    ch = m.channels[m.playingIx]
    m.osdTitle.text = (m.playingIx + 1).ToStr() + "   " + ch.t
    g = ch.g
    if g = "" then g = m.S.ungrouped
    pr = epgNowOf(m.playingIx)
    if pr <> invalid then g = g + "      " + hhmm(pr.s) + " – " + hhmm(pr.e) + "   " + pr.t
    if m.videoState = "error" then g = m.lastError
    m.osdSub.text = g
    m.osd.visible = true
    m.osdTimer.control = "stop"
    m.osdTimer.control = "start"
end sub

sub onOsdTimer()
    if m.videoState <> "error" then m.osd.visible = false
end sub

' Up/Down direction from the setting. "list": Up = lower number (like the list).
' Направление вверх/вниз по настройке. "list": вверх = меньший номер (как в списке).
function zapDelta(isUp as boolean) as integer
    d = 1
    if m.data.zapMode <> "classic" then d = -1
    if isUp then return d
    return -d
end function

' Next/previous channel in the current category / Следующий/предыдущий канал в категории
sub zap(delta as integer)
    n = m.curIdx.Count()
    if n = 0 then return
    posInCat = indexOfChannel(m.playingIx)
    if posInCat < 0 then posInCat = 0 else posInCat = (posInCat + delta + n) mod n
    m.chanList.jumpToItem = posInCat
    playChannel(m.curIdx[posInCat])
end sub

' Seek for files (VOD) only / Перемотка только для файлов (VOD)
sub seekBy(sec as integer)
    if m.playingIx < 0 then return
    if m.channels[m.playingIx].v <> true then return
    t = m.video.position + sec
    if t < 0 then t = 0
    m.video.seek = t
    dbg("PLAY", "seek to " + Int(t).ToStr())
end sub

sub togglePause()
    if m.videoState = "playing" then
        m.video.control = "pause"
    else if m.videoState = "paused" then
        m.video.control = "resume"
    end if
end sub

' ---------- last channel and autoplay / последний канал и автозапуск ----------

sub rememberLastChannel()
    ch = m.channels[m.playingIx]
    lc = m.data.lastChannel
    if lc <> invalid and lc.n = ch.t and lc.g = ch.g then return
    m.data.lastChannel = { n: ch.t, g: ch.g }
    storageSave(m.data)
end sub

' Select the last channel and play it if its group is shown and the Roku
' "Auto-play video" setting allows. Returns true if playback started.
' Выделить последний канал и включить его, если его группа показана и настройка
' Roku «Автовоспроизведение» разрешает. Возвращает true, если показ начался.
function autoplayLast() as boolean
    lc = m.data.lastChannel
    if lc = invalid or m.channels = invalid then return false
    k = findChannel(lc.n, lc.g)
    if k < 0 then return false
    if groupMode(m.data, lc.g) <> "s" then
        dbg("PLAY", "last channel is in a hidden or PIN group, no autoplay")
        return false
    end if
    i = groupCat(m.channels[k].gi)
    if i < 0 then return false
    m.catList.jumpToItem = i
    showCategory(i)
    posInCat = indexOfChannel(k)
    if posInCat >= 0 then m.chanList.jumpToItem = posInCat
    showPreviewInfo(k)
    if m.mode = "main" then focusArea("chan")
    di = CreateObject("roDeviceInfo")
    if FindMemberFunction(di, "IsAutoPlayEnabled") <> invalid then
        if not di.IsAutoPlayEnabled() then
            dbg("PLAY", "Roku auto-play is off: last channel selected, not started")
            return false
        end if
    end if
    dbg("PLAY", "autoplay last channel")
    playChannel(k)
    return true
end function

' ---------- favorites / избранное ----------
' Stored by channel NAME: links contain access keys that can change.
' Храним НАЗВАНИЯ каналов: в ссылках ключ доступа, он может смениться.

sub rebuildFavSet()
    m.favSet = {}
    for each n in m.data.favorites
        m.favSet[n] = true
    end for
end sub

sub rebuildFavIdx()
    m.favIdx = []
    for each k in m.allIdx
        if m.favSet.DoesExist(m.channels[k].t) then m.favIdx.push(k)
    end for
end sub

sub toggleFavorite(ix as integer)
    if ix < 0 then return
    name = m.channels[ix].t
    favs = m.data.favorites
    found = -1
    for i = 0 to favs.Count() - 1
        if favs[i] = name then
            found = i
            exit for
        end if
    end for
    if found >= 0 then
        favs.Delete(found)
        m.previewStatus.text = m.S.favRemoved + " " + name
    else if favs.Count() >= getAppConfig().maxFavorites then
        ' The registry is small (32 KB) / Реестр маленький (32 КБ)
        m.previewStatus.text = m.S.favLimit.Replace("{n}", getAppConfig().maxFavorites.ToStr())
        return
    else
        favs.push(name)
        m.previewStatus.text = m.S.favAdded + " " + name
    end if
    storageSave(m.data)
    rebuildFavSet()
    dbg("UI", "favorite '" + name + "' on=" + (found < 0).ToStr() + " total=" + favs.Count().ToStr())
    ' Update the counter; refresh the list if Favorites is open
    ' Обновляем счётчик; перестраиваем список, если открыто «Избранное»
    focusP = m.chanList.itemFocused
    cur = m.curCat
    buildCategories(m.catList.itemFocused)
    m.curCat = cur
    if cur >= 0 and cur < m.cats.Count() and m.cats[cur].kind = "fav" then
        showCategory(cur)
        if m.curIdx.Count() > 0 then
            if focusP >= m.curIdx.Count() then focusP = m.curIdx.Count() - 1
            m.chanList.jumpToItem = focusP
        end if
        focusArea("chan")
    end if
end sub

' ---------- group opened with the PIN / группа, открытая PIN-кодом ----------
' The PIN flag belongs to one group. With it the group can be browsed and its
' channels played without the PIN. It is cleared when:
'  - a channel from ANOTHER group is started (just moving the cursor is not leaving);
'  - nothing has played for 10 minutes (from the stop or from the PIN entry), even
'    while staying inside the group; pause counts as watching; checked every 30 s;
'  - the app starts (nothing is saved).
' Флажок PIN принадлежит одной группе. С ним по группе ходят и включают её каналы
' без PIN. Сбрасывается, когда:
'  - включён канал ДРУГОЙ группы (просто перевести курсор — не выход);
'  - 10 минут ничего не играет (с остановки или с ввода PIN), даже внутри группы;
'    пауза — это просмотр; проверка раз в 30 с;
'  - запуск приложения (ничего не сохраняется).

sub openGroupWith(gi as integer)
    m.openGroup = m.groups[gi].name
    m.openGroupSeen = CreateObject("roTimespan")
    dbg("UI", "group opened with PIN: '" + m.openGroup + "'")
end sub

function openGroupPlaying() as boolean
    if m.playingIx < 0 or m.openGroup = invalid then return false
    return m.channels[m.playingIx].g = m.openGroup
end function

' Called when a channel starts: another group's channel clears the flag
' Вызывается при включении канала: канал другой группы сбрасывает флажок
sub checkOpenGroup()
    if m.openGroup = invalid or m.channels = invalid or m.playingIx < 0 then return
    if openGroupPlaying() then return
    closeOpenGroup("other channel")
end sub

sub onLockTimer()
    if m.openGroup = invalid or m.channels = invalid then return
    if openGroupPlaying() and (m.videoState = "playing" or m.videoState = "buffering" or m.videoState = "paused") then
        m.openGroupSeen.Mark()
        return
    end if
    if m.openGroupSeen.TotalSeconds() >= getAppConfig().pinIdleSec then closeOpenGroup("idle")
end sub

sub closeOpenGroup(why as string)
    name = m.openGroup
    m.openGroup = invalid
    dbg("UI", "PIN group closed (" + why + "): '" + name + "'")
    if m.view <> "channels" then return
    wasShown = openGroupNameShown(name)
    cur = m.curCat
    buildCategories(m.catList.itemFocused)
    m.curCat = cur
    if wasShown then showCategory(cur)
end sub

function openGroupNameShown(name as string) as boolean
    if m.curCat < 0 or m.curCat >= m.cats.Count() then return false
    c = m.cats[m.curCat]
    return c.kind = "group" and m.groups[c.gi].name = name
end function
