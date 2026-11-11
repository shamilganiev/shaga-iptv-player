' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Saved settings in the device registry (one JSON record, schema version "v").
' Updates of the app must keep this data (certification 4.1).
' Сохранённые настройки в реестре устройства (одна JSON-запись, версия схемы "v").
' Обновления приложения должны сохранять эти данные (сертификация 4.1).
'
' { v: 1, lang: "auto", lastOkId: "p3", nextId: 4,
'   playlists: [ { id: "p1", name: "example.com", url: "https://..." } ],
'   pin: "" or "h:<sha256>" (hash of the PIN, never the digits / хеш PIN, не сами цифры),
'   playlists[].arch: { t: "auto"|"off"|"shift"|"flussonic"|"xc", d: days } (archive / архив),
'   groupModes: { "g:<group name>": "s" | "p" | "h" } (shown / PIN / hidden),
'   favorites: [channel names / названия каналов], lastChannel: { n: name, g: group },
'   zapMode: "list" (Up = smaller number / вверх = меньший номер) | "classic" }

function storageSection() as object
    return CreateObject("roRegistrySection", "ShaGaIPTV")
end function

function storageDefault() as object
    return { v: 1, lang: "auto", lastOkId: "", nextId: 1, playlists: [], pin: "", groupModes: {}, favorites: [], lastChannel: invalid, zapMode: "list" }
end function

function storageLoad() as object
    data = storageDefault()
    sec = storageSection()
    if sec.Exists("data") then
        parsed = ParseJson(sec.Read("data"))
        if parsed <> invalid and type(parsed) = "roAssociativeArray" then
            data = storageMigrate(parsed)
        else
            dbg("ERR", "registry data is not valid JSON, starting fresh")
        end if
    end if
    return data
end function

' Bring older records to the current schema / Приводим старые записи к текущей схеме
function storageMigrate(d as object) as object
    out = storageDefault()
    if d.lang <> invalid then out.lang = d.lang.ToStr()
    if d.lastOkId <> invalid then out.lastOkId = d.lastOkId.ToStr()
    if d.nextId <> invalid then out.nextId = d.nextId
    if d.pin <> invalid then out.pin = d.pin.ToStr()
    ' Old versions kept the PIN as plain digits: turn it into a hash once
    ' Старые версии хранили PIN цифрами: один раз превращаем в хеш
    if out.pin <> "" and out.pin.Left(2) <> "h:" then out.pin = pinHash(out.pin)
    if d.groupModes <> invalid and type(d.groupModes) = "roAssociativeArray" then out.groupModes = d.groupModes
    if d.favorites <> invalid and type(d.favorites) = "roArray" then out.favorites = d.favorites
    if d.lastChannel <> invalid and type(d.lastChannel) = "roAssociativeArray" then out.lastChannel = d.lastChannel
    if d.zapMode = "classic" then out.zapMode = "classic"
    if d.playlists <> invalid and type(d.playlists) = "roArray" then
        for each p in d.playlists
            if p <> invalid and p.url <> invalid and p.id <> invalid then
                nm = p.name
                if nm = invalid then nm = playlistNameFromUrl(p.url)
                pl = { id: p.id.ToStr(), name: nm.ToStr(), url: p.url.ToStr() }
                if p.arch <> invalid and p.arch.t <> invalid then pl.arch = p.arch
                out.playlists.push(pl)
            end if
        end for
    end if
    return out
end function

sub storageSave(data as object)
    sec = storageSection()
    sec.Write("data", FormatJson(data))
    sec.Flush()
end sub

function storageFindIndex(data as object, id as string) as integer
    for i = 0 to data.playlists.Count() - 1
        if data.playlists[i].id = id then return i
    end for
    return -1
end function

function storageFindUrl(data as object, url as string) as integer
    for i = 0 to data.playlists.Count() - 1
        if data.playlists[i].url = url then return i
    end for
    return -1
end function

' Short name for a playlist: host + file name / Короткое имя: сайт + имя файла
function playlistNameFromUrl(url as string) as string
    s = url
    p = s.Instr("://")
    if p >= 0 then s = s.Mid(p + 3)
    at = s.Instr("@")
    sl = s.Instr("/")
    if at >= 0 and (sl < 0 or at < sl) then s = s.Mid(at + 1)
    q = s.Instr("?")
    if q >= 0 then s = s.Left(q)
    host = s
    file = ""
    sl = s.Instr("/")
    if sl >= 0 then
        host = s.Left(sl)
        parts = s.Mid(sl + 1).Split("/")
        if parts.Count() > 0 then file = parts[parts.Count() - 1]
    end if
    if file <> "" and file.Len() <= 24 then return host + " · " + file
    return host
end function

' Clean up user input: trim, drop spaces, add https:// if no scheme.
' Returns "" if empty. / Чистим ввод: обрезаем, убираем пробелы, добавляем https://.
function normalizeUrl(raw as dynamic) as string
    if raw = invalid then return ""
    s = raw.ToStr().Trim()
    s = s.Replace(" ", "")
    if s = "" then return ""
    low = LCase(s)
    if low.Instr("://") < 0 then
        s = "https://" + s
    else if low.Left(7) = "http://" then
        s = "http://" + s.Mid(7)
    else if low.Left(8) = "https://" then
        s = "https://" + s.Mid(8)
    end if
    return s
end function

' Basic validity: http(s) scheme and a dot in the host / Простая проверка ссылки
function isValidUrl(s as string) as boolean
    low = LCase(s)
    if low.Left(7) <> "http://" and low.Left(8) <> "https://" then return false
    rest = s.Mid(s.Instr("://") + 3)
    sl = rest.Instr("/")
    host = rest
    if sl >= 0 then host = rest.Left(sl)
    if host.Len() < 3 then return false
    if host.Instr(".") < 0 and host.Instr(":") < 0 then return false
    return true
end function

' PIN is stored as a hash: the digits cannot be read back
' PIN хранится как хеш: цифры из него не восстановить
function pinHash(pin as string) as string
    ba = CreateObject("roByteArray")
    ba.FromAsciiString("shaga-iptv-player:" + pin)
    dg = CreateObject("roEVPDigest")
    dg.Setup("sha256")
    return "h:" + dg.Process(ba)
end function

function pinIsSet(data as object) as boolean
    return data.pin <> ""
end function

function pinMatches(data as object, pin as string) as boolean
    if data.pin = "" or pin = "" then return false
    return pinHash(pin) = data.pin
end function
