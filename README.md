# ShaGa IPTV Player

Free player for your own M3U/M3U8 playlists — for **Roku** and **LG webOS**.
The app contains no channels: you add a link to a playlist you are allowed to use.

Бесплатный плеер для ваших собственных плейлистов M3U/M3U8 — для **Roku** и **LG webOS**.
В приложении нет каналов: вы добавляете ссылку на плейлист, которым вправе пользоваться.

## Folders / Папки

| Folder | What |
|---|---|
| `roku/` | Roku app (BrightScript, SceneGraph) |
| `lg/` | LG webOS app (HTML/JS) |
| `docs/` | GitHub Pages: privacy policy, test playlist for store reviewers |
| `SPEC.md` | Shared behaviour spec for both apps (in Russian) / Общая спецификация |

## Install on Roku (developer mode) / Установка на Roku

1. Zip the **contents** of `roku/` (`manifest`, `source`, `components`, `images` at the top of the zip).
   Упакуйте **содержимое** папки `roku/` в zip (файлы сразу в корне архива).
2. Open `http://<roku-ip>` in a browser, sign in as `rokudev`, upload the zip, press Install.
3. Debug log: `telnet <roku-ip> 8085`.

## Install on LG (developer mode) / Установка на LG

```
cd lg
ares-package .
ares-install com.shaga.player_<version>_all.ipk
ares-launch com.shaga.player
```

## Links / Ссылки

- Privacy policy: https://shamilganiev.github.io/shaga-iptv-player/privacy.html
- Test playlist: https://shamilganiev.github.io/shaga-iptv-player/test.m3u
- Support: https://github.com/shamilganiev/shaga-iptv-player/issues

## License

MIT — see `LICENSE`. `lg/js/lib/hls.min.js` — hls.js, Apache-2.0 (`lg/js/lib/hls.LICENSE.txt`).
