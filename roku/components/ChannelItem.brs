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
    m.progScroll = m.top.findNode("progScroll")
    m.progBg = m.top.findNode("progBg")
    m.progBar = m.top.findNode("progBar")
    m.findLabel = m.top.findNode("findLine")
    m.playBg = m.top.findNode("playBg")
    m.playing = false
    m.isFind = false
    m.findCat = 0
    m.isDark = false
    m.watched = invalid
end sub

sub onContent()
    c = m.top.itemContent
    if m.watched <> invalid then
        m.watched.unobserveFieldScoped("shortDescriptionLine2")
        m.watched.unobserveFieldScoped("epgT")
        m.watched.unobserveFieldScoped("playing")
    end if
    m.watched = c
    if c = invalid then return
    c.observeFieldScoped("shortDescriptionLine2", "onProgramme")
    ' 1.3.7: the scene changes epgT every minute, so the bar moves (it was drawn once before)
    ' 1.3.7: сцена меняет epgT каждую минуту — полоска двигается (раньше рисовалась один раз)
    if c.hasField("epgT") then c.observeFieldScoped("epgT", "onProgramme")
    ' 1.4.1: the scene sets "playing" on the row of what is playing / сцена ставит "playing" у играющей строки
    if c.hasField("playing") then c.observeFieldScoped("playing", "onPlaying")
    m.numLabel.text = c.shortDescriptionLine1
    m.titleLabel.text = c.title
    m.logo.uri = c.HDPosterUrl
    m.logo.visible = (c.HDPosterUrl <> "")
    ' 1.4.0: search result row (taller): channel, programme, date line; no progress bar
    ' 1.4.0: строка результата поиска (выше): канал, передача, строка даты; без полоски
    m.isFind = (c.hasField("findLine") and c.findLine <> "")
    m.findLabel.visible = m.isFind
    if m.isFind then
        m.findLabel.text = c.findLine
        m.findCat = c.findCat
        m.numLabel.height = 124
        m.logo.translation = [98, 44]
        m.playBg.height = 120
    else
        m.numLabel.height = 84
        m.logo.translation = [98, 24]
        m.playBg.height = 80
    end if
    paintFind()
    onPlaying()
    onProgramme()
end sub

' 1.4.1: background of the playing row: grey when the list has no focus, soft violet when
' it has the focus and the cursor is on another row (the focus bar itself covers its row).
' 1.4.1: фон играющей строки: серый, когда список без фокуса, мягкий фиолетовый, когда
' список в фокусе, а курсор на другой строке (под самим курсором фон не нужен).
sub onPlaying()
    c = m.top.itemContent
    m.playing = false
    if c <> invalid and c.hasField("playing") then m.playing = (c.playing = true)
    paintPlaying()
end sub

sub paintPlaying()
    m.playBg.visible = (m.playing and not m.isDark)
    if m.top.listHasFocus = true then
        m.playBg.color = "0x4B4285FF"
    else
        m.playBg.color = "0x3C3A48FF"
    end if
end sub

sub onProgramme()
    c = m.top.itemContent
    if c = invalid then return
    m.progLabel.text = c.shortDescriptionLine2
    m.progScroll.text = c.shortDescriptionLine2
    s = 0
    e = 0
    if c.hasField("epgS") then
        s = c.epgS
        e = c.epgE
    end if
    if m.isFind then
        m.progBg.visible = false
        m.progBar.visible = false
        return
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
    if dark = m.isDark then
        paintPlaying()
        return
    end if
    m.isDark = dark
    paintPlaying()
    paintFind()
    ' Focused row: scrolling programme line instead of the cut one / Выделенная строка: бегущая строка вместо обрезанной
    m.progScroll.visible = dark
    m.progLabel.visible = not dark
    if dark then
        m.titleLabel.color = "0x14121CFF"
        m.numLabel.color = "0x2C2450FF"
        m.progBg.color = "0x6E62A8FF"
        m.progBar.color = "0x14121CFF"
    else
        m.titleLabel.color = "0xECE9F6FF"
        m.progLabel.color = "0xA39DB8FF"
        m.numLabel.color = "0xA39DB8FF"
        m.progBg.color = "0x5A5470FF"
        m.progBar.color = "0xF5B83DFF"
    end if
end sub

' Date line colour: past grey-blue, on air amber, future green (darker on the focus bar)
' Цвет строки даты: прошедшие серо-голубой, в эфире янтарный, будущие зелёный (на рамке фокуса темнее)
sub paintFind()
    if not m.isFind then return
    if m.isDark then
        cols = ["0x3A4668FF", "0x7A4A00FF", "0x1F6B2AFF"]
    else
        cols = ["0x8FA3C8FF", "0xF5B83DFF", "0x8FD694FF"]
    end if
    c = m.findCat
    if c < 0 or c > 2 then c = 0
    m.findLabel.color = cols[c]
end sub
