' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' Entry point: screen, global fields, memory monitor, deep links, event loop.
' Точка входа: экран, глобальные поля, монитор памяти, deep link, цикл событий.

sub Main(args as dynamic)
    dbg("APP", "===== " + appTitleVersion() + " =====")
    dev = CreateObject("roDeviceInfo")
    dbg("APP", "model=" + dev.GetModel() + " os=" + osVersionText(dev) + " locale=" + dev.GetCurrentLocale())

    screen = CreateObject("roSGScreen")
    m.port = CreateObject("roMessagePort")
    screen.setMessagePort(m.port)

    ' Global fields must exist BEFORE CreateScene: components subscribe to them in init().
    ' Глобальные поля создаём ДО CreateScene: компоненты подписываются на них в init().
    g = screen.getGlobalNode()
    g.addFields({ memWarning: 0 })

    ' Memory monitor (required by certification since Oct 1, 2026)
    ' Монитор памяти (обязателен для сертификации с 1 окт. 2026)
    setupMemoryMonitor()

    ' Deep link / input events while running / Deep link во время работы
    m.input = CreateObject("roInput")
    m.input.setMessagePort(m.port)

    scene = screen.CreateScene("MainScene")
    scene.observeField("exitApp", m.port)
    ' Launch deep link goes to the scene before show() / Deep link запуска передаём до show()
    dl = deepLinkFromArgs(args)
    if dl <> invalid then
        dbg("DL", "launch contentId=" + dl.contentId + " mediaType=" + dl.mediaType)
        scene.deepLink = dl
    end if
    screen.show()

    while true
        msg = wait(0, m.port)
        msgType = type(msg)
        if msgType = "roSGScreenEvent" then
            if msg.isScreenClosed() then exit while
        else if msgType = "roSGNodeEvent" then
            if msg.getField() = "exitApp" and msg.getData() = true then
                dbg("APP", "exit requested")
                exit while
            end if
        else if msgType = "roInputEvent" then
            if msg.isInput() then
                dl = deepLinkFromArgs(msg.getInfo())
                if dl <> invalid then
                    dbg("DL", "runtime contentId=" + dl.contentId + " mediaType=" + dl.mediaType)
                    scene.deepLink = dl
                end if
            end if
        else if msgType = "roAppMemoryNotificationEvent" then
            info = msg.getInfo()
            pct = 0
            if info <> invalid and info.MemoryUsagePercent <> invalid then pct = info.MemoryUsagePercent
            dbg("MEM", "memory usage " + pct.ToStr() + "%")
            g.memWarning = pct
        else if msgType = "roDeviceInfoEvent" then
            info = msg.getInfo()
            if info <> invalid and info.generalMemoryLevel <> invalid then
                dbg("MEM", "general memory level: " + info.generalMemoryLevel.ToStr())
            end if
        end if
    end while
    screen.close()
end sub

' Register for memory warnings; methods are checked first (older OS lacks some).
' Подписка на предупреждения о памяти; методы проверяем — на старых OS их может не быть.
sub setupMemoryMonitor()
    m.memMon = CreateObject("roAppMemoryMonitor")
    if m.memMon = invalid then
        dbg("MEM", "roAppMemoryMonitor not available")
        return
    end if
    m.memMon.setMessagePort(m.port)
    enabled = false
    if FindMemberFunction(m.memMon, "EnableMemoryWarningEvent") <> invalid then
        enabled = m.memMon.EnableMemoryWarningEvent(true)
    end if
    if FindMemberFunction(m.memMon, "GetChannelMemoryLimit") <> invalid then
        lim = m.memMon.GetChannelMemoryLimit()
        if lim <> invalid and lim.maxForegroundMemory <> invalid then
            dbg("MEM", "limit foreground=" + lim.maxForegroundMemory.ToStr())
        end if
    end if
    if not enabled then
        ' Fallback for devices without memory warnings / Запасной вариант для старых устройств
        m.devInfoMem = CreateObject("roDeviceInfo")
        m.devInfoMem.setMessagePort(m.port)
        if FindMemberFunction(m.devInfoMem, "EnableLowGeneralMemoryEvent") <> invalid then
            m.devInfoMem.EnableLowGeneralMemoryEvent(true)
        end if
    end if
    dbg("MEM", "memory warnings enabled=" + enabled.ToStr())
end sub

' {contentId, mediaType} or invalid / {contentId, mediaType} или invalid
function deepLinkFromArgs(args as dynamic) as dynamic
    if args = invalid or type(args) <> "roAssociativeArray" then return invalid
    cid = args.contentId
    if cid = invalid or cid.ToStr() = "" then return invalid
    mt = args.mediaType
    if mt = invalid then mt = ""
    return { contentId: cid.ToStr(), mediaType: mt.ToStr() }
end function

function osVersionText(dev as object) as string
    v = dev.GetOSVersion()
    if v = invalid then return "?"
    return v.major + "." + v.minor + "." + v.revision + " build " + v.build
end function
