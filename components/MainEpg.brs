' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Scene side of the TV guide: starts the EpgService, shows the programme in the
' channel rows, the header above the video, the third column and the full-screen bar.
' Сцена и телепрограмма: запуск EpgService, передача в строках каналов, заголовке
' над видео, третьей колонке и на плашке полного экрана.
'
' Column 3 shows the PLAYING channel, else the focused one (same rule as the header).
' Колонка 3 показывает ИГРАЮЩИЙ канал, иначе выделенный (как заголовок).

sub epgInit()
    m.epgNow = {}
    m.epgStatus = { state: "none", channels: 0, updated: "", error: "" }
    m.epgUrl = ""
    m.epgSentUrl = ""
    m.epgPending = false       ' load after the next video start / загрузить после старта видео
    m.epgDetail = invalid
    m.epgAskedKey = ""
    m.epgAskedMinute = -1
    m.epgSvc = CreateObject("roSGNode", "EpgService")
    m.epgSvc.observeField("status", "onEpgStatus")
    m.epgSvc.observeField("nowMap", "onEpgNow")
    m.epgSvc.observeField("detail", "onEpgDetail")
    m.epgSvc.control = "RUN"
    m.epgStartTimer = m.top.findNode("epgStartTimer")
    m.epgStartTimer.observeField("fire", "onEpgStartTimer")
    m.nowTitle = m.top.findNode("nowTitle")
    m.nowTime = m.top.findNode("nowTime")
    m.nowBarBg = m.top.findNode("nowBarBg")
    m.nowBar = m.top.findNode("nowBar")
    m.nowDesc = m.top.findNode("nowDesc")
    m.nextList = m.top.findNode("nextList")
end sub

' Guide key of a channel (older saved copies have no "ek") / Ключ канала для программы
function epgKeyOf(ch as object) as string
    if ch.ek <> invalid then return ch.ek
    if ch.id <> ch.t then return ch.id
    return "n:" + epgNorm(ch.t)
end function

' New playlist on screen: load its guide after the video starts (1.5 s later)
' Новый плейлист на экране: программу грузим через 1,5 с после старта видео
sub epgPlaylistShown(url as dynamic)
    if url = invalid then url = ""
    m.epgUrl = url
    if url <> m.epgSentUrl or m.epgStatus.state = "none" or m.epgStatus.state = "error" then
        m.epgPending = true
        if url <> m.epgSentUrl then
            m.epgNow = {}
            m.epgDetail = invalid
        end if
        if m.videoState = "playing" then m.epgStartTimer.control = "start"
    end if
end sub

sub onEpgStartTimer()
    epgRequestLoad(false)
end sub

sub epgRequestLoad(force as boolean)
    if m.channels = invalid then return
    m.epgPending = false
    keys = {}
    for each ch in m.channels
        keys[epgKeyOf(ch)] = epgNorm(ch.t)
    end for
    m.epgSentUrl = m.epgUrl
    m.epgSvc.cmd = { action: "load", url: m.epgUrl, force: force, keys: keys }
    dbg("EPG", "request load force=" + force.ToStr())
end sub

sub onEpgStatus()
    m.epgStatus = m.epgSvc.status
    if m.view = "settings" then buildSettingsRows(m.chanList.itemFocused)
    refreshInfoPanel()
end sub

sub onEpgNow()
    m.epgNow = m.epgSvc.nowMap
    refreshRows(40)
    refreshInfoPanel()
    updateChanHeader()
    if m.mode = "full" and m.osd.visible then showOsd()
end sub

' Programme on air for a channel: { s, e, t } or invalid / Передача в эфире или invalid
function epgNowOf(ix as integer) as dynamic
    if ix < 0 or m.channels = invalid then return invalid
    v = m.epgNow[epgKeyOf(m.channels[ix])]
    if v = invalid then return invalid
    a = v.Instr("|")
    b = v.Instr(a + 1, "|")
    if a < 0 or b < 0 then return invalid
    return { s: v.Left(a).ToInt(), e: v.Mid(a + 1, b - a - 1).ToInt(), t: v.Mid(b + 1) }
end function

' Content fields of a channel row / Поля строки канала
function rowItem(k as integer) as object
    ch = m.channels[k]
    it = { title: ch.t, HDPosterUrl: ch.l, shortDescriptionLine1: (k + 1).ToStr(), shortDescriptionLine2: "", epgS: 0, epgE: 0 }
    pr = epgNowOf(k)
    if pr <> invalid then
        it.shortDescriptionLine2 = pr.t
        it.epgS = pr.s
        it.epgE = pr.e
    end if
    return it
end function

' Update the programme in rows around the focus (cheap; the whole list can be thousands)
' Обновить передачу в строках около фокуса (весь список бывает в тысячи строк)
sub refreshRows(radius as integer)
    if m.view <> "channels" or m.chanList.content = invalid then return
    n = m.chanList.content.getChildCount()
    if n = 0 or n <> m.curIdx.Count() then return
    f = m.chanList.itemFocused
    if f < 0 then f = 0
    a = f - radius
    if a < 0 then a = 0
    b = f + radius
    if b > n - 1 then b = n - 1
    for p = a to b
        c = m.chanList.content.getChild(p)
        pr = epgNowOf(m.curIdx[p])
        t = ""
        s = 0
        e = 0
        if pr <> invalid then
            t = pr.t
            s = pr.s
            e = pr.e
        end if
        if c.epgS <> s or c.epgE <> e then
            c.epgS = s
            c.epgE = e
        end if
        if c.shortDescriptionLine2 <> t then c.shortDescriptionLine2 = t
    end for
end sub

' Channel shown in column 3: playing, else focused / Канал колонки 3
function infoTarget() as integer
    if m.playingIx >= 0 then return m.playingIx
    return focusedChannel()
end function

sub refreshInfoPanel()
    if m.view = "settings" then return
    ix = infoTarget()
    if ix < 0 or m.channels = invalid then
        clearPreview()
        return
    end if
    ch = m.channels[ix]
    m.chLogo.uri = ch.l
    m.chTitle.text = (ix + 1).ToStr() + "  " + ch.t
    g = ch.g
    if g = "" then g = m.S.ungrouped
    m.chGroup.text = g
    key = epgKeyOf(ch)
    minute = CreateObject("roDateTime").AsSeconds() \ 60
    if m.epgStatus.state = "ready" and (key <> m.epgAskedKey or minute <> m.epgAskedMinute) then
        m.epgAskedKey = key
        m.epgAskedMinute = minute
        m.epgSvc.detailKey = key
    end if
    if m.epgDetail <> invalid and m.epgDetail.key = key and m.epgStatus.state = "ready" then
        renderProgramme(m.epgDetail.items)
    else
        renderReason(key)
    end if
end sub

sub onEpgDetail()
    m.epgDetail = m.epgSvc.detail
    ix = infoTarget()
    if ix < 0 or m.channels = invalid then return
    if m.epgDetail.key <> epgKeyOf(m.channels[ix]) then return
    renderProgramme(m.epgDetail.items)
end sub

' Why there is no programme / Почему программы нет
sub renderReason(key as string)
    st = m.epgStatus.state
    t = ""
    if st = "loading" then
        t = m.S.epgLoading
    else if st = "nourl" then
        t = m.S.epgNoUrl
    else if st = "error" then
        if m.epgStatus.error = "gzip" then t = m.S.epgGzip else t = m.S.epgError
    else if st = "ready" then
        if m.epgDetail = invalid or m.epgDetail.key <> key then
            clearProgramme()     ' answer is on its way / ответ в пути
            return
        end if
        t = m.S.epgNone
    end if
    clearProgramme()
    m.nowDesc.text = t
end sub

sub renderProgramme(items as object)
    clearProgramme()
    now = CreateObject("roDateTime").AsSeconds()
    first = 0
    if items.Count() > 0 and items[0].s <= now then
        cur = items[0]
        m.nowTitle.text = cur.t
        left = (cur.e - now + 59) \ 60
        m.nowTime.text = hhmm(cur.s) + " – " + hhmm(cur.e) + "  ·  " + m.S.epgLeft.Replace("{n}", left.ToStr())
        pct = (now - cur.s) / (cur.e - cur.s)
        if pct > 1 then pct = 1
        m.nowBar.width = Int(580 * pct)
        m.nowBarBg.visible = true
        m.nowBar.visible = true
        m.nowDesc.text = cur.d
        first = 1
    else if items.Count() = 0 then
        m.nowDesc.text = m.S.epgNone
        return
    end if
    ' "Next": 4 lines, the description area is used too when there is no description
    ' «Далее»: 4 строки; без описания — и место описания
    lines = []
    for k = first to items.Count() - 1
        lines.push(hhmm(items[k].s) + "   " + items[k].t)
        if lines.Count() >= 8 then exit for
    end for
    if lines.Count() = 0 then return
    if m.nowDesc.text = "" then
        m.nowDesc.text = m.S.epgNext + ":" + Chr(10) + joinLines(lines, 0, 3)
        m.nextList.text = joinLines(lines, 3, 4)
    else
        m.nextList.text = m.S.epgNext + ":" + Chr(10) + joinLines(lines, 0, 3)
    end if
end sub

function joinLines(lines as object, from as integer, count as integer) as string
    out = ""
    for k = from to from + count - 1
        if k >= lines.Count() then exit for
        if out <> "" then out = out + Chr(10)
        out = out + lines[k]
    end for
    return out
end function

sub clearProgramme()
    m.nowTitle.text = ""
    m.nowTime.text = ""
    m.nowDesc.text = ""
    m.nextList.text = ""
    m.nowBarBg.visible = false
    m.nowBar.visible = false
end sub

' Local "HH:MM" / Местное время «ЧЧ:ММ»
function hhmm(secs as integer) as string
    dt = CreateObject("roDateTime")
    dt.FromSeconds(secs)
    dt.ToLocalTime()
    return epgPad2(dt.GetHours()) + ":" + epgPad2(dt.GetMinutes())
end function

function epgPad2(n as integer) as string
    if n < 10 then return "0" + n.ToStr()
    return n.ToStr()
end function

' Settings row value / Значение строки в Настройках
function epgStatusText() as string
    st = m.epgStatus.state
    if st = "ready" then return m.S.epgRowValue.Replace("{n}", m.epgStatus.channels.ToStr()).Replace("{t}", m.epgStatus.updated)
    if st = "loading" then return m.S.epgLoading
    if st = "nourl" then return m.S.epgNoUrl
    if st = "error" then
        if m.epgStatus.error = "gzip" then return m.S.epgGzip
        return m.S.epgError
    end if
    return ""
end function
