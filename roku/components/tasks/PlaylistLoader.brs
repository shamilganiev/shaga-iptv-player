' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' PlaylistLoader: download + parse M3U in a Task thread.
' PlaylistLoader: загрузка и разбор M3U в потоке Task.
'
' Channel record (short keys to save memory) / Запись канала (короткие ключи для экономии памяти):
'   t  title / название          u  stream URL / ссылка на поток
'   l  logo URL / логотип        g  group / категория ("" = без категории)
'   id tvg-id or title (deep links) / tvg-id или название (для deep link)
'   ua User-Agent, rf Referer from #EXTVLCOPT / заголовки из #EXTVLCOPT
'   f  streamFormat              v  true = VOD (file), false = live / true = файл, false = эфир
'   k  search key / ключ поиска
'   gi group index in groups[] / номер группы в groups[]
'   cu / cs / cd archive (catchup) type, source template, days — only when present
'      архив (catchup): тип, шаблон, дни — только если указаны
'   ek TV guide key: tvg-id, else "n:" + normalized title / ключ телепрограммы: tvg-id, иначе "n:" + название

sub init()
    m.top.functionName = "loadPlaylistTask"
end sub

sub loadPlaylistTask()
    cfg = getAppConfig()
    url = m.top.url
    pid = m.top.playlistId
    if m.top.mode = "cache" then
        readCache(pid, url)
        return
    end if
    dbg("LOAD", "start id=" + pid + " url=" + maskUrl(url))
    t0 = CreateObject("roTimespan")

    dl = download(url, cfg)
    if dl.error <> "" then
        dbg("ERR", "load failed: " + dl.error + " http=" + dl.httpCode.ToStr() + " " + dl.reason)
        m.top.result = { ok: false, fromCache: false, playlistId: pid, error: dl.error, httpCode: dl.httpCode }
        return
    end if
    dbg("LOAD", "downloaded " + dl.text.Len().ToStr() + " chars in " + t0.TotalMilliseconds().ToStr() + " ms")

    t1 = CreateObject("roTimespan")
    parsed = parseM3u(dl.text)
    dl = invalid
    if parsed = invalid then
        m.top.result = { ok: false, fromCache: false, playlistId: pid, error: "empty", httpCode: 0 }
        return
    end if
    if parsed.channels.Count() = 0 then
        m.top.result = { ok: false, fromCache: false, playlistId: pid, error: "nochannels", httpCode: 0 }
        return
    end if
    dbg("LOAD", "parsed " + parsed.channels.Count().ToStr() + " channels, " + parsed.groups.Count().ToStr() + " groups in " + t1.TotalMilliseconds().ToStr() + " ms")
    first = parsed.channels[0]
    dbg("LOAD", "first: '" + first.t + "' fmt=" + first.f + " url=" + maskUrl(first.u))

    writeCache(pid, url, parsed)
    dbg("LOAD", "TV guide link: " + maskUrl(parsed.epgUrl))
    m.top.result = { ok: true, fromCache: false, playlistId: pid, error: "", httpCode: 200, channels: parsed.channels, groups: parsed.groups, epgUrl: parsed.epgUrl }
end sub

'Parsed playlist cache in cachefs: (survives restarts, the OS may clear it).
' The registry is too small for it (about 16 KB per app).
' Кэш разобранного плейлиста в cachefs: (переживает перезапуск, система может очистить).
' Реестр для него слишком мал (около 16 КБ на приложение).
function cachePath(pid as string) as string
    return "cachefs:/playlist_" + pid + ".json"
end function

sub writeCache(pid as string, url as string, parsed as object)
    t0 = CreateObject("roTimespan")
    ok = WriteAsciiFile(cachePath(pid), FormatJson({ url: url, channels: parsed.channels, groups: parsed.groups, epgUrl: parsed.epgUrl }, &h0001))
    dbg("LOAD", "cache saved=" + ok.ToStr() + " in " + t0.TotalMilliseconds().ToStr() + " ms")
end sub

sub readCache(pid as string, url as string)
    t0 = CreateObject("roTimespan")
    text = ""
    if CreateObject("roFileSystem").Exists(cachePath(pid)) then text = ReadAsciiFile(cachePath(pid))
    data = invalid
    if text <> "" then data = ParseJson(text)
    text = ""
    if data = invalid or data.url <> url or data.channels = invalid or data.groups = invalid then
        dbg("LOAD", "no cache for id=" + pid)
        m.top.result = { ok: false, fromCache: true, playlistId: pid, error: "nocache", httpCode: 0 }
        return
    end if
    dbg("LOAD", "cache read " + data.channels.Count().ToStr() + " channels in " + t0.TotalMilliseconds().ToStr() + " ms")
    epgUrl = ""
    if data.epgUrl <> invalid then epgUrl = data.epgUrl
    m.top.result = { ok: true, fromCache: true, playlistId: pid, error: "", httpCode: 0, channels: data.channels, groups: data.groups, epgUrl: epgUrl }
end sub

' Returns { text, error ("" | timeout | network | http), httpCode, reason }
function download(url as string, cfg as object) as object
    res = { text: "", error: "", httpCode: 0, reason: "" }
    port = CreateObject("roMessagePort")
    req = CreateObject("roUrlTransfer")
    req.SetMessagePort(port)
    req.SetUrl(url)
    req.SetCertificatesFile("common:/certs/ca-bundle.crt")
    req.EnableEncodings(true)          ' Content-Encoding: gzip / сжатие gzip
    req.RetainBodyOnError(true)
    ' Always a fresh copy, never a cached one / Всегда свежая копия, не из кэша
    req.AddHeader("Cache-Control", "no-cache")
    req.AddHeader("Pragma", "no-cache")
    if cfg.playlistUserAgent <> invalid and cfg.playlistUserAgent <> "" then
        req.AddHeader("User-Agent", cfg.playlistUserAgent)
    end if
    if not req.AsyncGetToString() then
        res.error = "network"
        res.reason = "AsyncGetToString failed"
        return res
    end if
    timeoutMs = cfg.loadTimeoutSec * 1000
    msg = wait(timeoutMs, port)
    if msg = invalid then
        req.AsyncCancel()
        res.error = "timeout"
        return res
    end if
    if type(msg) = "roUrlEvent" then
        code = msg.GetResponseCode()
        res.httpCode = code
        res.reason = msg.GetFailureReason()
        if code < 0 or code = 0 then
            res.error = "network"
        else if code >= 400 then
            res.error = "http"
        else
            res.text = msg.GetString()
        end if
    else
        res.error = "network"
    end if
    return res
end function

' Returns { channels, groups } or invalid if this is not a playlist.
' Возвращает { channels, groups } или invalid, если это не плейлист.
function parseM3u(text as string) as dynamic
    if text.Len() > 0 and Asc(text.Left(1)) = 65279 then text = text.Mid(1)   ' BOM
    if text.Instr("#EXTINF") < 0 and text.Instr("://") < 0 then return invalid
    text = text.Replace(Chr(13), "")
    lines = text.Split(Chr(10))
    text = ""

    q = Chr(34)
    rxAttr = CreateObject("roRegex", "([A-Za-z0-9_-]+)=" + q + "([^" + q + "]*)" + q, "")
    rxTitle = CreateObject("roRegex", "^#EXTINF:(?:[^," + q + "]|" + q + "[^" + q + "]*" + q + ")*,(.*)$", "i")

    channels = []
    groups = []
    epgUrl = ""
    hdrCatch = invalid
    groupPos = {}
    pending = invalid
    pendingGrp = ""
    pendingUA = ""
    pendingRef = ""

    for each raw in lines
        line = raw.Trim()
        if line = "" then
            ' skip / пропускаем
        else if line.Left(1) = "#" then
            up = UCase(line.Left(11))
            if up.Left(7) = "#EXTM3U" then
                epgUrl = pickEpgUrl(line, rxAttr)
                hdrCatch = parseCatchup(lineAttrs(line, rxAttr))
            else if up.Left(8) = "#EXTINF:" then
                pending = parseExtinf(line, rxAttr, rxTitle)
                pendingGrp = ""
                pendingUA = ""
                pendingRef = ""
            else if up.Left(8) = "#EXTGRP:" then
                pendingGrp = line.Mid(8).Trim()
            else if up = "#EXTVLCOPT:" then
                opt = line.Mid(11)
                eq = opt.Instr("=")
                if eq > 0 then
                    k = LCase(opt.Left(eq).Trim())
                    v = opt.Mid(eq + 1).Trim()
                    if k = "http-user-agent" then pendingUA = v
                    if k = "http-referrer" or k = "http-referer" then pendingRef = v
                end if
            end if
        else
            ' Stream URL line / Строка со ссылкой на поток
            if pending = invalid then pending = { t: "", l: "", g: "", id: "", ua: "", rf: "" }
            ch = pending
            pending = invalid
            if ch.g = "" then ch.g = pendingGrp
            if pendingUA <> "" then ch.ua = pendingUA
            if pendingRef <> "" then ch.rf = pendingRef
            ch.u = line
            if ch.t = "" then ch.t = fileNameOf(line)
            if ch.id <> "" then ch.ek = ch.id else ch.ek = "n:" + epgNorm(ch.t)
            ' Header catchup defaults for channels without their own / Значения архива из заголовка
            if ch.cu = invalid and hdrCatch <> invalid then
                ch.cu = hdrCatch.cu
                if hdrCatch.cs <> "" then ch.cs = hdrCatch.cs
                ch.cd = hdrCatch.cd
            end if
            if ch.id = "" then ch.id = ch.t
            fmt = detectFormat(line)
            ch.f = fmt.f
            ch.v = fmt.v
            ch.k = searchKey(ch.t)
            ix = channels.Count()
            channels.push(ch)
            gk = "g_" + ch.g
            gp = groupPos[gk]
            if gp = invalid then
                gp = groups.Count()
                groupPos[gk] = gp
                groups.push({ name: ch.g, idx: [] })
            end if
            groups[gp].idx.push(ix)
            ch.gi = gp      ' group index / номер группы
        end if
    end for
    return { channels: channels, groups: groups, epgUrl: epgUrl }
end function

function lineAttrs(line as string, rxAttr as object) as object
    a = {}
    for each mt in rxAttr.MatchAll(line)
        a[LCase(mt[1])] = mt[2]
    end for
    return a
end function

' Archive attributes: catchup / catchup-type, catchup-source / catchup-template,
' catchup-days / tvg-rec / timeshift. Days without a type mean "shift".
' Атрибуты архива. Дни без типа означают "shift".
function parseCatchup(a as object) as dynamic
    t = ""
    if a["catchup"] <> invalid then t = LCase(a["catchup"])
    if t = "" and a["catchup-type"] <> invalid then t = LCase(a["catchup-type"])
    src = ""
    if a["catchup-source"] <> invalid then src = a["catchup-source"]
    if src = "" and a["catchup-template"] <> invalid then src = a["catchup-template"]
    days = 0
    for each k in ["catchup-days", "tvg-rec", "timeshift"]
        if days = 0 and a[k] <> invalid then days = a[k].ToInt()
    end for
    if t = "" and days > 0 then t = "shift"
    if t = "" then return invalid
    if days <= 0 then days = 3
    return { cu: t, cs: src, cd: days }
end function

' First real http(s) link from url-tvg / x-tvg-url ("a.xml,b.xml" or "name;x" are possible)
' Первая настоящая http(s)-ссылка из url-tvg / x-tvg-url (бывает список или не-ссылка)
function pickEpgUrl(line as string, rxAttr as object) as string
    for each mt in rxAttr.MatchAll(line)
        k = LCase(mt[1])
        if k = "url-tvg" or k = "x-tvg-url" then
            for each part in mt[2].Replace(",", " ").Split(" ")
                low = LCase(part.Trim())
                if low.Left(7) = "http://" or low.Left(8) = "https://" then return part.Trim()
            end for
        end if
    end for
    return ""
end function

function parseExtinf(line as string, rxAttr as object, rxTitle as object) as object
    a = {}
    for each mt in rxAttr.MatchAll(line)
        a[LCase(mt[1])] = mt[2]
    end for
    title = ""
    tm = rxTitle.Match(line)
    if tm.Count() > 1 then title = tm[1].Trim()
    if title = "" and a["tvg-name"] <> invalid then title = a["tvg-name"]
    ch = { t: title, l: "", g: "", id: "", ua: "", rf: "" }
    if a["tvg-logo"] <> invalid then
        ch.l = a["tvg-logo"]
    else if a["logo"] <> invalid then
        ch.l = a["logo"]
    end if
    if a["group-title"] <> invalid then ch.g = a["group-title"].Trim()
    if a["tvg-id"] <> invalid and a["tvg-id"] <> "" then ch.id = a["tvg-id"]
    if a["user-agent"] <> invalid then ch.ua = a["user-agent"]
    if a["http-user-agent"] <> invalid then ch.ua = a["http-user-agent"]
    if a["http-referrer"] <> invalid then ch.rf = a["http-referrer"]
    c = parseCatchup(a)
    if c <> invalid then
        ch.cu = c.cu
        if c.cs <> "" then ch.cs = c.cs
        ch.cd = c.cd
    end if
    return ch
end function

' Stream format by the URL path / Формат потока по пути ссылки
function detectFormat(url as string) as object
    path = LCase(url)
    qp = path.Instr("?")
    if qp >= 0 then path = path.Left(qp)
    if path.EndsWith(".mp4") or path.EndsWith(".mov") or path.EndsWith(".m4v") then return { f: "mp4", v: true }
    if path.EndsWith(".mkv") then return { f: "mkv", v: true }
    if path.EndsWith(".mpd") then return { f: "dash", v: false }
    if path.EndsWith(".ts") or path.EndsWith("/mpegts") then return { f: "ts", v: false }
    return { f: "hls", v: false }
end function

function fileNameOf(url as string) as string
    s = url
    qp = s.Instr("?")
    if qp >= 0 then s = s.Left(qp)
    parts = s.Split("/")
    for i = parts.Count() - 1 to 0 step -1
        if parts[i] <> "" then return parts[i]
    end for
    return url
end function
