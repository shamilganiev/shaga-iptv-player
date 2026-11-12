' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' EpgService: long-running Task with its own message port.
'  - cmd "load": show the saved copy (cachefs:) at once if it is younger than 24 h;
'    if it is older than the refresh period (12 h), download a fresh file in the
'    background while the old programme stays on screen.
'  - first load without a copy: the programme is published every 20 MB while parsing.
'  - requests from the scene (detailKey, listKey) are answered between chunks too.
'  - every 30 s: publish nowMap (programme on air per channel) when the minute changes.
' EpgService: долгоживущая задача со своим портом сообщений.
'  - "load": сохранённая копия (cachefs:) моложе 24 ч показывается сразу; если она старше
'    срока обновления (12 ч) — свежий файл качается в фоне, старая программа остаётся.
'  - первая загрузка без копии: программа публикуется каждые 20 МБ по ходу разбора.
'  - запросы сцены (detailKey, listKey) обслуживаются и между кусками файла.
'  - раз в 30 с: при смене минуты публикуем nowMap.
'
' gzip without Content-Encoding is NOT supported on Roku (no unpacking in BrightScript).
' gzip без Content-Encoding на Roku НЕ поддерживается (в BrightScript нет распаковки).

sub init()
    m.top.functionName = "epgLoop"
end sub

sub epgLoop()
    m.port = CreateObject("roMessagePort")
    m.top.observeField("cmd", m.port)
    m.top.observeField("detailKey", m.port)
    m.top.observeField("listKey", m.port)
    m.url = ""
    m.keys = {}
    m.progs = {}
    m.keyToFile = {}
    m.loadedAt = 0
    m.nextLoad = 0          ' when to download again (0 = not planned) / когда качать снова
    m.loading = false
    m.hadOld = false        ' a refresh with the old programme on screen / обновление поверх старой
    m.pendingCmd = invalid  ' cmd that came during a download / cmd, пришедший во время загрузки
    m.curUrl = ""
    m.fileIdList = []
    m.fileNames = {}
    m.lastMinute = -1
    m.cfg = getAppConfig()
    setStatus("none", "")
    while true
        msg = wait(30000, m.port)
        if type(msg) = "roSGNodeEvent" then handleEvent(msg)
        if m.pendingCmd <> invalid then
            c = m.pendingCmd
            m.pendingCmd = invalid
            handleCmd(c)
        end if
        now = CreateObject("roDateTime").AsSeconds()
        if m.nextLoad > 0 and now >= m.nextLoad then
            dbg("EPG", "refresh of the TV guide in the background")
            loadFromNetwork()
        else if m.loadedAt > 0 and now \ 60 <> m.lastMinute then
            publishNow()
        end if
    end while
end sub

sub handleEvent(msg as object)
    f = msg.getField()
    if f = "cmd" then
        if m.loading then
            c = msg.getData()
            ' Manual refresh of the same link while it is already downloading: nothing to do
            ' Ручное обновление той же ссылки, пока она уже качается, — ничего не делаем
            if c <> invalid and c.url = m.url and c.force = true then return
            m.pendingCmd = c
        else
            handleCmd(msg.getData())
        end if
    else if f = "detailKey" then
        answerDetail(msg.getData())
    else if f = "listKey" then
        answerList(msg.getData())
    end if
end sub

' Between chunks: answer the scene without waiting for the whole file
' Между кусками: отвечаем сцене, не дожидаясь всего файла
sub serviceRequests()
    while true
        msg = m.port.GetMessage()
        if msg = invalid then return
        if type(msg) = "roSGNodeEvent" then handleEvent(msg)
    end while
end sub

sub handleCmd(c as dynamic)
    if c = invalid or c.action <> "load" then return
    m.keys = c.keys
    if m.keys = invalid then m.keys = {}
    m.url = c.url
    if m.url = invalid then m.url = ""
    if m.url = "" then
        dbg("EPG", "no TV guide link in the playlist")
        clearData()
        m.curUrl = ""
        setStatus("nourl", "")
        return
    end if
    ' Another link: the old programme does not belong to it / Другая ссылка — старая программа не подходит
    if m.url <> m.curUrl then clearData()
    m.curUrl = m.url
    if c.force <> true and m.loadedAt > 0 then
        ' Same link, programme already in memory (the playlist was refreshed): only re-map
        ' Та же ссылка, программа уже в памяти (обновился плейлист): только пересопоставить
        remap()
        dbg("EPG", "re-mapped with the playlist; channels with guide " + countWithGuide().ToStr() + " of " + m.keys.Count().ToStr())
        m.lastMinute = -1
        publishNow()
        setStatus("ready", "")
        return
    end if
    if c.force <> true and loadFromCache() then return
    loadFromNetwork()
end sub

' Playlist keys -> file channels using the ids/names kept from the last load
' Ключи плейлиста -> каналы файла по id/названиям, сохранённым от последней загрузки
sub remap()
    epgParseBegin(m.keys)
    for each fid in m.fileIdList
        m.ep.fileIds[fid] = true
    end for
    m.ep.fileNames = m.fileNames
    epgBuildKeyMap()
    m.keyToFile = m.ep.keyToFile
    m.ep = invalid
end sub

' A command for another playlist came during the download: stop it
' Во время загрузки пришла команда для другого плейлиста — прекращаем
function loadAborted() as boolean
    c = m.pendingCmd
    if c = invalid or c.url = m.url then return false
    dbg("EPG", "download stopped: another playlist")
    m.loading = false
    if CreateObject("roFileSystem").Exists("cachefs:/epg_download.xml") then DeleteFile("cachefs:/epg_download.xml")
    return true
end function

sub clearData()
    m.progs = {}
    m.keyToFile = {}
    m.loadedAt = 0
    m.nextLoad = 0
    m.top.nowMap = {}
end sub

sub setStatus(state as string, err as string)
    upd = ""
    if m.loadedAt > 0 then
        dt = CreateObject("roDateTime")
        dt.FromSeconds(m.loadedAt)
        dt.ToLocalTime()
        upd = epgPad(dt.GetHours()) + ":" + epgPad(dt.GetMinutes())
    end if
    m.top.status = { state: state, channels: countWithGuide(), updated: upd, error: err }
end sub

function countWithGuide() as integer
    n = 0
    for each key in m.keyToFile
        if m.progs.DoesExist(m.keyToFile[key]) then n = n + 1
    end for
    return n
end function

' ---------- saved copy / сохранённая копия ----------

function cacheFile() as string
    return "cachefs:/epg.json"
end function

' FormatJson with &h0001: Cyrillic is written as is; by default every letter became \uXXXX
' (6 bytes instead of 2), the 1.3.1 copy was about 3 times bigger than needed.
' FormatJson с &h0001: кириллица как есть; по умолчанию каждая буква становилась \uXXXX.
' Compact copy (v2): times are offsets from "base" (start = s - base, length = e - s),
' descriptions are kept only for programmes around now (−3 h … +12 h). The old copy is
' deleted before writing: in 1.3.1 a full copy did not fit into cachefs: and Roku cleared
' the whole cachefs: of the app (the playlist copy too).
' Компактная копия (v2): время — смещения от base (начало = s − base, длина = e − s),
' описания — только у передач около «сейчас» (−3 ч … +12 ч). Старая копия удаляется перед
' записью: в 1.3.1 полная копия не помещалась в cachefs:, и Roku очищал весь cachefs:
' приложения (вместе с копией плейлиста).
' 1.3.3: the copy is split into parts of ~25 000 programmes (3–4 MB each): ReadAsciiFile
' refused the single 20 MB file ("file is too big"). epg.json is a small index.
' 1.3.3: копия разбита на части по ~25 000 передач (3–4 МБ): ReadAsciiFile не читал
' один файл в 20 МБ («file is too big»). epg.json — маленький оглавление.
function partFile(i as integer) as string
    return "cachefs:/epg_" + i.ToStr() + ".json"
end function

sub deleteCopy()
    fs = CreateObject("roFileSystem")
    if fs.Exists(cacheFile()) then DeleteFile(cacheFile())
    for i = 0 to 39
        if fs.Exists(partFile(i)) then DeleteFile(partFile(i))
    end for
end sub

function loadFromCache() as boolean
    t0 = CreateObject("roTimespan")
    fs = CreateObject("roFileSystem")
    if not fs.Exists(cacheFile()) then return false
    idx = ParseJson(ReadAsciiFile(cacheFile()))
    if idx = invalid or idx.url <> m.url or idx.v <> 3 or idx.parts = invalid then return false
    now = CreateObject("roDateTime").AsSeconds()
    age = now - idx.loadedAt
    if age >= m.cfg.epgMaxAgeSec then
        dbg("EPG", "saved copy is older than " + Int(m.cfg.epgMaxAgeSec / 3600).ToStr() + " h, not used")
        return false
    end if
    ' Expand offsets back to seconds / Смещения обратно в секунды
    base = idx.base
    progs = {}
    for p = 0 to idx.parts - 1
        if not fs.Exists(partFile(p)) then
            dbg("EPG", "saved copy part " + p.ToStr() + " is missing")
            return false
        end if
        part = ParseJson(ReadAsciiFile(partFile(p)))
        if part = invalid then
            dbg("EPG", "saved copy part " + p.ToStr() + " is not readable")
            return false
        end if
        for each fid in part
            c = part[fid]
            pr = { s: [], e: [], t: c.t, d: c.d }
            n = c.s.Count()
            for i = 0 to n - 1
                st = base + c.s[i]
                pr.s.push(st)
                pr.e.push(st + c.l[i])
            end for
            progs[fid] = pr
        end for
        part = invalid
    end for
    m.progs = progs
    m.loadedAt = idx.loadedAt
    m.fileIdList = idx.ids
    m.fileNames = idx.names
    ' Older than the refresh period: shown now, fresh file in the background (next loop pass)
    ' Старше срока обновления: показываем сейчас, свежий файл — в фоне (следующий проход цикла)
    m.nextLoad = m.loadedAt + m.cfg.epgRefreshSec
    if m.nextLoad <= now then m.nextLoad = now + 5
    dbg("EPG", "saved copy is " + Int(age / 60).ToStr() + " min old; next download in " + Int((m.nextLoad - now) / 60).ToStr() + " min")
    ' Re-map with the current playlist / Пересопоставляем с текущим плейлистом
    remap()
    dbg("EPG", "saved copy read (" + idx.parts.ToStr() + " parts) in " + t0.TotalMilliseconds().ToStr() + " ms; channels with guide " + countWithGuide().ToStr() + " of " + m.keys.Count().ToStr())
    m.lastMinute = -1
    publishNow()
    setStatus("ready", "")
    return true
end function

sub saveCache(ids as object, names as object)
    t0 = CreateObject("roTimespan")
    fs = CreateObject("roFileSystem")
    ' Free the space of the old copy first / Сначала освобождаем место старой копии
    deleteCopy()
    idList = []
    for each fid in ids
        idList.push(fid)
    end for
    now = CreateObject("roDateTime").AsSeconds()
    base = now - 26 * 3600
    dLo = now - 3 * 3600
    dHi = now + 12 * 3600
    parts = 0
    total = 0
    ok = true
    part = {}
    inPart = 0
    for each fid in m.progs
        pr = m.progs[fid]
        c = { s: [], l: [], t: pr.t, d: [] }
        n = pr.s.Count()
        for i = 0 to n - 1
            c.s.push(pr.s[i] - base)
            c.l.push(pr.e[i] - pr.s[i])
            if pr.e[i] > dLo and pr.s[i] < dHi then c.d.push(pr.d[i]) else c.d.push("")
        end for
        part[fid] = c
        inPart = inPart + n
        if inPart >= 25000 then
            ok = ok and writePart(parts, part, fs)
            parts = parts + 1
            part = {}
            inPart = 0
        end if
    end for
    if inPart > 0 and ok then
        ok = writePart(parts, part, fs)
        parts = parts + 1
    end if
    if ok then
        ok = WriteAsciiFile(cacheFile(), FormatJson({ v: 3, url: m.url, loadedAt: m.loadedAt, base: base, parts: parts, ids: idList, names: names }, &h0001))
    end if
    for p = 0 to parts - 1
        st = fs.Stat(partFile(p))
        if st <> invalid and st.size <> invalid then total = total + st.size
    end for
    if not ok then deleteCopy()
    dbg("EPG", "saved copy written=" + ok.ToStr() + ", " + parts.ToStr() + " parts, " + Int(total / 1024).ToStr() + " KB on disk, in " + t0.TotalMilliseconds().ToStr() + " ms")
end sub

function writePart(i as integer, part as object, fs as object) as boolean
    ok = WriteAsciiFile(partFile(i), FormatJson(part, &h0001))
    if not ok then dbg("ERR", "saved copy part " + i.ToStr() + " not written")
    return ok
end function

' ---------- download + parse / загрузка и разбор ----------

sub loadFromNetwork()
    ' The old programme stays on screen until the new one is ready
    ' Старая программа остаётся на экране, пока не готова новая
    m.hadOld = m.loadedAt > 0
    m.nextLoad = 0
    if not m.hadOld then
        clearData()
        setStatus("loading", "")
    end if
    m.loading = true
    m.partialAt = 0
    t0 = CreateObject("roTimespan")
    dt = CreateObject("roDateTime")
    dt.ToLocalTime()
    nTvg = 0
    for each key in m.keys
        if key.Left(2) <> "n:" then nTvg = nTvg + 1
    end for
    dbg("EPG", "TV time " + dt.ToISOString() + "; playlist channels " + m.keys.Count().ToStr() + ", with tvg-id " + nTvg.ToStr())
    dbg("EPG", "loading " + maskUrl(m.url))

    head = headRequest(m.url)
    dbg("EPG", "answer HTTP " + head.code.ToStr() + "; type " + head.ctype + ", size " + head.size.ToStr() + ", encoding " + head.enc + ", ranges " + head.ranges)
    if head.code < 200 or head.code >= 400 then
        failLoad("http " + head.code.ToStr())
        return
    end if
    low = LCase(m.url)
    qp = low.Instr("?")
    if qp >= 0 then low = low.Left(qp)
    if low.EndsWith(".gz") or head.ctype.Instr("gzip") >= 0 then
        ' Roku cannot unpack a gzip file / Roku не умеет распаковать gzip-файл
        failLoad("gzip")
        return
    end if

    epgParseBegin(m.keys)
    chunkSize = m.cfg.epgChunkBytes
    ok = true
    if head.ranges = "bytes" and head.size > 0 then
        ok = readByRanges(head.size, chunkSize, t0)
    else
        ok = readViaFile(head.size, chunkSize, t0)
    end if
    if not ok then
        m.ep = invalid
        m.loading = false
        return
    end if
    epgParseFinish()

    ep = m.ep
    m.progs = ep.progs
    m.keyToFile = ep.keyToFile
    m.loadedAt = CreateObject("roDateTime").AsSeconds()
    dbg("EPG", Int(head.size / 1048576).ToStr() + " MB in " + t0.TotalMilliseconds().ToStr() + " ms; file channels " + ep.fileIds.Count().ToStr() + ", matched by id " + ep.byId.ToStr() + ", by name " + ep.byName.ToStr() + "; programmes " + ep.total.ToStr() + ", kept " + ep.kept.ToStr() + "; channels with guide " + countWithGuide().ToStr() + " of " + m.keys.Count().ToStr())
    dbg("EPG", "dropped: channel not in playlist " + ep.dropChan.ToStr() + ", outside the window " + ep.dropWindow.ToStr() + ", bad time " + ep.dropTime.ToStr())
    dbg("EPG", "file programmes " + ep.minDate + " .. " + ep.maxDate + " (window " + epgStamp(ep.winLo) + " .. " + epgStamp(ep.winHi) + " UTC)")
    if m.keys.Count() > 0 and (ep.byId + ep.byName) * 2 < m.keys.Count() then
        logIdSamples(ep)
    end if
    ids = ep.fileIds
    names = ep.fileNames
    m.ep = invalid
    m.fileIdList = []
    for each fid in ids
        m.fileIdList.push(fid)
    end for
    m.fileNames = names
    saveCache(ids, names)
    logMemory()
    m.loading = false
    m.nextLoad = m.loadedAt + m.cfg.epgRefreshSec
    m.lastMinute = -1
    publishNow()
    setStatus("ready", "")
end sub

' First load (no old programme): publish what is parsed so far, every 20 MB
' Первая загрузка (старой программы нет): публикуем уже разобранное каждые 20 МБ
sub publishPartial()
    if m.hadOld or m.ep = invalid or not m.ep.channelsDone then return
    m.progs = m.ep.progs
    m.keyToFile = m.ep.keyToFile
    m.lastMinute = -1
    publishNow()
    setStatus("loading", "")
end sub

' App memory use after the guide is loaded / Память приложения после загрузки программы
sub logMemory()
    mon = CreateObject("roAppMemoryMonitor")
    if mon <> invalid and FindMemberFunction(mon, "GetMemoryLimitPercent") <> invalid then
        dbg("MEM", "app memory used " + mon.GetMemoryLimitPercent().ToStr() + "% of the limit")
    end if
end sub

sub failLoad(why as string)
    m.loading = false
    if m.hadOld then
        ' Keep the old programme, try again in 30 minutes / Оставляем старую, повтор через 30 минут
        dbg("ERR", "EPG refresh failed: " + why + "; the old programme stays, retry in 30 min")
        m.nextLoad = CreateObject("roDateTime").AsSeconds() + 1800
        setStatus("ready", "")
        return
    end if
    dbg("ERR", "EPG not loaded: " + why)
    clearData()
    setStatus("error", why)
end sub

' Less than half matched: show examples to compare / Совпало меньше половины: примеры для сравнения
sub logIdSamples(ep as object)
    a = []
    for each fid in ep.fileIds
        a.push(fid)
        if a.Count() >= 5 then exit for
    end for
    b = []
    for each key in m.keys
        b.push(key)
        if b.Count() >= 5 then exit for
    end for
    dbg("EPG", "ids in file: " + a.Join(", ") + " | in playlist: " + b.Join(", "))
end sub

function newRequest(url as string) as object
    req = CreateObject("roUrlTransfer")
    req.SetUrl(url)
    req.SetCertificatesFile("common:/certs/ca-bundle.crt")
    req.EnableEncodings(true)
    req.AddHeader("Cache-Control", "no-cache")
    return req
end function

function headRequest(url as string) as object
    res = { code: 0, ctype: "", size: 0, enc: "-", ranges: "" }
    port = CreateObject("roMessagePort")
    req = newRequest(url)
    req.SetMessagePort(port)
    if not req.AsyncHead() then return res
    msg = wait(30000, port)
    if type(msg) <> "roUrlEvent" then
        req.AsyncCancel()
        return res
    end if
    res.code = msg.GetResponseCode()
    h = msg.GetResponseHeaders()
    if h <> invalid then
        if h["content-type"] <> invalid then res.ctype = LCase(h["content-type"])
        if h["content-length"] <> invalid then res.size = h["content-length"].ToInt()
        if h["content-encoding"] <> invalid then res.enc = h["content-encoding"]
        if h["accept-ranges"] <> invalid then res.ranges = LCase(h["accept-ranges"])
    end if
    return res
end function

' Server supports Range: read the file from the network piece by piece
' Сервер умеет Range: читаем файл из сети кусками
function readByRanges(size as integer, chunkSize as integer, t0 as object) as boolean
    port = CreateObject("roMessagePort")
    offset = 0
    nextLog = 20 * 1048576
    first = true
    while offset < size
        if t0.TotalSeconds() > m.cfg.epgTimeoutSec then
            failLoad("timeout")
            return false
        end if
        last = offset + chunkSize - 1
        if last >= size - 1 then last = size - 1
        req = newRequest(m.url)
        req.SetMessagePort(port)
        req.AddHeader("Range", "bytes=" + offset.ToStr() + "-" + last.ToStr())
        if not req.AsyncGetToString() then
            failLoad("network")
            return false
        end if
        msg = wait(60000, port)
        if type(msg) <> "roUrlEvent" then
            req.AsyncCancel()
            failLoad("timeout")
            return false
        end if
        code = msg.GetResponseCode()
        if code <> 206 and not (code = 200 and offset = 0 and last = size - 1) then
            failLoad("range http " + code.ToStr())
            return false
        end if
        text = msg.GetString()
        if first then
            logFileStart(text)
            first = false
        end if
        offset = last + 1
        epgParseChunk(text, offset >= size)
        text = ""
        if offset >= nextLog then
            logProgress(offset, t0)
            publishPartial()
            nextLog = nextLog + 20 * 1048576
        end if
        serviceRequests()
        if loadAborted() then return false
    end while
    return true
end function

' No Range: save the file to cachefs: and read it from there piece by piece
' Нет Range: сохраняем файл в cachefs: и читаем оттуда кусками
function readViaFile(size as integer, chunkSize as integer, t0 as object) as boolean
    path = "cachefs:/epg_download.xml"
    port = CreateObject("roMessagePort")
    req = newRequest(m.url)
    req.SetMessagePort(port)
    if not req.AsyncGetToFile(path) then
        failLoad("file")
        return false
    end if
    msg = wait(m.cfg.epgTimeoutSec * 1000, port)
    if type(msg) <> "roUrlEvent" then
        req.AsyncCancel()
        DeleteFile(path)
        failLoad("timeout")
        return false
    end if
    if msg.GetResponseCode() <> 200 then
        DeleteFile(path)
        failLoad("http " + msg.GetResponseCode().ToStr())
        return false
    end if
    fs = CreateObject("roFileSystem")
    st = fs.Stat(path)
    if st = invalid or st.size = invalid then
        failLoad("file too large for the device")
        return false
    end if
    size = st.size
    dbg("EPG", "downloaded to file " + Int(size / 1048576).ToStr() + " MB in " + t0.TotalMilliseconds().ToStr() + " ms")
    ba = CreateObject("roByteArray")
    offset = 0
    nextLog = 20 * 1048576
    while offset < size
        n = chunkSize
        if offset + n > size then n = size - offset
        ba.ReadFile(path, offset, n)
        text = ba.ToAsciiString()
        if offset = 0 then logFileStart(text)
        offset = offset + n
        epgParseChunk(text, offset >= size)
        text = ""
        if offset >= nextLog then
            logProgress(offset, t0)
            publishPartial()
            nextLog = nextLog + 20 * 1048576
        end if
        serviceRequests()
        if loadAborted() then return false
    end while
    DeleteFile(path)
    return true
end function

sub logFileStart(text as string)
    s = text.Left(200).Replace(Chr(10), " ").Replace(Chr(13), " ")
    dbg("EPG", "file start: " + s)
end sub

sub logProgress(offset as integer, t0 as object)
    ep = m.ep
    dbg("EPG", "... " + Int(offset / 1048576).ToStr() + " MB in " + t0.TotalMilliseconds().ToStr() + " ms; programmes " + ep.total.ToStr() + ", kept " + ep.kept.ToStr())
end sub

' ---------- answers to the scene / ответы сцене ----------

' Index of the programme on air, or of the next one if none / Передача в эфире или следующая
function currentIndex(pr as object, now as integer) as integer
    n = pr.s.Count()
    for i = 0 to n - 1
        if pr.e[i] > now then
            return i
        end if
    end for
    return -1
end function

sub publishNow()
    now = CreateObject("roDateTime").AsSeconds()
    m.lastMinute = now \ 60
    map = {}
    for each key in m.keyToFile
        pr = m.progs[m.keyToFile[key]]
        if pr <> invalid then
            i = currentIndex(pr, now)
            if i >= 0 and pr.s[i] <= now then
                map[key] = pr.s[i].ToStr() + "|" + pr.e[i].ToStr() + "|" + pr.t[i]
            end if
        end if
    end for
    m.top.nowMap = map
end sub

' Whole window of one channel (yesterday .. tomorrow) / Всё окно одного канала
sub answerList(key as dynamic)
    if key = invalid or key = "" then return
    items = []
    fid = m.keyToFile[key]
    if fid <> invalid then
        pr = m.progs[fid]
        if pr <> invalid then
            for k = 0 to pr.s.Count() - 1
                items.push({ s: pr.s[k], e: pr.e[k], t: pr.t[k], d: pr.d[k] })
            end for
        end if
    end if
    m.top.list = { key: key, items: items }
end sub

sub answerDetail(key as dynamic)
    if key = invalid or key = "" then return
    items = []
    fid = m.keyToFile[key]
    if fid <> invalid then
        pr = m.progs[fid]
        if pr <> invalid then
            now = CreateObject("roDateTime").AsSeconds()
            i = currentIndex(pr, now)
            if i >= 0 then
                last = i + 8
                if last > pr.s.Count() - 1 then last = pr.s.Count() - 1
                for k = i to last
                    items.push({ s: pr.s[k], e: pr.e[k], t: pr.t[k], d: pr.d[k] })
                end for
            end if
        end if
    end if
    m.top.detail = { key: key, items: items, known: (fid <> invalid) }
end sub
