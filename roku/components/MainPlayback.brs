' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Playback, full screen, channel switching, favorites, autoplay of the last
' channel, and the group opened with the PIN.
' Воспроизведение, полный экран, переключение каналов, избранное, автозапуск
' последнего канала и группа, открытая PIN-кодом.

' ---------- playback / воспроизведение ----------

' 1.5.0: archive watch: restart on errors and long buffering (up to 3 times), the next
' programme when one ends. Nothing here touches m.S (strings are not loaded yet in init).
' 1.5.0: присмотр за архивом: перезапуск при ошибке и долгой буферизации (до 3 раз), следующая
' передача, когда закончилась текущая. m.S здесь не трогаем (в init строк ещё нет).
sub playbackInit()
    m.archRetry = 0           ' restarts in a row / перезапусков подряд
    m.archEver = false        ' the archive programme has played at least once / архив хоть раз пошёл
    m.archAuto = false        ' the next playArchiveAt is a restart, not the user's choice / следующий запуск — перезапуск
    m.archOkClock = CreateObject("roTimespan")
    m.restartClock = CreateObject("roTimespan")   ' 1.5.1: last automatic restart / последний автоперезапуск
    m.restartDone = false
    m.nextAsked = false
    m.nextPlay = false        ' the stream ended: play the next programme / поток закончился — включить следующую
    m.liveRetry = 0          ' 1.5.1: live restarts in a row / перезапусков эфира подряд
    m.liveEver = false       ' the live channel has played / эфир хоть раз пошёл
    m.playAuto = false       ' the next playChannel is a restart / следующий playChannel — перезапуск
    m.seekTarget = -1        ' 1.5.1: collected archive seek, seconds from the start / накопленная перемотка
    m.seekTimer = m.top.findNode("seekTimer")
    m.seekTimer.observeField("fire", "onSeekTimer")
    m.bufTimer = m.top.findNode("bufTimer")
    m.bufTimer.observeField("fire", "onBufTimer")
    m.archTimer = m.top.findNode("archTimer")
    m.archTimer.observeField("fire", "onArchTimer")
    m.archTimer.control = "start"
    m.epgSvc.observeField("nextAns", "onNextAns")
end sub

' Play and open the full screen at once (1.5.0: the first OK opens the full screen)
' Включить и сразу открыть полный экран (1.5.0: первый OK открывает полный экран)
sub playChannelFull(ix as integer)
    playChannel(ix)
    enterFull()
end sub

' Replay key: restart the stream (live), 15 s back (files) / Instant Replay: перезапуск потока (эфир), 15 с назад (файлы)
sub restartStream()
    if m.playingIx < 0 then return
    if m.arch <> invalid then
        dbg("PLAY", "restart of the archive by the user")
        m.archRetry = 0
        seekArchive(0)
    else if m.channels[m.playingIx].v = true then
        seekBy(-15)
    else
        dbg("PLAY", "restart of the stream by the user")
        playChannel(m.playingIx)
    end if
end sub

' Live channel (or a file) / Прямой эфир канала (или файл)
sub playChannel(ix as integer)
    if m.channels = invalid or ix < 0 or ix >= m.channels.Count() then return
    ' A channel chosen by the user: live restarts are counted anew / Выбран канал: перезапуски эфира считаются заново
    if not m.playAuto then
        m.liveRetry = 0
        m.liveEver = false
    end if
    m.playAuto = false
    m.arch = invalid
    ch = m.channels[ix]
    playStream(ix, ch.u, ch.v <> true)
end sub

' Programme from the archive, from `offset` seconds after its start
' Передача из архива, начиная через `offset` секунд от её начала
sub playArchiveAt(ix as integer, s as integer, e as integer, title as string, offset as integer)
    if m.channels = invalid or ix < 0 then return
    ch = m.channels[ix]
    info = archiveInfo(ch, currentArchive())
    if info = invalid then return
    ' A new programme chosen by the user (or the next one): restarts are counted anew
    ' Новая передача (выбрана или следующая): перезапуски считаются заново
    if not m.archAuto then
        m.archRetry = 0
        m.archEver = false
    end if
    m.archAuto = false
    m.nextAsked = false
    m.nextPlay = false
    now = CreateObject("roDateTime").AsSeconds()
    url = archiveUrl(ch.u, info, s + offset, e, now)
    ' Own clock: Video.position of archive/shift streams follows the wall clock, not frames
    ' Свои часы: Video.position у архива/shift идёт по часам, а не по кадрам
    m.arch = { s: s, e: e, t: title, base: offset, acc: 0, run: false, clock: CreateObject("roTimespan"), type: info.t }
    dbg("PLAY", "archive " + info.t + " #" + (ix + 1).ToStr() + " " + hhmm(s) + "-" + hhmm(e) + " +" + offset.ToStr() + "s")
    playStream(ix, url, false)
end sub

' Seconds since the programme start in the archive / Секунды от начала передачи в архиве
function archPos() as integer
    a = m.arch
    if a = invalid then return 0
    p = a.base + a.acc
    if a.run then p = p + a.clock.TotalSeconds()
    return p
end function

sub seekArchive(delta as integer)
    a = m.arch
    if a = invalid or m.playingIx < 0 then return
    p = archClamp(archPos() + delta)
    m.archAuto = true      ' same programme: not a new choice / та же передача — не новый выбор
    playArchiveAt(m.playingIx, a.s, a.e, a.t, p)
end sub

' Position inside the archive programme, not later than "now − 30 s" / Позиция в передаче, не позже «сейчас − 30 с»
function archClamp(p as integer) as integer
    a = m.arch
    maxP = a.e - a.s - 30
    now = CreateObject("roDateTime").AsSeconds()
    if a.s + maxP > now - 30 then maxP = now - 30 - a.s
    if p > maxP then p = maxP
    if p < 0 then p = 0
    return p
end function

' 1.5.1: seek keys: the target is collected and shown, the stream restarts once 0.9 s after the last press
' (in 1.5.0 every press restarted the stream: dozens of restarts for a long seek)
' 1.5.1: кнопки перемотки: цель копится и показывается, поток перезапускается один раз через 0,9 с
' после последнего нажатия (в 1.5.0 каждое нажатие перезапускало поток — десятки перезапусков)
sub seekArchiveKey(delta as integer)
    a = m.arch
    if a = invalid or m.playingIx < 0 then return
    if m.seekTarget < 0 then m.seekTarget = archPos()
    m.seekTarget = archClamp(m.seekTarget + delta)
    m.osdSub.text = m.S.archLabel + "      " + a.t + "      → " + mmss(m.seekTarget) + " / " + mmss(a.e - a.s)
    m.osd.visible = true
    m.osdTimer.control = "stop"
    m.osdTimer.control = "start"
    m.seekTimer.control = "stop"
    m.seekTimer.control = "start"
end sub

sub onSeekTimer()
    a = m.arch
    p = m.seekTarget
    m.seekTarget = -1
    if a = invalid or p < 0 or m.playingIx < 0 then return
    m.archAuto = true
    playArchiveAt(m.playingIx, a.s, a.e, a.t, p)
end sub

' "1:05:07" / "5:07"
function mmss(sec as integer) as string
    if sec < 0 then sec = 0
    h = sec \ 3600
    mi = (sec \ 60) mod 60
    s = sec mod 60
    t = epgPad2(s)
    if h > 0 then return h.ToStr() + ":" + epgPad2(mi) + ":" + t
    return mi.ToStr() + ":" + t
end function

sub playStream(ix as integer, url as string, live as boolean)
    ch = m.channels[ix]
    c = CreateObject("roSGNode", "ContentNode")
    c.url = url
    c.title = ch.t
    c.streamFormat = ch.f
    c.live = live
    headers = []
    if ch.ua <> "" then headers.push("User-Agent:" + ch.ua)
    if ch.rf <> "" then headers.push("Referer:" + ch.rf)
    if headers.Count() > 0 then c.HttpHeaders = headers
    m.playingIx = ix
    m.seekTarget = -1
    m.seekTimer.control = "stop"
    ' 1.4.1: remember the category the playback was started from (for the mark in the group list)
    ' 1.4.1: запоминаем категорию, из которой запустили (для отметки в списке групп)
    if m.view = "channels" and m.curCat >= 0 then
        k = catKeyAt(m.curCat)
        if k <> "" then m.playCatKey = k
    end if
    m.lastError = ""
    m.video.control = "stop"
    m.video.content = c
    m.video.visible = true
    m.video.control = "play"
    m.startTimer.control = "stop"
    m.startTimer.control = "start"
    dbg("PLAY", "#" + (ix + 1).ToStr() + " '" + ch.t + "' fmt=" + ch.f + " url=" + maskUrl(url))
    updatePreviewStatus()
    updateChanHeader()
    refreshInfoPanel()
    checkOpenGroup()
    markPlaying()
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
    m.arch = invalid
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
    markPlaying()
end sub

sub onVideoState()
    st = m.video.state
    if m.playingIx < 0 then return     ' nothing of ours is playing / ничего нашего не играет
    m.videoState = st
    ' Archive clock runs only while playing (pause counts as stopped clock)
    ' Часы архива идут только во время показа
    if m.arch <> invalid then
        if st = "playing" and not m.arch.run then
            m.arch.clock.Mark()
            m.arch.run = true
        else if st <> "playing" and m.arch.run then
            m.arch.acc = m.arch.acc + m.arch.clock.TotalSeconds()
            m.arch.run = false
        end if
    end if
    if st = "buffering" then
        m.spinTimer.control = "stop"
        m.spinTimer.control = "start"
        ' 1.5.1: live too, if the channel has played / 1.5.1: и эфир, если канал уже шёл
        if (m.arch <> invalid and m.archEver) or (m.arch = invalid and m.liveEver) then
            m.bufTimer.control = "stop"
            m.bufTimer.control = "start"
        end if
    else
        hideVideoSpinner()
        m.bufTimer.control = "stop"
    end if
    if st = "playing" then
        m.startTimer.control = "stop"
        if m.arch <> invalid then
            m.archEver = true
        else
            m.liveEver = true
        end if
        m.archOkClock.Mark()
        rememberLastChannel()
        refreshAfterStart()
        if m.epgPending then m.epgStartTimer.control = "start"
    end if
    if st = "error" then
        m.startTimer.control = "stop"
        dbg("ERR", "video error code=" + m.video.errorCode.ToStr() + " msg=" + m.video.errorMsg + " str=" + m.video.errorStr)
        if m.arch <> invalid and archRestart("error") then return
        if m.arch = invalid and liveRestart("error") then return
        m.lastError = m.S.playError
        if m.arch <> invalid then
            if m.archEver then m.lastError = m.S.archBroken else m.lastError = m.S.archNoStart
        end if
        showPlayError()
        refreshAfterStart()
    else
        dbg("PLAY", "state=" + st)
    end if
    ' Archive stream ended: at the end of the programme — the next one, else a restart
    ' Поток архива закончился: в конце передачи — следующая, иначе перезапуск
    if st = "finished" and m.arch <> invalid then
        a = m.arch
        if archPos() >= a.e - a.s - 120 then
            askNextProgramme(true)
        else
            archRestart("finished")
        end if
    else if st = "finished" and m.videoState <> "error" and m.lastError = "" then
        liveRestart("finished")      ' 1.5.1: a live stream must not end / эфир не должен кончаться
    end if
    if m.mode = "main" then updatePreviewStatus()
end sub

' Restart of the archive from the same place, up to 3 times in a row (the count is reset after
' 2 minutes of normal playing). Not for a programme that never started: its archive is missing.
' Перезапуск архива с того же места, до 3 раз подряд (счёт сбрасывается после 2 минут
' нормального показа). Не для передачи, которая так и не пошла: её архива нет.
function archRestart(why as string) as boolean
    if m.arch = invalid or not m.archEver then return false
    if recentRestart() then return true
    if m.archOkClock.TotalSeconds() > 120 then m.archRetry = 0
    if m.archRetry >= 3 then
        dbg("PLAY", "archive " + why + ": 3 restarts did not help")
        return false
    end if
    m.archRetry = m.archRetry + 1
    dbg("PLAY", "archive " + why + ": restart " + m.archRetry.ToStr() + "/3 at +" + archPos().ToStr() + "s")
    seekArchive(0)
    return true
end function

' 1.5.1: the same for live: restart of the channel, up to 3 times in a row, only if it has played
' (a channel that never starts is just broken). Files (VOD) are not restarted.
' 1.5.1: то же для эфира: перезапуск канала, до 3 раз подряд, только если он уже шёл
' (канал, который не стартует, просто не работает). Файлы (VOD) не перезапускаются.
function liveRestart(why as string) as boolean
    if m.arch <> invalid or not m.liveEver or m.playingIx < 0 then return false
    if m.channels[m.playingIx].v = true then return false
    if recentRestart() then return true
    if m.archOkClock.TotalSeconds() > 120 then m.liveRetry = 0
    if m.liveRetry >= 3 then
        dbg("PLAY", "live " + why + ": 3 restarts did not help")
        return false
    end if
    m.liveRetry = m.liveRetry + 1
    dbg("PLAY", "live " + why + ": restart " + m.liveRetry.ToStr() + "/3")
    m.playAuto = true
    playChannel(m.playingIx)
    return true
end function

' 1.5.1: "error" is followed by "finished" of the same stream: one restart within 3 s is enough
' 1.5.1: за «error» приходит «finished» того же потока — одного перезапуска за 3 с достаточно
function recentRestart() as boolean
    if m.restartDone and m.restartClock.TotalMilliseconds() < 3000 then return true
    m.restartDone = true
    m.restartClock.Mark()
    return false
end function

' Buffering of the archive lasts 25 s / Буферизация архива длится 25 с
sub onBufTimer()
    if m.videoState <> "buffering" then return
    if m.arch = invalid then
        liveRestart("buffering 25 s")
        return
    end if
    if not archRestart("buffering 25 s") then
        m.lastError = m.S.archBroken
        updatePreviewStatus()
    end if
end sub

' Every 10 s: the archive programme is over — the next one (labels; the stream itself goes on)
' Каждые 10 с: архивная передача закончилась — следующая (подписи; сам поток идёт дальше)
sub onArchTimer()
    if m.arch = invalid or m.videoState <> "playing" or m.nextAsked then return
    a = m.arch
    if archPos() >= a.e - a.s then askNextProgramme(false)
end sub

sub askNextProgramme(play as boolean)
    if m.arch = invalid or m.playingIx < 0 then return
    m.nextAsked = true
    m.nextPlay = play
    m.epgSvc.nextReq = { key: epgKeyOf(m.channels[m.playingIx]), s: m.arch.e }
end sub

sub onNextAns()
    a = m.epgSvc.nextAns
    if a = invalid or m.arch = invalid or a.s <> m.arch.e or m.playingIx < 0 then return
    it = a.item
    if it = invalid then
        dbg("PLAY", "archive: no next programme in the guide")
        return
    end if
    now = CreateObject("roDateTime").AsSeconds()
    if m.nextPlay then
        ' The stream ended: start the next programme (or live if it is on air now)
        ' Поток закончился: включаем следующую передачу (или эфир, если она идёт сейчас)
        dbg("PLAY", "archive: next programme '" + it.t + "'")
        if it.s >= now - 60 then
            playChannel(m.playingIx)
        else
            playArchiveAt(m.playingIx, it.s, it.e, it.t, 0)
        end if
        return
    end if
    ' The stream goes on (shift): only the programme under it changes, the clock keeps going
    ' Поток идёт дальше (shift): меняется только передача, часы продолжают идти
    old = m.arch
    old.base = old.base - (it.s - old.s)
    old.s = it.s
    old.e = it.e
    old.t = it.t
    m.nextAsked = false
    dbg("PLAY", "archive: now '" + it.t + "' " + hhmm(it.s) + "-" + hhmm(it.e))
    updatePreviewStatus()
    updateChanHeader()
    markPlaying()
    if m.mode = "full" then showOsd()
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
    m.fullFrom = m.focusArea
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
    ' 1.4.1: back to the row the video was started from, so that OK opens the full screen
    ' again instead of restarting (programme list, search result or channel).
    ' 1.4.1: возвращаемся на строку, с которой запустили, чтобы OK снова открыл полный экран,
    ' а не перезапустил показ (список передач, результат поиска или канал).
    if m.fullFrom = "prog" and m.progList.visible and m.progIx = m.playingIx and not m.findPeek then
        focusArea("prog")
    else
        if m.progList.visible and not m.findPeek then closeGuide()
        posInCat = playingRow()
        if posInCat >= 0 then m.chanList.jumpToItem = posInCat
        focusArea("chan")
    end if
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
    if m.arch <> invalid then
        a = m.arch
        g = m.S.archLabel + "      " + hhmm(a.s) + " – " + hhmm(a.e) + "   " + a.t + "      (" + hhmm(a.s + archPos()) + ")"
    else if pr <> invalid then
        g = g + "      " + hhmm(pr.s) + " – " + hhmm(pr.e) + "   " + pr.t
    end if
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
    if m.findShown and m.findItems <> invalid and m.view = "channels" then
        zapFound(delta)
        return
    end if
    n = m.curIdx.Count()
    if n = 0 then return
    posInCat = indexOfChannel(m.playingIx)
    if posInCat < 0 then posInCat = 0 else posInCat = (posInCat + delta + n) mod n
    m.chanList.jumpToItem = posInCat
    m.fullFrom = "chan"
    playChannel(m.curIdx[posInCat])
end sub

' 1.5.1: in search results Up/Down go to the neighbouring result that can be played now
' (on air — live, past — archive if it is there); future ones are skipped.
' 1.5.1: в результатах поиска вверх/вниз — соседний результат, который можно смотреть сейчас
' (в эфире — эфир, прошедший — архив, если он есть); будущие пропускаются.
sub zapFound(delta as integer)
    n = m.findItems.Count()
    if n = 0 then return
    p = playingRow()
    if p < 0 then p = m.chanList.itemFocused
    if p < 0 then p = 0
    now = CreateObject("roDateTime").AsSeconds()
    arch = currentArchive()
    for k = 1 to n
        q = (p + delta * k) mod n
        if q < 0 then q = q + n
        it = m.findItems[q]
        ix = m.findIdx[q]
        if it.s <= now and now < it.e then
            dbg("PLAY", "zap to result #" + (q + 1).ToStr() + " (live)")
            m.chanList.jumpToItem = q
            playChannel(ix)
            return
        else if it.e <= now and archiveCovers(archiveInfo(m.channels[ix], arch), it.s, now) then
            dbg("PLAY", "zap to result #" + (q + 1).ToStr() + " (archive)")
            m.chanList.jumpToItem = q
            playArchiveAt(ix, it.s, it.e, it.t, 0)
            return
        end if
    end for
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
    if m.arch <> invalid then return     ' only live channels / только прямой эфир
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
    if groupIsAdult(m.data, lc.g) then
        ' 18+ group: not played, not focused; "All channels", channel 1 stays selected
        ' Группа 18+: не включаем и не выделяем; остаются «Все каналы», канал 1
        dbg("PLAY", "last channel is in an 18+ group, no autoplay")
        return false
    end if
    i = groupCat(m.channels[k].gi)
    if i < 0 then return false
    if i <> m.curCat or m.catList.itemFocused <> i then
        m.catList.jumpToItem = i
        showCategory(i)
    end if
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
