' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Dialogs of MainScene (Standard Dialog Framework only: StandardMessageDialog,
' StandardKeyboardDialog — voice keyboard required by certification 4.12).
' Диалоги MainScene (только Standard Dialog Framework: StandardMessageDialog,
' StandardKeyboardDialog — голосовая клавиатура обязательна по пункту 4.12).
'
' m.dlgKind says which dialog is open; m.dlgCtx keeps its context.
' m.dlgKind — какой диалог открыт; m.dlgCtx — его контекст.

' Dark palette for all dialogs / Тёмная палитра для всех диалогов
sub applyDialogPalette()
    pal = CreateObject("roSGNode", "RSGPalette")
    pal.colors = {
        DialogBackgroundColor: "0x1D1A29FF"
        DialogItemColor: "0xECE9F6FF"
        DialogTextColor: "0xECE9F6FF"
        DialogFocusColor: "0xA995FFFF"
        DialogFocusItemColor: "0x14121CFF"
        DialogSecondaryTextColor: "0xA39DB8FF"
        DialogSecondaryItemColor: "0x322D45FF"
        DialogInputFieldColor: "0x14121CFF"
        DialogKeyboardColor: "0x14121CFF"
        DialogFootprintColor: "0x322D45FF"
    }
    m.top.palette = pal
end sub

' ---------- common / общее ----------

sub showDialog(dlg as object, kind as string, ctx as dynamic)
    if m.dlgNode <> invalid then
        m.dlgNode.unobserveField("buttonSelected")
        m.dlgNode.unobserveField("wasClosed")
    end if
    m.dlgNode = dlg
    m.dlgKind = kind
    m.dlgCtx = ctx
    m.dialogOpen = true
    dlg.observeField("buttonSelected", "onDialogButton")
    dlg.observeField("wasClosed", "onDialogClosed")
    m.top.dialog = dlg
    dbg("UI", "dialog " + kind)
end sub

sub closeDialog()
    if m.dlgNode <> invalid then
        m.dlgNode.unobserveField("buttonSelected")
        m.dlgNode.unobserveField("wasClosed")
        m.dlgNode.close = true
    end if
    m.dlgNode = invalid
    m.dlgKind = ""
    m.dlgCtx = invalid
    m.dialogOpen = false
    restoreFocus()
end sub

' Back on a dialog / «Назад» в диалоге
sub onDialogClosed()
    if m.dlgNode = invalid or m.dlgNode.wasClosed <> true then return
    kind = m.dlgKind
    m.dlgNode.unobserveField("buttonSelected")
    m.dlgNode.unobserveField("wasClosed")
    m.dlgNode = invalid
    m.dlgKind = ""
    m.dlgCtx = invalid
    m.dialogOpen = false
    dbg("UI", "dialog " + kind + " closed by Back")
    restoreFocus()
end sub

sub restoreFocus()
    if m.mode = "welcome" or m.mode = "full" then
        m.keyCatcher.setFocus(true)
    else
        focusArea(m.focusArea)
    end if
end sub

' Put the cursor on button i (the current choice) / Курсор на кнопку i (текущий выбор)
sub focusButton(dlg as object, i as integer)
    if i >= 0 and dlg.hasField("buttonFocused") then dlg.buttonFocused = i
end sub

function newMessageDialog(title as string, message as object, buttons as object) as object
    dlg = CreateObject("roSGNode", "StandardMessageDialog")
    dlg.title = title
    dlg.message = message
    dlg.buttons = buttons
    return dlg
end function

sub showInfoDialog(title as string, message as object)
    showDialog(newMessageDialog(title, message, [m.S.close]), "info", invalid)
end sub

' All button presses arrive here / Все нажатия кнопок диалогов приходят сюда
sub onDialogButton()
    if m.dlgNode = invalid then return
    b = m.dlgNode.buttonSelected
    kind = m.dlgKind
    ctx = m.dlgCtx
    dbg("KEY", "dialog " + kind + " button " + b.ToStr())

    if kind = "info" then
        closeDialog()
    else if kind = "exit" then
        if b = 0 then
            closeDialog()
            m.top.exitApp = true
        else
            closeDialog()
        end if
    else if kind = "url" then
        onUrlKeyboardButton(b)
    else if kind = "urlError" then
        ' Back to editing with the same text / Обратно к редактированию с тем же текстом
        openUrlKeyboard(m.kbMode, m.kbId, m.kbText)
    else if kind = "playlists" then
        onPlaylistsButton(b)
    else if kind = "playlistActions" then
        onPlaylistActionButton(b, ctx)
    else if kind = "deleteConfirm" then
        if b = 0 then deletePlaylist(ctx) else closeDialog()
    else if kind = "language" then
        onLanguageButton(b)
    else if kind = "search" then
        txt = keyboardText(m.dlgNode)
        closeDialog()
        if b = 0 then runSearch(txt)
    else if kind.Left(3) = "pin" then
        onPinDialogButton(kind, b, ctx)
    else if kind = "groupMode" then
        onGroupModeButton(b, ctx)
    else
        closeDialog()
    end if
end sub

function keyboardText(dlg as object) as string
    t = dlg.text
    if (t = invalid or t = "") and dlg.textEditBox <> invalid then t = dlg.textEditBox.text
    if t = invalid then return ""
    return t
end function

' ---------- exit / выход ----------

sub openExitDialog()
    showDialog(newMessageDialog(m.S.exitTitle, [" "], [m.S.exit, m.S.stay]), "exit", invalid)
end sub

' ---------- playlist link keyboard / ввод ссылки ----------

' mode: "add" | "edit"; id: playlist id for edit / id плейлиста для изменения
sub openUrlKeyboard(mode as string, id as string, text as string)
    m.kbMode = mode
    m.kbId = id
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    dlg.title = m.S.urlTitle
    if m.kbError <> "" then
        ' Load failed: show why above the hint / Загрузка не удалась: причина над подсказкой
        dlg.message = [m.kbError, m.S.urlMsg]
        m.kbError = ""
    else
        dlg.message = [m.S.urlMsg]
    end if
    dlg.buttons = [m.S.ok, m.S.cancel]
    if dlg.textEditBox <> invalid then dlg.textEditBox.maxTextLength = getAppConfig().maxUrlLength
    dlg.text = text
    showDialog(dlg, "url", invalid)
end sub

sub onUrlKeyboardButton(b as integer)
    raw = keyboardText(m.dlgNode)
    if b <> 0 then
        closeDialog()
        ' Nothing saved yet: back to the welcome screen / Ничего не сохранено: к приветствию
        if m.data.playlists.Count() = 0 and m.mode <> "welcome" then showWelcome()
        return
    end if
    cfg = getAppConfig()
    url = normalizeUrl(raw)
    m.kbText = url
    if url = "" then
        closeDialog()
        return
    end if
    if url.Len() > cfg.maxUrlLength then
        showDialog(newMessageDialog(m.S.urlTitle, [m.S.tooLong + " " + cfg.maxUrlLength.ToStr()], [m.S.ok]), "urlError", invalid)
        return
    end if
    if not isValidUrl(url) then
        showDialog(newMessageDialog(m.S.urlTitle, [m.S.invalidUrl], [m.S.ok]), "urlError", invalid)
        return
    end if

    ' The playlist is saved only after it loads successfully (see onLoaded)
    ' Плейлист сохраняется только после успешной загрузки (см. onLoaded)
    dup = storageFindUrl(m.data, url)
    if dup >= 0 and (m.kbMode <> "edit" or m.data.playlists[dup].id <> m.kbId) then
        ' Same link already saved: open that playlist / Такая ссылка уже есть: открываем её
        closeDialog()
        if m.mode <> "main" then showMain()
        openPlaylist(m.data.playlists[dup].id)
        return
    end if
    if m.kbMode = "edit" then
        if storageFindIndex(m.data, m.kbId) < 0 then
            closeDialog()
            return
        end if
        m.pendingNew = { mode: "edit", id: m.kbId, url: url, name: playlistNameFromUrl(url) }
    else
        if m.data.playlists.Count() >= cfg.maxPlaylists then
            showDialog(newMessageDialog(m.S.playlistsTitle, [m.S.limitReached + " " + cfg.maxPlaylists.ToStr() + ".", m.S.limitHint], [m.S.ok]), "info", invalid)
            return
        end if
        m.pendingNew = { mode: "add", id: "p" + m.data.nextId.ToStr(), url: url, name: playlistNameFromUrl(url) }
    end if
    dbg("UI", "playlist " + m.pendingNew.mode + " try " + m.pendingNew.id + " " + maskUrl(url))
    closeDialog()
    if m.mode <> "main" then showMain()
    startLoad(m.pendingNew.id, "net", false)
end sub

' ---------- playlists / плейлисты ----------

sub openPlaylistsDialog()
    buttons = []
    for each p in m.data.playlists
        mark = ""
        if p.id = m.currentId then mark = "* "
        buttons.push(mark + p.name)
    end for
    buttons.push(m.S.addPlaylist)
    buttons.push(m.S.close)
    dlg = newMessageDialog(m.S.playlistsTitle, [m.S.playlistsMsg], buttons)
    focusButton(dlg, storageFindIndex(m.data, m.currentId))
    showDialog(dlg, "playlists", invalid)
end sub

sub onPlaylistsButton(b as integer)
    n = m.data.playlists.Count()
    if b < n then
        p = m.data.playlists[b]
        shown = maskUrl(p.url)
        if shown.Len() > 160 then shown = shown.Left(157) + "..."
        showDialog(newMessageDialog(p.name, [shown], [m.S.open, m.S.editLink, m.S.delete, m.S.cancel]), "playlistActions", p.id)
    else if b = n then
        if n >= getAppConfig().maxPlaylists then
            showDialog(newMessageDialog(m.S.playlistsTitle, [m.S.limitReached + " " + getAppConfig().maxPlaylists.ToStr() + ".", m.S.limitHint], [m.S.ok]), "info", invalid)
        else
            openUrlKeyboard("add", "", "")
        end if
    else
        closeDialog()
    end if
end sub

sub onPlaylistActionButton(b as integer, id as dynamic)
    i = storageFindIndex(m.data, id)
    if i < 0 then
        closeDialog()
        return
    end if
    p = m.data.playlists[i]
    if b = 0 then
        closeDialog()
        openPlaylist(id)
    else if b = 1 then
        openUrlKeyboard("edit", id, p.url)
    else if b = 2 then
        showDialog(newMessageDialog(m.S.deleteTitle, [p.name, m.S.deleteMsg], [m.S.delete, m.S.cancel]), "deleteConfirm", id)
    else
        closeDialog()
    end if
end sub

sub deletePlaylist(id as string)
    i = storageFindIndex(m.data, id)
    if i < 0 then
        closeDialog()
        return
    end if
    dbg("UI", "playlist deleted " + id)
    m.data.playlists.Delete(i)
    DeleteFile("cachefs:/playlist_" + id + ".json")     ' saved copy / сохранённая копия
    if m.data.lastOkId = id then m.data.lastOkId = ""
    storageSave(m.data)
    wasCurrent = (m.currentId = id or m.loadingId = id)
    closeDialog()
    if m.data.playlists.Count() = 0 then
        m.loadingId = ""
        clearChannels()
        showWelcome()
    else if wasCurrent then
        m.loadingId = ""
        clearChannels()
        buildCategories(1)
        focusArea("cat")
        openPlaylist(startupPlaylistId())
    end if
end sub

' ---------- language / язык ----------

sub openLanguageDialog()
    codes = ["auto", "en", "es", "ru"]
    names = [m.S.langAuto, "English", "Español", "Русский"]
    buttons = []
    cur = 0
    for i = 0 to 3
        if codes[i] = m.data.lang then
            cur = i
            buttons.push("* " + names[i])
        else
            buttons.push("   " + names[i])
        end if
    end for
    dlg = newMessageDialog(m.S.langTitle, [" "], buttons)
    focusButton(dlg, cur)
    showDialog(dlg, "language", invalid)
end sub

sub onLanguageButton(b as integer)
    codes = ["auto", "en", "es", "ru"]
    if b >= 0 and b < codes.Count() then
        m.data.lang = codes[b]
        storageSave(m.data)
        applyLanguage()
        dbg("UI", "language " + m.data.lang + " -> " + m.lang)
        focusIdx = m.catList.itemFocused
        buildCategories(focusIdx)
        restorePlaylistLabel()
        if m.curCat >= 0 and m.curCat < m.cats.Count() then showCategory(m.curCat)
    end if
    closeDialog()
end sub

' ---------- search keyboard / клавиатура поиска ----------

sub openSearchKeyboard()
    if m.channels = invalid then return
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    dlg.title = m.S.searchTitle
    dlg.message = [m.S.searchMsg]
    dlg.buttons = [m.S.ok, m.S.cancel]
    dlg.text = m.searchQuery
    showDialog(dlg, "search", invalid)
end sub
