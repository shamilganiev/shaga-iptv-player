' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Settings (shown in the channel column) and the PIN code.
' Rows: PIN code, Channel switching, every group of the playlist, Close the app.
' OK on a group opens a small window: shown / locked with PIN / hidden.
' The PIN is asked only to WEAKEN protection (hidden -> anything, PIN -> shown),
' once per visit to Settings. A PIN entered here opens nothing for viewing.
' Настройки (в колонке каналов) и PIN-код.
' Строки: PIN-код, Переключение каналов, все группы плейлиста, Закрыть приложение.
' OK на группе открывает окно: показана / по PIN-коду / скрыта.
' PIN спрашивается только для ОСЛАБЛЕНИЯ защиты (скрыта -> любое, PIN -> показана),
' один раз за заход в Настройки. PIN, введённый здесь, ничего не открывает для просмотра.
'
' Forgotten PIN: the master code (isMasterPin in groups.brs) only resets it.
' Забытый PIN: мастер-код (isMasterPin в groups.brs) только сбрасывает его.

sub requestSettings()
    enterSettings(0)
end sub

sub enterSettings(focusRow as integer)
    m.view = "settings"
    m.settingsPinOk = false
    showMessage("", false)
    clearPreview()
    buildSettingsRows(focusRow)
    updateChanHeader()
    focusArea("chan")
    dbg("UI", "settings opened")
end sub

' Back to channels; group modes may have changed, so rebuild categories
' Обратно к каналам; режимы групп могли измениться — перестраиваем категории
sub leaveSettings(rebuild as boolean)
    if m.view <> "settings" then return
    m.view = "channels"
    m.settingsPinOk = false
    m.settingsRows = []
    if not rebuild or m.channels = invalid then
        m.chanList.content = invalid
        m.curIdx = []
        updateChanHeader()
        return
    end if
    cur = m.curCat
    buildCategories(m.catList.itemFocused)
    if cur >= 0 and cur < m.cats.Count() and m.cats[cur].kind <> "action" then
        showCategory(cur)
    else
        m.curCat = -1
        i = allChannelsCat()
        if i >= 0 then showCategory(i)
    end if
    dbg("UI", "settings closed")
end sub

sub buildSettingsRows(focusRow as integer)
    m.settingsRows = [{ kind: "pin" }, { kind: "zap" }, { kind: "epg" }]
    pinText = m.S.pinNotSet
    if m.data.pin <> "" then pinText = m.S.pinIsSet
    zapText = m.S.zapList
    if m.data.zapMode = "classic" then zapText = m.S.zapClassic
    items = [
        { title: m.S.pinRow, HDPosterUrl: "", shortDescriptionLine1: "", shortDescriptionLine2: pinText }
        { title: m.S.zapRow, HDPosterUrl: "", shortDescriptionLine1: "", shortDescriptionLine2: zapText }
        { title: m.S.epgRow, HDPosterUrl: "", shortDescriptionLine1: "", shortDescriptionLine2: epgStatusText() }
    ]
    if m.groups <> invalid then
        for gi = 0 to m.groups.Count() - 1
            m.settingsRows.push({ kind: "group", gi: gi })
            items.push({ title: groupTitle(gi), HDPosterUrl: "", shortDescriptionLine1: "", shortDescriptionLine2: modeText(groupMode(m.data, m.groups[gi].name)) })
        end for
    end if
    m.settingsRows.push({ kind: "about" })
    items.push({ title: m.S.aboutRow, HDPosterUrl: "", shortDescriptionLine1: "", shortDescriptionLine2: appTitleVersion() })
    m.settingsRows.push({ kind: "close" })
    items.push({ title: m.S.closeApp, HDPosterUrl: "", shortDescriptionLine1: "", shortDescriptionLine2: "" })
    root = CreateObject("roSGNode", "ContentNode")
    root.Update({ children: items }, true)
    m.chanList.content = root
    if focusRow < 0 or focusRow >= items.Count() then focusRow = 0
    m.chanList.jumpToItem = focusRow
end sub

function groupTitle(gi as integer) as string
    g = m.groups[gi]
    nm = g.name
    if nm = "" then nm = m.S.ungrouped
    return nm + "  (" + g.idx.Count().ToStr() + ")"
end function

function modeText(mode as string) as string
    if mode = "p" then return m.S.statusPin
    if mode = "h" then return m.S.statusHidden
    return m.S.statusShown
end function

sub onSettingsRowSelected(p as integer)
    if p < 0 or p >= m.settingsRows.Count() then return
    row = m.settingsRows[p]
    if row.kind = "pin" then
        if m.data.pin = "" then
            startPinCreate({ action: "none", row: p })
        else
            showDialog(newMessageDialog(m.S.pinRow, [" "], [m.S.pinChange, m.S.pinRemove, m.S.cancel]), "pinMenu", invalid)
        end if
    else if row.kind = "zap" then
        if m.data.zapMode = "classic" then m.data.zapMode = "list" else m.data.zapMode = "classic"
        storageSave(m.data)
        dbg("UI", "zap mode=" + m.data.zapMode)
        buildSettingsRows(p)
    else if row.kind = "group" then
        openGroupModeDialog(row.gi, p)
    else if row.kind = "epg" then
        ' Update the TV guide now / Обновить программу сейчас
        if m.channels <> invalid then epgRequestLoad(true)
    else if row.kind = "about" then
        cfg = getAppConfig()
        showInfoDialog(m.S.aboutRow, [m.S.aboutText.Replace("{v}", appTitleVersion()), m.S.privacyLabel + " " + cfg.privacyUrl, m.S.supportLabel + " " + cfg.supportContact])
    else if row.kind = "close" then
        openExitDialog()
    end if
end sub

' Small window with the three modes; the current one is marked with *
' Маленькое окно с тремя режимами; текущий отмечен *
sub openGroupModeDialog(gi as integer, row as integer)
    cur = groupMode(m.data, m.groups[gi].name)
    buttons = []
    for each md in ["s", "p", "h"]
        mark = "   "
        if md = cur then mark = "* "
        buttons.push(mark + modeText(md))
    end for
    buttons.push(m.S.cancel)
    dlg = newMessageDialog(groupTitle(gi), [m.S.groupModeMsg], buttons)
    curIdx = 0
    if cur = "p" then curIdx = 1
    if cur = "h" then curIdx = 2
    focusButton(dlg, curIdx)
    showDialog(dlg, "groupMode", { gi: gi, row: row })
end sub

' Called from onDialogButton for kind "groupMode" / Вызывается для окна "groupMode"
sub onGroupModeButton(b as integer, ctx as object)
    modes = ["s", "p", "h"]
    if b < 0 or b > 2 then
        closeDialog()
        return
    end if
    gi = ctx.gi
    newMode = modes[b]
    cur = groupMode(m.data, m.groups[gi].name)
    if newMode = cur then
        closeDialog()
        return
    end if
    after = { action: "setGroup", gi: gi, mode: newMode, row: ctx.row }
    if modeStrength(newMode) > modeStrength(cur) then
        ' Stronger: no PIN, but "PIN" mode needs a PIN to exist
        ' Усиление: PIN не нужен, но для режима «по PIN» он должен быть задан
        if newMode = "p" and m.data.pin = "" then
            startPinCreate(after)
        else
            closeDialog()
            applyGroupMode(gi, newMode, ctx.row)
        end if
    else
        ' Weaker: PIN once per visit; no PIN yet -> create it first
        ' Ослабление: PIN один раз за заход; если PIN нет — сначала создать
        if m.settingsPinOk then
            closeDialog()
            applyGroupMode(gi, newMode, ctx.row)
        else if m.data.pin = "" then
            startPinCreate(after)
        else
            openPinCheck(after)
        end if
    end if
end sub

sub applyGroupMode(gi as integer, mode as string, row as integer)
    name = m.groups[gi].name
    setGroupMode(m.data, name, mode)
    storageSave(m.data)
    dbg("UI", "group '" + name + "' mode=" + mode)
    if m.openGroup <> invalid and mode <> "p" then
        if m.openGroup = name then m.openGroup = invalid
    end if
    if m.view = "settings" then buildSettingsRows(row)
end sub

' ---------- PIN dialogs / окна PIN-кода ----------

function newPinDialog(title as string, msg as string) as object
    dlg = CreateObject("roSGNode", "StandardPinPadDialog")
    dlg.title = title
    dlg.message = [msg]
    dlg.buttons = [m.S.ok, m.S.cancel]
    if dlg.pinPad <> invalid then dlg.pinPad.secureMode = true
    return dlg
end function

' after = { action: "group", gi }                 — open a PIN group for viewing
'       | { action: "setGroup", gi, mode, row }   — weaken protection in Settings
'       | { action: "changePin" } | { action: "removePin" }
sub openPinCheck(after as object)
    showDialog(newPinDialog(m.S.pinEnterTitle, m.S.pinEnterMsg), "pinCheck", after)
end sub

sub startPinCreate(after as object)
    m.pinAfter = after
    m.pinFirst = ""
    showDialog(newPinDialog(m.S.pinCreateTitle, m.S.pinCreateMsg), "pinNew1", invalid)
end sub

sub onPinDialogButton(kind as string, b as integer, ctx as dynamic)
    pin = ""
    if m.dlgNode <> invalid and m.dlgNode.pin <> invalid then pin = m.dlgNode.pin

    if kind = "pinCheck" then
        if b <> 0 then
            closeDialog()
        else if m.data.pin <> "" and pin = m.data.pin then
            dbg("UI", "PIN accepted for " + ctx.action)
            closeDialog()
            doAfterPin(ctx)
        else if isMasterPin(pin) then
            dbg("UI", "master code accepted")
            showDialog(newMessageDialog(m.S.pinResetTitle, [m.S.pinResetMsg], [m.S.pinReset, m.S.cancel]), "pinResetConfirm", invalid)
        else
            dbg("UI", "PIN wrong")
            showDialog(newMessageDialog(m.S.pinEnterTitle, [m.S.pinWrong], [m.S.ok]), "info", invalid)
        end if

    else if kind = "pinResetConfirm" then
        if b = 0 then removePin() else closeDialog()

    else if kind = "pinNew1" then
        if b <> 0 then
            closeDialog()
        else if pin.Len() <> 4 then
            showDialog(newMessageDialog(m.S.pinCreateTitle, [m.S.pinShort], [m.S.ok]), "pinNewRetry", invalid)
        else
            m.pinFirst = pin
            showDialog(newPinDialog(m.S.pinCreateTitle, m.S.pinRepeatMsg), "pinNew2", invalid)
        end if

    else if kind = "pinNew2" then
        if b <> 0 then
            m.pinFirst = ""
            closeDialog()
        else if pin <> m.pinFirst then
            m.pinFirst = ""
            showDialog(newMessageDialog(m.S.pinCreateTitle, [m.S.pinMismatch], [m.S.ok]), "pinNewRetry", invalid)
        else
            ' New PIN: opens nothing for viewing; counts as entered for this Settings visit
            ' Новый PIN: для просмотра ничего не открывает; в этом заходе в Настройки считается введённым
            m.data.pin = pin
            m.pinFirst = ""
            m.settingsPinOk = true
            storageSave(m.data)
            dbg("UI", "PIN created")
            closeDialog()
            after = m.pinAfter
            m.pinAfter = invalid
            if after <> invalid and after.action = "setGroup" then
                applyGroupMode(after.gi, after.mode, after.row)
            else if m.view = "settings" then
                buildSettingsRows(0)
            end if
        end if

    else if kind = "pinNewRetry" then
        startPinCreate(m.pinAfter)

    else if kind = "pinMenu" then
        ' Changing or removing the PIN needs the current PIN (or the master code)
        ' Изменение или удаление PIN-кода требует текущий PIN (или мастер-код)
        if b = 0 then
            openPinCheck({ action: "changePin" })
        else if b = 1 then
            openPinCheck({ action: "removePin" })
        else
            closeDialog()
        end if
    else
        closeDialog()
    end if
end sub

sub doAfterPin(after as dynamic)
    if after = invalid then return
    if after.action = "group" then
        ' Viewing: open only this group / Просмотр: открываем только эту группу
        openGroupWith(after.gi)
        buildCategories(-1)
        i = groupCat(after.gi)
        if i >= 0 then
            m.catList.jumpToItem = i
            showCategory(i)
            focusArea("chan")
        end if
        return
    end if
    ' Settings actions / Действия в Настройках
    m.settingsPinOk = true
    if after.action = "setGroup" then
        applyGroupMode(after.gi, after.mode, after.row)
    else if after.action = "changePin" then
        startPinCreate({ action: "none" })
    else if after.action = "removePin" then
        removePin()
    end if
end sub

' Remove or reset the PIN: groups locked with it become hidden
' Удаление или сброс PIN-кода: группы под ним становятся скрытыми
sub removePin()
    m.data.pin = ""
    m.openGroup = invalid
    ' All saved "PIN" modes (any playlist) become hidden / Все сохранённые «по PIN» → скрыта
    for each key in m.data.groupModes
        if m.data.groupModes[key] = "p" then m.data.groupModes[key] = "h"
    end for
    storageSave(m.data)
    dbg("UI", "PIN removed")
    if m.view = "settings" then buildSettingsRows(0)
    showDialog(newMessageDialog(m.S.pinRow, [m.S.pinRemovedMsg], [m.S.ok]), "info", invalid)
end sub
