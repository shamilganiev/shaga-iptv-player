' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' XMLTV parsing helpers. The file is fed in pieces (chunks); only complete
' <channel> and <programme> elements are parsed, the tail waits for the next chunk.
' Kept: programmes of playlist channels inside the window "−24 h … +24 h";
' short descriptions (200 chars) for all of them; the full text is fetched on request (1.3.4).
' Помощники разбора XMLTV. Файл подаётся кусками; разбираются только законченные
' <channel> и <programme>, хвост ждёт следующего куска.
' Храним: передачи каналов плейлиста в окне «−24 ч … +24 ч»;
' описания — у всех, до 300 символов (1.3.2; раньше — только ближайшие 4 часа).
'
' State in m.ep (one parse) / Состояние в m.ep (один разбор):
'   carry, channelsDone, fileIds{}, fileNames{normName: id}, wanted{fileId}, keyToFile{},
'   progs{fileId: {s:[], e:[], t:[], d:[]}}, winLo, winHi, descUntil, winCache{},
'   stats: total, kept, dropChan, dropWindow, dropTime, minDate, maxDate

sub epgParseBegin(keys as object)
    now = CreateObject("roDateTime").AsSeconds()
    m.ep = {
        carry: "", channelsDone: false, keys: keys
        fileIds: {}, fileNames: {}, wanted: {}, keyToFile: {}, progs: {}
        winLo: now - 24 * 3600, winHi: now + 24 * 3600, descUntil: now + 4 * 3600, winCache: {}
        total: 0, kept: 0, dropChan: 0, dropWindow: 0, dropTime: 0, minDate: "", maxDate: ""
        byId: 0, byName: 0
        carryPos: 0, ba: CreateObject("roByteArray")   ' byte position of the carry / байтовая позиция хвоста
        rxChannel: CreateObject("roRegex", "<channel\s+id\s*=\s*[" + Chr(34) + "']([^" + Chr(34) + "']+)[" + Chr(34) + "'][^>]*>(.*?)</channel>", "s")
        rxName: CreateObject("roRegex", "<display-name[^>]*>([^<]*)</display-name>", "")
    }
end sub

' Feed one chunk; last = true for the end of the file
' Подать один кусок; last = true в конце файла
sub epgParseChunk(chunk as string, last as boolean)
    ep = m.ep
    buf = ep.carry + chunk
    ep.carry = ""
    ' 1.3.4: byte position of every programme in the file, to fetch its full description later
    ' 1.3.4: байтовое место каждой передачи в файле — чтобы потом скачать полное описание
    cur = ep.carryPos
    if not ep.channelsDone then
        p = buf.Instr("<programme")
        if p < 0 and not last then
            ' Still in the <channel> section: parse what is complete, keep the rest
            ' Ещё раздел <channel>: разбираем законченное, остаток ждёт
            pieces = buf.Split("</channel>")
            n = pieces.Count()
            for i = 0 to n - 2
                epgParseChannels(pieces[i] + "</channel>")
                cur = cur + epgBytes(pieces[i]) + 10
            end for
            ep.carry = pieces[n - 1]
            ep.carryPos = cur
            return
        end if
        if p < 0 then p = buf.Len()
        head = buf.Left(p)
        epgParseChannels(head)
        cur = cur + epgBytes(head)
        buf = buf.Mid(p)
        ep.channelsDone = true
        epgBuildKeyMap()
    end if
    pieces = buf.Split("</programme>")
    n = pieces.Count()
    lastIdx = n - 1
    if last then lastIdx = n
    for i = 0 to lastIdx - 1
        if i < n - 1 or last then
            epgParseProgramme(pieces[i], cur)
            cur = cur + epgBytes(pieces[i]) + 12
        end if
    end for
    if not last then
        ep.carry = pieces[n - 1]
        ep.carryPos = cur
    end if
end sub

sub epgParseChannels(text as string)
    ep = m.ep
    for each mt in ep.rxChannel.MatchAll(text)
        fid = mt[1]
        ep.fileIds[fid] = true
        for each nm in ep.rxName.MatchAll(mt[2])
            key = epgNameKey(epgDecode(nm[1]))
            if key <> "" and not ep.fileNames.DoesExist(key) then ep.fileNames[key] = fid
        end for
    end for
end sub

' Playlist channel key -> file channel id: by tvg-id, then by display-name
' Ключ канала плейлиста -> id канала файла: по tvg-id, затем по display-name
sub epgBuildKeyMap()
    ep = m.ep
    ep.keyToFile = {}
    ep.wanted = {}
    ep.byId = 0
    ep.byName = 0
    for each key in ep.keys
        fid = invalid
        if ep.fileIds.DoesExist(key) then
            fid = key
            ep.byId = ep.byId + 1
        else
            nm = ep.keys[key]
            if nm <> invalid and nm <> "" and ep.fileNames.DoesExist(nm) then
                fid = ep.fileNames[nm]
                ep.byName = ep.byName + 1
            end if
        end if
        if fid <> invalid then
            ep.keyToFile[key] = fid
            ep.wanted[fid] = true
        end if
    end for
end sub

' UTF-8 length of a string / Длина строки в байтах UTF-8
function epgBytes(t as string) as integer
    m.ep.ba.FromAsciiString(t)
    return m.ep.ba.Count()
end function

' pieceByte: byte position of the piece in the file / байтовое место куска в файле
sub epgParseProgramme(piece as string, pieceByte as integer)
    ep = m.ep
    p = piece.Instr("<programme")
    if p < 0 then return
    te = piece.Instr(p, ">")
    if te < 0 then return
    head = piece.Mid(p, te - p)
    ep.total = ep.total + 1
    cid = epgAttr(head, "channel")
    if not ep.wanted.DoesExist(cid) then
        ep.dropChan = ep.dropChan + 1
        return
    end if
    st = epgAttr(head, "start")
    sp = epgAttr(head, "stop")
    if st.Len() < 14 then
        ep.dropTime = ep.dropTime + 1
        return
    end if
    sd = st.Left(14)
    offs = st.Mid(14).Trim()
    ed = sd
    if sp.Len() >= 14 then ed = sp.Left(14)
    if ep.minDate = "" or sd < ep.minDate then ep.minDate = sd
    if sd > ep.maxDate then ep.maxDate = sd
    ' Window check on strings in the file's own time zone (no date math for dropped ones)
    ' Проверка окна строками в поясе самого файла (без расчёта дат для отброшенных)
    w = ep.winCache[offs]
    if w = invalid then
        o = epgOffsetSec(offs)
        w = { lo: epgStamp(ep.winLo + o), hi: epgStamp(ep.winHi + o) }
        ep.winCache[offs] = w
    end if
    if ed <= w.lo or sd >= w.hi then
        ep.dropWindow = ep.dropWindow + 1
        return
    end if
    s = epgToEpoch(sd, offs)
    e = s + 1800
    if sp.Len() >= 14 then e = epgToEpoch(ed, epgStopOffset(sp, offs))
    if s < 0 then
        ep.dropTime = ep.dropTime + 1
        return
    end if
    title = epgTag(piece, "title", te)
    desc = epgTag(piece, "desc", te)
    ' Short text for rows and the info column; the full one is fetched on request (1.3.4)
    ' Короткий текст для строк и колонки; полный скачивается по запросу (1.3.4)
    if desc.Len() > 200 then desc = desc.Left(197) + "..."

    pr = ep.progs[cid]
    if pr = invalid then
        pr = { s: [], e: [], t: [], d: [], o: [] }
        ep.progs[cid] = pr
    end if
    pr.s.push(s)
    pr.e.push(e)
    pr.t.push(title)
    pr.d.push(desc)
    pr.o.push(pieceByte + p)   ' «<programme» is ASCII before it, usually just a line break / перед ним обычно перевод строки
    ep.kept = ep.kept + 1
end sub

' After the last chunk: make sure each channel is sorted by start time
' После последнего куска: передачи каждого канала по возрастанию времени
sub epgParseFinish()
    for each fid in m.ep.progs
        pr = m.ep.progs[fid]
        sorted = true
        for i = 1 to pr.s.Count() - 1
            if pr.s[i] < pr.s[i - 1] then
                sorted = false
                exit for
            end if
        end for
        if not sorted then m.ep.progs[fid] = epgSortProgs(pr)
    end for
end sub

function epgSortProgs(pr as object) as object
    idx = []
    for i = 0 to pr.s.Count() - 1
        idx.push({ k: pr.s[i], i: i })
    end for
    idx.SortBy("k")
    out = { s: [], e: [], t: [], d: [], o: [] }
    for each it in idx
        out.s.push(pr.s[it.i])
        out.e.push(pr.e[it.i])
        out.t.push(pr.t[it.i])
        out.d.push(pr.d[it.i])
        out.o.push(pr.o[it.i])
    end for
    return out
end function

' Attribute value in double or single quotes / Значение атрибута в двойных или одинарных кавычках
function epgAttr(head as string, name as string) as string
    i = head.Instr(" " + name + "=")
    if i < 0 then return ""
    vStart = i + name.Len() + 2
    q = head.Mid(vStart, 1)
    if q <> Chr(34) and q <> "'" then return ""
    j = head.Instr(vStart + 1, q)
    if j < 0 then return ""
    return head.Mid(vStart + 1, j - vStart - 1)
end function

function epgTag(piece as string, tag as string, from as integer) as string
    a = piece.Instr(from, "<" + tag)
    if a < 0 then return ""
    b = piece.Instr(a, ">")
    if b < 0 then return ""
    c = piece.Instr(b, "</" + tag + ">")
    if c < 0 then return ""
    return epgDecode(piece.Mid(b + 1, c - b - 1).Trim())
end function

function epgDecode(t as string) as string
    if t.Instr("<![CDATA[") >= 0 then t = t.Replace("<![CDATA[", "").Replace("]]>", "")
    if t.Instr("&") < 0 then return t
    t = t.Replace("&lt;", "<").Replace("&gt;", ">").Replace("&quot;", Chr(34))
    t = t.Replace("&apos;", "'").Replace("&#39;", "'").Replace("&#34;", Chr(34)).Replace("&amp;", "&")
    return t
end function

' Same normalization as the scene's epgNorm: lowercase, ё→е, no spaces/punctuation (ASCII-only lowercasing here is
' enough for matching because both sides go through the same steps on the playlist side in textutil.epgNorm).
' Та же нормализация, что epgNorm в textutil.brs (там — для плейлиста).
function epgNameKey(s as string) as string
    return epgNorm(s)
end function

' "+0300" -> 10800 / "-0500" -> -18000
function epgOffsetSec(offs as string) as integer
    if offs.Len() < 5 then return 0
    v = offs.Mid(1, 2).ToInt() * 3600 + offs.Mid(3, 2).ToInt() * 60
    if offs.Left(1) = "-" then return -v
    return v
end function

function epgStopOffset(sp as string, startOffs as string) as string
    o = sp.Mid(14).Trim()
    if o = "" then return startOffs
    return o
end function

' "20261107030000" + "+0300" -> UTC seconds / в секунды UTC
function epgToEpoch(d as string, offs as string) as integer
    y = d.Left(4).ToInt()
    mo = d.Mid(4, 2).ToInt()
    dd = d.Mid(6, 2).ToInt()
    if y < 1971 or mo < 1 or mo > 12 or dd < 1 then return -1
    days = epgDaysFromCivil(y, mo, dd)
    return days * 86400 + d.Mid(8, 2).ToInt() * 3600 + d.Mid(10, 2).ToInt() * 60 + d.Mid(12, 2).ToInt() - epgOffsetSec(offs)
end function

' Days since 1970-01-01 (H. Hinnant's algorithm) / Дни с 1970-01-01
function epgDaysFromCivil(y as integer, mo as integer, d as integer) as integer
    if mo <= 2 then y = y - 1
    era = y \ 400
    yoe = y - era * 400
    if mo > 2 then mp = mo - 3 else mp = mo + 9
    doy = (153 * mp + 2) \ 5 + d - 1
    doe = yoe * 365 + yoe \ 4 - yoe \ 100 + doy
    return era * 146097 + doe - 719468
end function

' UTC seconds -> "YYYYMMDDHHMMSS" / секунды -> строка
function epgStamp(secs as integer) as string
    dt = CreateObject("roDateTime")
    dt.FromSeconds(secs)
    return dt.GetYear().ToStr() + epgPad(dt.GetMonth()) + epgPad(dt.GetDayOfMonth()) + epgPad(dt.GetHours()) + epgPad(dt.GetMinutes()) + epgPad(dt.GetSeconds())
end function

function epgPad(n as integer) as string
    if n < 10 then return "0" + n.ToStr()
    return n.ToStr()
end function
