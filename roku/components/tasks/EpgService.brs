' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' EpgService: long-running Task with its own message port.
'  - cmd "load": read the saved copy (cachefs:) if fresh, else download the XMLTV
'    in chunks (HTTP Range, or via a file if the server does not support it) and parse.
'  - every 30 s: publish nowMap (programme on air per channel) when the minute changes;
'    reload every 6 hours.
'  - detailKey: answer with the current + next programmes of one channel.
' EpgService: долгоживущая задача со своим портом сообщений.
'  - "load": берём сохранённую копию (cachefs:), если свежая, иначе качаем XMLTV
'    кусками (HTTP Range или через файл, если сервер не умеет) и разбираем.
'  - раз в 30 с: при смене минуты публикуем nowMap; обновление раз в 6 часов.
'  - detailKey: отвечаем текущей и следующими передачами одного канала.
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
    m.lastMinute = -1
    m.cfg = getAppConfig()
    setStatus("none", "")
    while true
        msg = wait(30000, m.port)
        if type(msg) = "roSGNodeEvent" then
            f = msg.getField()
            if f = "cmd" then
                handleCmd(msg.getData())
            else if f = "detailKey" then
                answerDetail(msg.getData())
            else if f = "listKey" then
                answerList(msg.getData())
            end if
        end if
        if m.loadedAt > 0 then
            now = CreateObject("roDateTime").AsSeconds()
            if now - m.loadedAt >= m.cfg.epgRefreshSec then
                dbg("EPG", "refresh after " + Int(m.cfg.epgRefreshSec / 3600).ToStr() + " h")
                loadFromNetwork()
            else if now \ 60 <> m.lastMinute then
                publishNow()
            end if
        end if
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
        setStatus("nourl", "")
        return
    end if
    if c.force <> true and loadFromCache() then return
    loadFromNetwork()
end sub

sub clearData()
    m.progs = {}
    m.keyToFile = {}
    m.loadedAt = 0
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

function loadFromCache() as boolean
    t0 = CreateObject("roTimespan")
    if not CreateObject("roFileSystem").Exists(cacheFile()) then return false
    text = ReadAsciiFile(cacheFile())
    if text = "" then return false
    data = ParseJson(text)
    text = ""
    if data = invalid or data.url <> m.url or data.progs = invalid or data.win <> 24 then return false
    now = CreateObject("roDateTime").AsSeconds()
    if now - data.loadedAt >= m.cfg.epgRefreshSec then
        dbg("EPG", "saved copy is older than the refresh period")
        return false
    end if
    m.progs = data.progs
    m.loadedAt = data.loadedAt
    ' Re-map with the current playlist / Пересопоставляем с текущим плейлистом
    epgParseBegin(m.keys)
    for each fid in data.ids
        m.ep.fileIds[fid] = true
    end for
    m.ep.fileNames = data.names
    epgBuildKeyMap()
    m.keyToFile = m.ep.keyToFile
    m.ep = invalid
    dbg("EPG", "saved copy read in " + t0.TotalMilliseconds().ToStr() + " ms; channels with guide " + countWithGuide().ToStr() + " of " + m.keys.Count().ToStr())
    m.lastMinute = -1
    publishNow()
    setStatus("ready", "")
    return true
end function

sub saveCache(ids as object, names as object)
    t0 = CreateObject("roTimespan")
    idList = []
    for each fid in ids
        idList.push(fid)
    end for
    ok = WriteAsciiFile(cacheFile(), FormatJson({ win: 24, url: m.url, loadedAt: m.loadedAt, progs: m.progs, ids: idList, names: names }))
    dbg("EPG", "saved copy written=" + ok.ToStr() + " in " + t0.TotalMilliseconds().ToStr() + " ms")
end sub

' ---------- download + parse / загрузка и разбор ----------

sub loadFromNetwork()
    clearData()
    setStatus("loading", "")
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
    saveCache(ids, names)
    logMemory()
    m.lastMinute = -1
    publishNow()
    setStatus("ready", "")
end sub

' App memory use after the guide is loaded / Память приложения после загрузки программы
sub logMemory()
    mon = CreateObject("roAppMemoryMonitor")
    if mon <> invalid and FindMemberFunction(mon, "GetMemoryLimitPercent") <> invalid then
        dbg("MEM", "app memory used " + mon.GetMemoryLimitPercent().ToStr() + "% of the limit")
    end if
end sub

sub failLoad(why as string)
    dbg("ERR", "EPG not loaded: " + why)
    m.loadedAt = 0
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
            nextLog = nextLog + 20 * 1048576
        end if
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
            nextLog = nextLog + 20 * 1048576
        end if
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
