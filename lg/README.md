# ShaGa IPTV Player — LG webOS

Универсальный плеер M3U-плейлистов. Сам каналов не содержит.

## Установка на телевизор
    ares-package .              # из папки проекта; появится com.shaga.player_1.3.2_all.ipk
    ares-install com.shaga.player_1.3.2_all.ipk
    ares-launch com.shaga.player

## Отладка
    ares-inspect com.shaga.player --open   # DevTools Chrome для приложения на ТВ

## Проверка в браузере на компьютере
Открыть index.html через локальный сервер (python -m http.server).
Esc = Back, PageUp/PageDown = CH+/CH−, R = красная (меню), Y = жёлтая, B = синяя.

## Структура
    appinfo.json      манифест webOS
    index.html        все экраны
    css/app.css       стили (1920x1080)
    js/i18n.js        строки EN/ES/RU
    js/storage.js     настройки, плейлисты, кэш (IndexedDB)
    js/m3u.js         разбор плейлиста
    js/text.js        поиск, транслит, ссылки, слова 18+ (как в Roku)
    js/source.js      загрузка плейлиста по ссылке
    js/vlist.js       виртуальный список
    js/dialogs.js     сообщения, окна, PIN, набор номера
    js/player.js      просмотр
    js/browser.js     список групп и каналов
    js/settings.js    настройки
    js/setup.js       приветствие при первом запуске
    js/epg.js         программа передач (XMLTV)
    js/catchup.js     ссылки на архив (как source/catchup.brs в Roku)
    js/app.js         ядро, пульт
