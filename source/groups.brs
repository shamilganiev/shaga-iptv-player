' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Group visibility: "s" shown, "p" locked with PIN, "h" hidden.
' Adult groups (detected by name) are hidden by default.
' Видимость групп: "s" показана, "p" по PIN-коду, "h" скрыта.
' Группы для взрослых (по названию) по умолчанию скрыты.

' Words that mark an adult group name (checked on normText(name))
' Слова, по которым группа считается «для взрослых» (проверяем normText(названия))
function adultMarkers() as object
    return ["18+", "+18", "(18)", "xxx", "ххх", "adult", "porn", "erotic", "sexy", "взросл", "эрот", "порн", "для взрослых"]
end function

function isAdultGroup(name as string) as boolean
    n = normText(name)
    if n = "" then return false
    for each w in adultMarkers()
        if n.Instr(w) >= 0 then return true
    end for
    return false
end function

function groupModeKey(name as string) as string
    return "g:" + name
end function

' Current mode of a group: saved value or default / Режим группы: сохранённый или по умолчанию
function groupMode(data as object, name as string) as string
    v = data.groupModes[groupModeKey(name)]
    if v = "s" or v = "p" or v = "h" then return v
    if isAdultGroup(name) then return "h"
    return "s"
end function

sub setGroupMode(data as object, name as string, mode as string)
    data.groupModes[groupModeKey(name)] = mode
end sub

' Protection strength: shown 0 < PIN 1 < hidden 2 / Сила защиты: показана < PIN < скрыта
function modeStrength(mode as string) as integer
    if mode = "h" then return 2
    if mode = "p" then return 1
    return 0
end function

' Master code that only resets the PIN: current local time HHMM (24 h), digits
' reversed. 13:45 → "1345" → "5431". The previous minute is accepted too.
' Мастер-код только для сброса PIN-кода: текущее местное время ЧЧММ (24 ч)
' задом наперёд. 13:45 → "1345" → "5431". Предыдущая минута тоже подходит.
function isMasterPin(pin as string) as boolean
    now = CreateObject("roDateTime").AsSeconds()
    for each offset in [0, 60]
        d = CreateObject("roDateTime")
        d.FromSeconds(now - offset)
        d.ToLocalTime()
        clock = twoDigits(d.GetHours()) + twoDigits(d.GetMinutes())
        rev = clock.Mid(3, 1) + clock.Mid(2, 1) + clock.Mid(1, 1) + clock.Mid(0, 1)
        if pin = rev then return true
    end for
    return false
end function

function twoDigits(n as integer) as string
    if n < 10 then return "0" + n.ToStr()
    return n.ToStr()
end function
