' ShaGa IPTV Player — free player for your own M3U/M3U8 playlists
' ShaGa IPTV Player — бесплатный плеер для собственных плейлистов M3U/M3U8
'
' App settings / Настройки приложения

function getAppConfig() as object
    return {
        ' Detailed log in the debug console (telnet <roku-ip> 8085). Set false for the store build.
        ' Подробный лог в отладочной консоли (telnet <ip-roku> 8085). Для магазина — false.
        debugLog: true

        ' Maximum number of saved playlists (registry limit is about 16 KB per app)
        ' Максимум сохранённых плейлистов (лимит реестра — около 16 КБ на приложение)
        maxPlaylists: 5

        ' Maximum playlist link length / Максимальная длина ссылки на плейлист
        maxUrlLength: 2048

        ' Channel start timeout, seconds / Тайм-аут старта канала, секунды
        startTimeoutSec: 45

        ' Show our buffering spinner only after this pause, ms / Значок загрузки — только после паузы, мс
        spinnerDelayMs: 700

        ' Entered PIN for a group expires after this many seconds without viewing
        ' Введённый PIN группы сбрасывается через столько секунд без просмотра
        pinIdleSec: 600

        ' TV guide: whole load timeout (s), refresh period (s), chunk size (bytes)
        ' Телепрограмма: тайм-аут всей загрузки (с), период обновления (с), размер куска (байт)
        epgTimeoutSec: 300
        epgRefreshSec: 21600
        epgChunkBytes: 4194304

        ' About / privacy (shown in Settings) / О приложении и политика (в Настройках)
        privacyUrl: "https://shamilganiev.github.io/shaga-player/privacy"
        supportContact: "https://github.com/shamilganiev"

        ' Playlist download timeout, seconds / Тайм-аут загрузки плейлиста, секунды
        loadTimeoutSec: 30

        ' User-Agent for downloading playlists. Some providers send a reduced playlist
        ' to Roku's default User-Agent.
        ' User-Agent для загрузки плейлистов. Некоторые провайдеры отдают Roku
        ' урезанный плейлист.
        playlistUserAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
end function
