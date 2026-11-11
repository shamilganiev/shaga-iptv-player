' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Archive (catchup) links. Types / Типы:
'   shift      url + ?utc=START&lutc=NOW
'   append     url + catchup-source (with placeholders / с подстановками)
'   default    catchup-source is the whole link / шаблон — вся ссылка
'   flussonic  .../name.m3u8 -> .../name-START-DURATION.m3u8 ; /mpegts -> /timeshift_abs-START.ts
'   xc         Xtream: /live/user/pass/ID.ext -> /timeshift/user/pass/MINUTES/YYYY-MM-DD:HH-MM/ID.ts
' Placeholders / Подстановки: {utc} {start} ${start} {utcend} {end} ${end} {duration} ${duration}
'   {lutc} {now} ${now} {timestamp} {offset} ${offset} {Y} {m} {d} {H} {M} {S}
' Times are UTC seconds / Время — секунды UTC.

' Archive settings for a channel, or invalid. arch = playlist setting { t, d }.
' Настройки архива канала или invalid. arch — настройка плейлиста { t, d }.
function archiveInfo(ch as object, arch as object) as dynamic
    if arch.t = "off" then return invalid
    if arch.t = "auto" then
        if ch.cu = invalid or ch.cu = "" then return invalid
        src = ""
        if ch.cs <> invalid then src = ch.cs
        days = 3
        if ch.cd <> invalid and ch.cd > 0 then days = ch.cd
        return { t: normCatchupType(ch.cu, src), d: days, src: src }
    end if
    return { t: arch.t, d: arch.d, src: "" }
end function

function normCatchupType(t as string, src as string) as string
    t = LCase(t)
    if t = "fs" or t.Left(9) = "flussonic" then return "flussonic"
    if t = "xc" or t = "xtream" then return "xc"
    if t = "append" or t = "default" or t = "shift" then return t
    if src <> "" then return "default"
    return "shift"
end function

' Can a programme starting at s be watched from the archive now?
' Можно ли сейчас смотреть из архива передачу, начавшуюся в s?
function archiveCovers(info as dynamic, s as integer, now as integer) as boolean
    if info = invalid then return false
    return s < now and s >= now - info.d * 86400
end function

' Link to the archive from moment `start` (UTC) to `finish` / Ссылка на архив с `start` до `finish`
function archiveUrl(url as string, info as object, start as integer, finish as integer, now as integer) as string
    dur = finish - start
    if dur < 60 then dur = 60
    t = info.t
    if t = "append" then return url + fillCatchup(info.src, start, finish, now)
    if t = "default" and info.src <> "" then return fillCatchup(info.src, start, finish, now)
    if t = "flussonic" then return flussonicUrl(url, start, dur)
    if t = "xc" then
        x = xtreamUrl(url, start, dur)
        if x <> "" then return x
    end if
    ' shift (and fallback) / shift (и запасной вариант)
    sep = "?"
    if url.Instr("?") >= 0 then sep = "&"
    return url + sep + "utc=" + start.ToStr() + "&lutc=" + now.ToStr()
end function

function fillCatchup(tpl as string, start as integer, finish as integer, now as integer) as string
    dt = CreateObject("roDateTime")
    dt.FromSeconds(start)
    s = tpl
    pairs = [
        ["${start}", start.ToStr()], ["{utc}", start.ToStr()], ["{start}", start.ToStr()]
        ["${end}", finish.ToStr()], ["{utcend}", finish.ToStr()], ["{end}", finish.ToStr()]
        ["${duration}", (finish - start).ToStr()], ["{duration}", (finish - start).ToStr()]
        ["${now}", now.ToStr()], ["{lutc}", now.ToStr()], ["{now}", now.ToStr()], ["{timestamp}", now.ToStr()]
        ["${offset}", (now - start).ToStr()], ["{offset}", (now - start).ToStr()]
        ["{Y}", dt.GetYear().ToStr()], ["{m}", catchPad(dt.GetMonth())], ["{d}", catchPad(dt.GetDayOfMonth())]
        ["{H}", catchPad(dt.GetHours())], ["{M}", catchPad(dt.GetMinutes())], ["{S}", catchPad(dt.GetSeconds())]
    ]
    for each p in pairs
        s = s.Replace(p[0], p[1])
    end for
    return s
end function

function flussonicUrl(url as string, start as integer, dur as integer) as string
    q = ""
    base = url
    qp = url.Instr("?")
    if qp >= 0 then
        q = url.Mid(qp)
        base = url.Left(qp)
    end if
    if base.EndsWith("/mpegts") then
        return base.Left(base.Len() - 7) + "/timeshift_abs-" + start.ToStr() + ".ts" + q
    end if
    if LCase(base).EndsWith(".m3u8") then
        return base.Left(base.Len() - 5) + "-" + start.ToStr() + "-" + dur.ToStr() + ".m3u8" + q
    end if
    return base + "/index-" + start.ToStr() + "-" + dur.ToStr() + ".m3u8" + q
end function

' Xtream timeshift link (start written in UTC) / Ссылка Xtream (начало в UTC)
function xtreamUrl(url as string, start as integer, dur as integer) as string
    p = url.Instr("://")
    if p < 0 then return ""
    rest = url.Mid(p + 3)
    sl = rest.Instr("/")
    if sl < 0 then return ""
    hostPart = url.Left(p + 3) + rest.Left(sl)
    segs = rest.Mid(sl + 1).Split("/")
    if segs.Count() > 0 and LCase(segs[0]) = "live" then segs.Shift()
    if segs.Count() < 3 then return ""
    id = segs[2]
    dot = id.Instr(".")
    if dot > 0 then id = id.Left(dot)
    dt = CreateObject("roDateTime")
    dt.FromSeconds(start)
    stamp = dt.GetYear().ToStr() + "-" + catchPad(dt.GetMonth()) + "-" + catchPad(dt.GetDayOfMonth()) + ":" + catchPad(dt.GetHours()) + "-" + catchPad(dt.GetMinutes())
    return hostPart + "/timeshift/" + segs[0] + "/" + segs[1] + "/" + Int((dur + 59) / 60).ToStr() + "/" + stamp + "/" + id + ".ts"
end function

function catchPad(n as integer) as string
    if n < 10 then return "0" + n.ToStr()
    return n.ToStr()
end function
