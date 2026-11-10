' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Debug log helpers (shared by main, scene and tasks)
' Помощники отладочного лога (общие для main, сцены и задач)
' Output: telnet <roku-ip> 8085 / Вывод: telnet <ip-roku> 8085
' Tags / Теги: APP, KEY, PLAY, ERR, LOAD, MEM, DL, UI

sub dbg(tag as string, msg as string)
    cfg = getAppConfig()
    if cfg.debugLog <> true then return
    dt = CreateObject("roDateTime")
    dt.ToLocalTime()
    t = dbgPad(dt.GetHours()) + ":" + dbgPad(dt.GetMinutes()) + ":" + dbgPad(dt.GetSeconds())
    print "[" + t + "] " + tag + " " + msg
end sub

function dbgPad(n as integer) as string
    if n < 10 then return "0" + n.ToStr()
    return n.ToStr()
end function

' Hide credentials in URLs before logging or showing on screen:
' query tokens, user:pass@ and Xtream-style /user/pass/ paths.
' Прячем учётные данные в ссылках перед логом или показом на экране:
' токены в параметрах, user:pass@ и пути вида /user/pass/ (Xtream).
function maskUrl(url as dynamic) as string
    if url = invalid then return "<invalid>"
    s = url.ToStr()
    rx = CreateObject("roRegex", "(token|key|password|pass|pwd|auth|session|signature|sig|sid|username|user|login|mac)=[^&\s]*", "i")
    s = rx.ReplaceAll(s, "\1=***")
    rx2 = CreateObject("roRegex", "://[^/@\s]+@", "")
    s = rx2.ReplaceAll(s, "://***@")
    rx3 = CreateObject("roRegex", "(/(live|movie|series|timeshift|get\.php|play))/[^/\s]+/[^/\s]+/", "i")
    s = rx3.ReplaceAll(s, "\1/***/***/")
    return maskPathTokens(s)
end function

' Tokens inside the path, e.g. /pl/9zWAiYDaNd/list.m3u8 → /pl/***/list.m3u8:
' a segment of 8+ letters/digits/-/_ that has both letters and digits,
' or any segment of 24+ characters without a dot (a hash).
' Токены внутри пути: сегмент из 8+ букв/цифр/-/_, где есть и буквы, и цифры,
' или любой сегмент из 24+ символов без точки (хеш).
function maskPathTokens(url as string) as string
    p = url.Instr("://")
    if p < 0 then return url
    rest = url.Mid(p + 3)
    sl = rest.Instr("/")
    if sl < 0 then return url
    head = url.Left(p + 3) + rest.Left(sl)
    path = rest.Mid(sl)
    query = ""
    q = path.Instr("?")
    if q >= 0 then
        query = path.Mid(q)
        path = path.Left(q)
    end if
    rxTok = CreateObject("roRegex", "^[A-Za-z0-9_-]{8,}$", "")
    rxLetter = CreateObject("roRegex", "[A-Za-z]", "")
    rxDigit = CreateObject("roRegex", "[0-9]", "")
    parts = path.Split("/")
    out = ""
    for i = 0 to parts.Count() - 1
        seg = parts[i]
        if rxTok.IsMatch(seg) and rxLetter.IsMatch(seg) and rxDigit.IsMatch(seg) then
            seg = "***"
        else if seg.Len() >= 24 and seg.Instr(".") < 0 then
            seg = "***"
        end if
        if i > 0 then out = out + "/"
        out = out + seg
    end for
    return head + out + query
end function

' "ShaGa IPTV Player v1.0.1" from the manifest / из manifest
function appTitleVersion() as string
    ai = CreateObject("roAppInfo")
    return ai.GetTitle() + " v" + ai.GetVersion()
end function
