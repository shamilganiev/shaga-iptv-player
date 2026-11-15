' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' 1.4.1: "where it plays". The row of what is playing is marked in every column:
' the group it was started from, the channel (or the search result) and the programme.
' The row components (ChannelItem, TextItem) draw the mark: grey in a column without
' the focus, soft violet in the focused column while the cursor is on another row.
' Leaving the channel column to the left returns its cursor to the playing row.
' 1.4.1: «где играет». Строка того, что играет, отмечена во всех колонках: группа, из
' которой запустили, канал (или результат поиска) и передача. Отметку рисуют строки
' (ChannelItem, TextItem): серая в колонке без фокуса, мягкая фиолетовая в колонке
' с фокусом, пока курсор на другой строке. При уходе из колонки каналов влево её
' курсор возвращается на играющую строку.

sub markInit()
    m.markNodes = { cat: invalid, chan: invalid, prog: invalid }
    m.playCatKey = ""        ' category the playback was started from / категория, из которой запустили
    m.fullFrom = "chan"      ' column the full screen was opened from / колонка, из которой открыли полный экран
end sub

' Stable key of a category row ("" for menu rows) / Постоянный ключ строки категории
function catKeyAt(i as integer) as string
    if i < 0 or i >= m.cats.Count() then return ""
    c = m.cats[i]
    if c.kind = "action" then return ""
    if c.kind = "group" then return "g:" + c.gi.ToStr()
    return c.kind
end function

' Is this programme the one that plays now (archive: same start; live: on air)?
' Эта передача сейчас играет (архив: то же начало; эфир: идёт сейчас)?
function isPlayingProg(it as object, now as integer) as boolean
    if m.arch <> invalid then return (m.arch.s = it.s)
    return (it.s <= now and now < it.e)
end function

' Row of the channel column that plays, -1 if it is not in the list
' Играющая строка колонки каналов, -1 — если её нет в списке
function playingRow() as integer
    if m.playingIx < 0 or m.curIdx = invalid or m.view <> "channels" then return -1
    if m.findShown and m.findItems <> invalid then
        now = CreateObject("roDateTime").AsSeconds()
        for p = 0 to m.findItems.Count() - 1
            if m.findIdx[p] = m.playingIx then
                if isPlayingProg(m.findItems[p], now) then return p
            end if
        end for
        return -1
    end if
    return indexOfChannel(m.playingIx)
end function

sub setMark(slot as string, node as dynamic)
    old = m.markNodes[slot]
    if old <> invalid then
        if node <> invalid and old.isSameNode(node) then
            if old.playing <> true then old.playing = true
            return
        end if
        if old.hasField("playing") then old.playing = false
    end if
    if node <> invalid then
        if node.hasField("playing") then
            node.playing = true
        else
            node.addFields({ playing: true })
        end if
    end if
    m.markNodes[slot] = node
end sub

' Put the marks on the rows of what is playing (cheap: three rows at most change)
' Расставить отметки на строках того, что играет (меняется не больше трёх строк)
sub markPlaying()
    on = (m.playingIx >= 0 and m.channels <> invalid)

    ' groups / группы
    node = invalid
    if on and m.catSub = "" and m.catList.content <> invalid then
        found = -1
        for i = 0 to m.cats.Count() - 1
            if m.playCatKey <> "" and catKeyAt(i) = m.playCatKey then
                found = i
                exit for
            end if
        end for
        if found < 0 then found = groupCat(m.channels[m.playingIx].gi)
        if found >= 0 and found < m.catList.content.getChildCount() then node = m.catList.content.getChild(found)
    end if
    setMark("cat", node)

    ' channels or search results / каналы или результаты поиска
    node = invalid
    if on and m.view = "channels" and m.chanList.content <> invalid then
        p = playingRow()
        if p >= 0 and p < m.chanList.content.getChildCount() then node = m.chanList.content.getChild(p)
    end if
    setMark("chan", node)

    ' programme list / список передач
    node = invalid
    if on and m.progIx = m.playingIx and m.progList.visible and m.progList.content <> invalid then
        now = CreateObject("roDateTime").AsSeconds()
        n = m.progList.content.getChildCount()
        for i = 0 to m.progItems.Count() - 1
            if isPlayingProg(m.progItems[i], now) then
                if i < n then node = m.progList.content.getChild(i)
                exit for
            end if
        end for
    end if
    setMark("prog", node)
end sub

' The cursor of the channel column goes back to the playing row (when the column is left)
' Курсор колонки каналов возвращается на играющую строку (при уходе из колонки)
sub returnToPlaying()
    if m.view <> "channels" then return
    p = playingRow()
    if p >= 0 and p <> m.chanList.itemFocused then
        m.chanList.jumpToItem = p
        dbg("UI", "channel list returned to the playing row")
    end if
end sub
