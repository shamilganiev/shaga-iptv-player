' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' TextItem (1.4.1): text row of the group list and of the programme list.
' The scene sets the content field "playing" on the row of what is playing.
' TextItem (1.4.1): текстовая строка списка групп и списка передач.
' Сцена ставит поле содержимого "playing" у строки того, что сейчас играет.

sub init()
    m.label = m.top.findNode("text")
    m.playBg = m.top.findNode("playBg")
    m.isDark = false
    m.playing = false
    m.watched = invalid
end sub

sub onSize()
    w = m.top.width
    h = m.top.height
    if w <= 0 or h <= 0 then return
    m.label.width = w - 36
    m.label.height = h
    m.playBg.width = w
    m.playBg.height = h - 4
    ' Tall rows (groups) use the medium font, short rows (programmes) the smallest one
    ' Высокие строки (группы) — средний шрифт, низкие (передачи) — самый мелкий
    if h >= 54 then
        m.label.font = "font:MediumSystemFont"
    else
        m.label.font = "font:SmallestSystemFont"
    end if
end sub

sub onContent()
    c = m.top.itemContent
    if m.watched <> invalid then m.watched.unobserveFieldScoped("playing")
    m.watched = c
    if c = invalid then return
    m.label.text = c.title
    if c.hasField("playing") then c.observeFieldScoped("playing", "onPlaying")
    onPlaying()
end sub

sub onPlaying()
    c = m.top.itemContent
    m.playing = false
    if c <> invalid and c.hasField("playing") then m.playing = (c.playing = true)
    paint()
end sub

sub onFocus()
    m.isDark = (m.top.listHasFocus = true and m.top.focusPercent > 0.5)
    paint()
end sub

' Dark text on the focus bar; background of the playing row / Тёмный текст на рамке фокуса; фон играющей строки
sub paint()
    if m.isDark then
        m.label.color = "0x14121CFF"
    else
        m.label.color = "0xECE9F6FF"
    end if
    m.playBg.visible = (m.playing and not m.isDark)
    if m.top.listHasFocus = true then
        m.playBg.color = "0x4B4285FF"     ' soft violet: the list has the focus / мягкий фиолетовый: список в фокусе
    else
        m.playBg.color = "0x3C3A48FF"     ' grey: the list is not active / серый: список не активен
    end if
end sub
