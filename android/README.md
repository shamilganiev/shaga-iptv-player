# ShaGa IPTV Player — Android (TV + phones)

Универсальный плеер M3U-плейлистов. Сам каналов не содержит.
Одно приложение для Android TV / Google TV (пульт) и телефонов/планшетов (сенсор).
Правила поведения — в `SPEC.md` в корне репозитория; до паритета с Roku и LG версии Android идут как `0.x.y`.

## Версия 0.2.0 — главный экран и просмотр
- Левая колонка: `> Изменить ссылку`, `> Настройки`, `Все каналы (N)`, группы плейлиста.
- Колонка каналов: номер, логотип, название. Третья колонка (широкий экран): канал и его состояние.
- Видео идёт под полупрозрачным списком; на телефоне вертикально — прямоугольник вверху.
- OK на канале запускает его, повторный OK — полный экран. На полном экране вверх/вниз и CH+/− переключают каналы категории (направление — в Настройках), OK показывает плашку.
- Back по шагам: полный экран → каналы → группы → остановить видео → окно «Закрыть?».
- Пульт обрабатывается в `MainActivity` и `Nav.kt`; экран фокусом ввода не пользуется.

Нет: поиска, избранного, PIN-кода, нескольких плейлистов, кэша, программы передач, архива.

## Сборка
Собирает GitHub Actions (`.github/workflows/android.yml`) при каждом изменении в `android/`.
Готовый APK: вкладка **Actions** → последний запуск → **Artifacts** → `shaga-iptv-player-<версия>-android`.
Android Studio не нужна, но проект в ней открывается (папка `android/`).

Версии: Gradle 8.13, Android Gradle Plugin 8.11.1, Kotlin 2.2.20, Compose BOM 2025.08.00, Media3 1.8.0, Coil 3.3.0.
Android: минимум 7.0 (API 24), цель — 16 (API 36).

## Установка (adb из Android SDK Platform-Tools)
    adb connect <ip-устройства>:5555
    adb install -r shaga-iptv-player-<версия>-android.apk
    adb shell am start -n com.shaga.player/.MainActivity

## Журнал
    adb logcat -d -s ShaGa:V AndroidRuntime:E
Первая строка — название и версия. Теги как в Roku: APP, KEY, PLAY, ERR, LOAD, UI.
Ключи доступа в ссылках маскируются (`Mask.kt`), цифровые кнопки не пишутся.

## Структура
    app/build.gradle.kts            версия, уровни Android, библиотеки
    app/debug.keystore              тестовый ключ подписи (не секретный, не для Google Play)
    app/src/main/AndroidManifest.xml
    app/src/main/res/               название, тема, баннер для ТВ 320x180, значок
    app/src/main/java/com/shaga/player/
        Config.kt           настройки (тайм-ауты, User-Agent)
        Strings.kt          строки EN/ES/RU
        TextUtil.kt         ссылки: очистка, проверка, имя плейлиста
        M3u.kt              разбор плейлиста
        PlaylistLoader.kt   загрузка плейлиста по ссылке
        Storage.kt          сохранённые настройки (JSON, схема как в Roku и LG)
        Mask.kt, AppLog.kt  журнал и маскировка ключей
        Nav.kt              логика главного экрана: курсор, колонки, полный экран, Back по шагам
        Playback.kt         плеер Media3 (ExoPlayer), один на всё приложение
        Ui.kt               экраны (Compose)
        MainActivity.kt     окно приложения, кнопки пульта, окна ссылки / настроек / выхода
