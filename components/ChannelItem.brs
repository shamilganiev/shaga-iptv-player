' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' ChannelItem: row renderer for the MarkupList of channels (and settings rows).
' The scene updates the programme fields of the content node once a minute,
' so the row also observes those fields, not only a new content node.
' ChannelItem: отрисовка строки MarkupList каналов (и строк настроек).
' Сцена раз в минуту обновляет поля передачи у узла содержимого, поэтому строка
' следит и за этими полями, а не только за сменой узла.
'
' Content fields / Поля содержимого: shortDescriptionLine1 = number / номер,
' HDPosterUrl = logo, title, shortDescriptionLine2 = programme / передача,
' epgS / epgE = programme start / end, UTC seconds (0 = none) / начало и конец передачи

sub init()
    m.numLabel = m.top.findNode("num")
    m.logo = m.top.findNode("logo")
    m.titleLabel = m.top.findNode("title")
    m.progLabel = m.top.findNode("prog")
    m.progBg = m.top.findNode("progBg")
    m.progBar = m.top.findNode("progBar")
    m.isDark = false
    m.watched = invalid
end sub

sub onContent()
    c = m.top.itemContent
    if m.watched <> invalid then m.watched.unobserveFieldScoped("shortDescriptionLine2")
    m.watched = c
    if c = invalid then return
    c.observeFieldScoped("shortDescriptionLine2", "onProgramme")
    m.numLabel.text = c.shortDescriptionLine1
    m.titleLabel.text = c.title
    m.logo.uri = c.HDPosterUrl
    m.logo.visible = (c.HDPosterUrl <> "")
    onProgramme()
end sub

sub onProgramme()
    c = m.top.itemContent
    if c = invalid then return
    m.progLabel.text = c.shortDescriptionLine2
    s = 0
    e = 0
    if c.hasField("epgS") then
        s = c.epgS
        e = c.epgE
    end if
    if e > s and s > 0 then
        now = CreateObject("roDateTime").AsSeconds()
        pct = (now - s) / (e - s)
        if pct < 0 then pct = 0
        if pct > 1 then pct = 1
        m.progBar.width = Int(300 * pct)
        m.progBg.visible = true
        m.progBar.visible = true
    else
        m.progBg.visible = false
        m.progBar.visible = false
    end if
end sub

' Dark text on the focus bar, light text elsewhere / Тёмный текст на рамке фокуса
sub onFocus()
    dark = (m.top.listHasFocus = true and m.top.focusPercent > 0.5)
    if dark = m.isDark then return
    m.isDark = dark
    if dark then
        m.titleLabel.color = "0x14121CFF"
        m.progLabel.color = "0x2C2450FF"
        m.numLabel.color = "0x2C2450FF"
        m.progBg.color = "0x7C6BC9FF"
        m.progBar.color = "0x14121CFF"
    else
        m.titleLabel.color = "0xECE9F6FF"
        m.progLabel.color = "0xA39DB8FF"
        m.numLabel.color = "0xA39DB8FF"
        m.progBg.color = "0x322D45FF"
        m.progBar.color = "0xA995FFFF"
    end if
end sub
