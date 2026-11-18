# ShaGa IPTV Player — shared specification (Roku + LG webOS + Android)

The single source of rules for all three apps. Any change of behaviour goes here first, then into the code of the platforms.
State as of 17 November 2026: **Roku 1.5.0**, **LG 1.5.0** (one version number), **Android 0.4.2** (catching up; see section 11).
Marks: **[Roku]** / **[LG]** / **[Android]** — concerns one platform; **❓** — open question (section 12).
Two versions of this document: `SPEC.md` — Russian, the main one; `SPEC.en.md` — English translation. They are changed together; if they differ, the Russian one is right.

## 1. Purpose and stores
- Name on all platforms: **ShaGa IPTV Player**.
- A free player for the user's own M3U/M3U8 playlists. The app has no channels, no demo playlists and no links to providers.
- Store description: "the app contains no channels; the user adds their own playlist from legal sources". Screenshots without TV channel logos and without 18+ groups.
- Test playlist for reviewers — a separate page (GitHub Pages), only legal open streams, no 18+; given in the submission notes.
- Interface languages: EN, ES, RU; by default the system language, otherwise EN; the first choice is "Automatic (system language)".
- Privacy policy and support contact — on GitHub Pages and as "About" in Settings (all platforms).

## 2. Screens
1. **Welcome** (no playlists): text "the app has no channels…", OK → link input. [Roku] hint about the keyboard QR code for typing on a phone.
2. **Main screen — three columns:**
   - left: `> Search`, `> Playlists`, `> Language`, `> Settings` (no playlist name); then `Search: <query> (N)` (if there was a search), `Favorites (N)`, `All channels (N)`, playlist groups `Name (N)`; a "By PIN" group — `Name [PIN]` without a count;
   - middle: channels, a two-line row — number (column for 4 digits, right-aligned) · logo · name; below, the current programme in a smaller font and a thin progress bar (no guide — empty, bar hidden);
   - right: programme and TV guide (see section 9); Right from the channels — the **channel's TV guide** for the EPG window (yesterday … tomorrow).
   - Top line of the screen: "Now watching: <channel>" (live or archive); nothing plays — empty.
   - Headers: above the groups — the focused group; above the channels — the focused channel; above the third column — the programme on the playing channel ("Archive: <programme>" for the archive), otherwise on the focused one (no guide — the channel name); with the TV guide open — the name of its channel.
   - **No cache and no network at start:** error text in the channel column, cursor on `> Playlists`, no dialog.
   - **Video:** [Roku] a 580×326 thumbnail in the top right corner; [LG] and [Android] full screen under a semi-transparent list ([Android] phone held vertically — a rectangle at the top). *The only planned difference.*
3. **Full screen** — banner: number, name, group, programme and time; hidden after 4 s.

## 3. Controls
- **1.5.0 — the first OK opens the full screen at once** (on a channel, on a programme in the channel's TV guide, on a search result; [Roku] now like [LG]). A repeated OK on what is already playing — also full screen, without a restart.
- **1.4.1 — OK in full screen folds** the video back to the lists ([Roku] to the thumbnail; [LG] under the list) and returns the cursor to the row playback was started from (channel, programme in the programme list or search result). OK there opens the full screen again — playback is not restarted.
- **1.4.1 — "where it plays".** The row of what is playing is marked in every column: the group (category) it was started from; the channel (in search results — exactly the playing programme); the programme in the programme list. In a column without the focus the mark is greyish; in the focused column — a soft colour ([Roku] muted violet, [LG] muted amber) while the cursor is on another row; so before OK is pressed two rows can be highlighted — the cursor and the playing one.
- **1.4.1 — back to the playing row.** If the channel list was scrolled without OK and the user goes Left (or Back) to the groups, the cursor of the channel column returns to the playing row. Going Right to the channel's TV guide keeps the cursor: the guide opens for the channel under the cursor, the playing one stays marked grey. [Roku] a cursor left on a menu row ("> Search" etc.) returns to the shown category when going Right.
- Left/Right — between columns; Right from the channels — the channel's TV guide; Left/Back — back to the channels.
- **Repeated OK** on a programme that is already playing (live or archive) — full screen without a restart.
- **Channel's TV guide:** the current programme is marked `> ` and focused, other days — "yesterday "/"tomorrow ". OK: on air — live; past — archive; future — description. Archive not set (type "From the playlist", and the playlist has no catchup) — "The archive is not set for this playlist: Settings > Playlist > Archive."; programme older than the depth — "This programme is not in the archive."
- **1.5.0 — "Load one more day".** The channel's TV guide opens for the last 24 h … tomorrow. If the channel has an archive deeper than one day, the first row is `+ Load one more day`: OK adds the previous day on top (from memory, no network), the cursor goes to the last programme of the added day — right above the old first one; scrolling can go on. The row disappears at the archive depth of the channel. Days older than "yesterday" are labelled with the weekday and date: "Fri 11/13 21:00".
- **Context menu:** [Roku] the `*` key, [LG] the red key, [Android] long OK / long press. Depends on the place: programme — Description / Watch (if it has started); channel — Add to / Remove from Favorites, Programme description, Channel's TV guide, Watch from the start (if there is an archive), Go to the channel's group (if not in that group's own list); group — Lock with PIN (if shown), Hide (stronger protection, no PIN).
- One-row steps in all vertical lists wrap around (groups, channels, settings, vertical menus). Page scrolling and the Magic Remote wheel stop at the ends. Horizontal dialog buttons and the PIN keypad do not wrap. [Roku] buttons inside Roku system dialogs cannot wrap.
- **Back step by step:** full screen → list (focus on the channels) → groups → stop the video → exit dialog (cursor on "Exit"; Back in the dialog — back to the groups). "Close the app" is the last item of Settings.
- Full screen: Up/Down — switch channels of the current category by the "Channel switching" setting: "Like the list" (default, Up → lower number) / "Classic".
- Favorites: through the context menu; [LG] also the yellow key. Settings: [LG] the blue key (same as `> Settings`).
- [LG] [Android] channel number with digits. [Roku] none (the remote has no digit keys).
- Files (VOD): Left/Right ±10 s, rewind/fast-forward ±30 s, instant replay −15 s, Play/Pause toggles.
- **Archive in full screen:** Left/Right ±60 s, rewind/fast-forward ±5 min ([Roku] instant replay −15 s); seeking = restarting the stream from the new place; the position follows the app's own clock (the player position of shift streams follows the wall clock). Switching the channel — live again. The archive is not remembered as the last channel.
- **1.5.0 — archive watch.** A stream error or buffering longer than 25 s — restart from the same place, up to 3 times in a row (the count is reset after 2 minutes of normal playing); then the message "The archive stream broke off". If the archive programme never started — "The archive of this programme is not available". When the programme ends, the next one follows: if the stream goes on by itself (shift), only the labels change; if the stream ended — the next programme starts (or live, if it is on air now).
- **1.5.0 — stream restart:** [LG] the Stop key (before: stop and go to the list); [Roku] Instant Replay in live. In the archive — restart from the same place.
- [Roku] the `*` key is not used during full-screen video (reserved by the system).

## 4. Playlists
- Settings → "Playlist >" (name · N channels · copy note): Playlists (dialog), Refresh the channel list, **Archive** (From the playlist / shift / flussonic / xc / Off), **Archive depth** (1–7 days, default 3). The archive is kept per playlist `{t, d}`. "From the playlist" — attributes `catchup`/`catchup-type`, `catchup-source`/`catchup-template`, `catchup-days`/`tvg-rec`/`timeshift` (days without a type → shift), defaults from `#EXTM3U`. Links: shift `?utc=START&lutc=NOW`; append/default with placeholders `{utc} {start} {utcend} {end} {duration} {lutc} {now} {timestamp} {offset} {Y} {m} {d} {H} {M} {S}` and `${…}`; flussonic `name-START-DURATION.m3u8` or `/timeshift_abs-START.ts`; xc `/timeshift/u/p/MIN/YYYY-MM-DD:HH-MM/ID.ts` (otherwise shift).
- Up to 5 playlists. "Playlists": the list, the current one marked `*`, the cursor on the current one; `+ Add playlist`, `Close`. For each: Open / Change link / Delete (with confirmation).
- Name: site · file name (if up to 24 characters), without login and password.
- Link input: up to 2048 characters, spaces removed, `https://` added if there is no scheme, check: http(s) and a dot or colon in the server name. **1.5.0:** a Dropbox share link (`www.dropbox.com/…?dl=0`) is replaced with the direct one (`dl.dropboxusercontent.com/…`, without `dl=0/1`).
- **A new playlist is saved only after it loads successfully**; on an error the input dialog stays with the error text. A link that is already saved → that playlist is opened.
- At start the last successfully loaded one opens. Deleting the current one: the video stops, the last successful of the rest opens, otherwise the first; no playlists — Welcome.
- Switching the playlist stops the video and resets the search and the group opened with the PIN.
- **Cache:** the parsed playlist is kept separately for each one ([Roku] a file in `cachefs:`; [LG] IndexedDB; [Android] `pl_<id>.m3u` files). At start the list is shown at once from the cache, the fresh one loads in the background after the video starts. Deleting a playlist deletes its cache; after a link change the old cache is not used. If refreshing failed — the note "saved copy, could not update".
- Loading always bypasses the HTTP cache (otherwise an old copy can come silently). Timeout 30 s. Errors: the server did not answer in time / server error (HTTP code) / could not connect / no M3U at the link / no channels in the playlist.
- M3U parsing: `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `#EXTGRP`, `#EXTVLCOPT:http-user-agent / http-referrer`, `url-tvg` / `x-tvg-url` in the header (1.5.0: links in it are separated by a comma, space or ";"). **1.5.0:** `tvg-name`, if given, is the name to find the channel in the TV guide by name. Archive placeholders — both `{timestamp}` and `${timestamp}`.
- **"Playlist" row in Settings (1.5.0):** "updated at 15:16" / "saved copy, updating…" / "saved copy, could not update".

## 5. Search
- **1.4.0 — three kinds of search.** "> Search" turns the left column into a menu: "Search channels", "Search programmes" (by title), "Search descriptions", "< Back"; Back returns the groups.
- **Masks:** `*` — any number of any characters (including none), `?` — exactly one; `*` at the start and at the end is always implied. Case does not matter, е = ё, Latin finds Cyrillic (transliteration: `garri*potter`). The hint is in the input dialog. The query becomes a regular expression over the original titles (the same builder on Roku and LG).
- **1.5.0 — depth of the programme search.** The input dialog has "Next" instead of OK; then the dialog "1 day / 3 days / Maximum" (cursor on "1 day", a second OK searches at once). Ahead — always 24 hours. Deeper than one day only channels with an archive are searched: "3 days" — not deeper than the channel's archive, "Maximum" — the channel's archive depth (`catchup-days` or the setting), but not deeper than the provider's file has. Channel search — still OK.
- **1.5.0 — description search** reads the whole TV guide file again over the network (descriptions are no longer kept in memory): it searches the full description text; the window "Reading the TV guide file: N of M MB" with a "Stop" button (and Back) — then what was found in the part read is shown. Not available while the guide is loading for the first time. [Roku] needs a server with HTTP Range.
- **18+ — strictly:** channels and programmes of groups marked "18+ group" (hidden and "By PIN" too) never get into any search.
- **Programme search results** — the category `Programmes: <query> (N)` / `Descriptions: …` in the left column; in the second column rows taller than usual: channel (number, logo, name), programme title (scrolls on the focused row), "Yesterday: 11/14/2026 at 13:45" (MM/DD/YYYY) in colour: past — grey-blue, on air — amber, future — green. Order: past by time, on air, future by time. Up to 300 results (the nearest to "now"; the count says "first 300"). The cursor is on the first one.
- The third column shows the TV guide of the focused result's channel, the found programme in the middle; Right — the focus goes into that guide. OK on a result — like in the channel's TV guide (live / archive / description). **1.4.1:** the context menu on a result refers to the found programme: "Programme description" (the found one, not the one on air), "Watch" (if it has started), Favorites, "Go to the channel's group". [Roku] while the search runs, "Searching…" is shown above the channel column.
- By part of the name; case-insensitive; ё=е; accents ignored (`espana` → "España"); Latin finds Cyrillic: transliteration + merged variants kh→h, ts→c, j→y, w→v, x→ks, q→k, ie→e, ye→e (`pervyy` → "Первый").
- Results — the category `Search: … (N)` after the menu rows; recalculated when the playlist is refreshed. Only channels of shown groups. "Nothing found" — a dialog.

## 6. Favorites, autoplay
- Favorites are stored by channel **names**, up to 200 (the access key in links can change), shared by all playlists. The "Favorites" category is before "All channels".
- The last channel (name + group) is remembered and played at start if its group is "Shown". [Roku] only if "Auto-play video" is on in the Roku settings, otherwise the channel is only focused.
- The loading indicator over the video — only for buffering longer than 0.7 s. Channel start timeout 45 s.

## 7. 18+ groups, PIN code
- Group modes: **Shown / By PIN / Hidden**. Stored by group name, shared by all playlists.
- Groups with 18+ signs are "Hidden" by default. Signs (in the group name, case-insensitive): `18+`, `+18`, `(18)`, `xxx`, `ххх` (Cyrillic), `adult`, `porn`, `erotic`, `sexy`, `взросл`, `эрот`, `порн`, `для взрослых`. Channel names are not checked.
- "All channels", search, Favorites, autoplay, deep link — only "Shown" groups. A "By PIN" group: no count; before the PIN the channel column is just empty — no channel names, no programmes.
- **1.5.0 — channels of 18+ groups only in their own group:** they are not in "All channels" and "Favorites" (and not when switching channels in these categories). Ones already in Favorites are not deleted but hidden (they come back if the mark is removed); "Add to Favorites" is not offered for them ([LG] the yellow key shows a hint). [LG] they are not played by channel number.
- **Viewing ("PIN entered" flag):** OK on a "By PIN" group asks for the PIN; the right PIN sets the flag for this group (only one group has the flag). With the flag the group is used freely: channels, programmes, switching — without the PIN. The flag is cleared: (1) when a channel NOT from this group is started (just moving the cursor to another group is not leaving); (2) after 10 minutes with nothing playing — counted from stopping the video or from entering the PIN, even if staying inside the group; pause counts as watching; checked every 30 s; (3) at app start / when the app goes to the background. While a channel of this group plays, the flag stays and the timer does not run. After the reset the group's channel column is empty at once, the PIN is asked again on entry. The "By PIN" mode itself does not change.
- **Settings:** entered without the PIN. The group mode is changed in a dialog of three choices (the current one marked `* `). Making it stronger — without the PIN. Making it weaker (Hidden → any, By PIN → Shown) — with the PIN, once per visit. If no PIN is set — first "Create a PIN code" ("To weaken the protection, first create a PIN code"); choosing "By PIN" without a PIN — the same. A PIN entered or created in Settings does not open anything for viewing.
- **"18+ group" mark (1.3.6):** in the group mode dialog — the switch `[x] 18+ group`. By default it is on for groups recognised by name. Setting it — without the PIN, removing it — with the PIN (like weakening). Stored by group name. If the last channel before exit was from such a group, at start it is neither played nor focused: "All channels", channel 1, no video.
- Change or remove the PIN — with the current PIN or the master code. Removing/resetting the PIN: all "By PIN" groups → "Hidden".
- **Master code** (PIN reset only): local time HHMM (24 h) reversed, 13:45 → 5431, the previous minute is accepted too. Works in any PIN input dialog and leads to "Reset the PIN code?". The real PIN is checked first. Hint in the PIN input dialog (not in the creation one): «Забыли? Введите мастер-код» / "Forgot it? Enter the master code." / "¿Lo olvidaste? Introduce el código maestro." Described in the store listing.
- The PIN is stored as a hash on all platforms ([Roku] and [Android] sha256, [LG] djb2); an old plain PIN on Roku is converted to a hash at start.

## 8. Going to the background
- [LG] the stream stops; on return the video is not resumed if a channel of a "By PIN" group was playing.
- [Roku] the Home key closes the app completely, so "background" = a new start.

## 9. TV guide (EPG)
**Common:**
- [Roku] copies are written with `FormatJson(…, &h0001)` — Cyrillic without `\uXXXX` (otherwise 3 times bigger); the guide copy is compact (time offsets), split into parts of ~25 000 programmes (`ReadAsciiFile` cannot read a ~20 MB file), the old one is deleted before writing, the size goes to the log. [LG] the copy is read after the picture starts; download timeout 30 min.
- **Programme description (1.3.4):** memory keeps the byte place of the programme in the file. On "Description" — a wide window of its own: the short text at once (if any), then ~16 KB around the place is asked from the server (HTTP Range), the start and title are checked and the full `<desc>` is shown; a smaller font for long text. If the programme is not at its place (the provider rebuilds the file every few hours) — a search (1.3.7): the shift is taken from programmes in the piece with a known old place; otherwise by the order of channels in the file and by time (before/after, an estimate by time, halving); up to 10 requests / 6 s. If it fails — the short text stays. To the log: title, time, source (file/memory), length, reason, the first 60 characters.
- **Saved copy** ([Roku] `cachefs:/epg.json`, [LG] IndexedDB, key `epg`): a copy younger than 24 h is shown at once at start; older than 12 h — a fresh file is downloaded in the background, the old guide stays on screen until parsing ends; if the refresh fails the old one stays, retry in 30 min.
- First load without a copy: what is parsed is shown every 20 MB (channels get their guide gradually). Requests for a channel's guide and descriptions during loading are answered with what is there; empty — "The TV guide is loading…".
- XMLTV by the first real http(s) link from `url-tvg` / `x-tvg-url`. Download from the network 1.5 s after the picture starts, refresh every 12 hours, manual — the "TV guide" row in Settings. Switching the playlist — loading by its link. Outdated answers are ignored.
- The file is read as a stream, in pieces; only complete `<channel>` / `<programme>` elements are parsed, the tail waits for the next piece.
- **1.5.0 — what is kept:** programmes of the playlist channels from "−24 h" (for channels with an archive — from the archive depth, at most 14 days) to "+24 h". The short description (200 characters) — only for the programme on air and the next one of each channel; the others keep only title, time and byte place in the file. The description of the programme on air under the video, if it is not in memory, is fetched from the file when the cursor has stayed for 0.7 s, and then kept in memory. Under the programme list while scrolling — time and title (the description — via "Description"). Archive depth grew (setting) — the guide is downloaded again in the background. Guide copy — v4 [Roku] / v5 [LG]: after an app update the guide loads once again.
- Matching: channel key = `tvg-id`, otherwise `n:` + normalised name; first by id, then by `display-name`. Channels with the same tvg-id share the guide. Attributes in double and single quotes.
- Long titles (1.3.6): on the focused row and in the header/"Now" — a scrolling line; in the channel's TV guide under the list — the full title and description. Progress bar: amber fill on a grey track (on the focused row — dark on medium).
- Progress bars in the channel rows are updated every minute ([Roku] before 1.3.7 they were drawn once).
- Display: channel row (programme + progress); third column: "Now" (title, "18:00 – 18:45", "N min left", progress, description up to 7 lines), "Next" — 4 programmes (8 without a description); no guide — the reason (loading / no link / not loaded / none for the channel); player banner — programme and time. Redrawn every minute.
- Diagnostics in the log: time zone; server answer (code, type, size, compression, first 200 characters); progress every 20 MB; result (MB, ms, channels, matched by id/name, programmes total/kept); dropped by reason; if under 50 % matched — id examples.
- **"TV guide" row in Settings (1.5.0):** "up to date: updated 2 h ago, N channels" / "updating… 60/190 MB; now: updated 12 h ago" / "loading… 60/190 MB" / "out of date: updated 15 h ago, could not refresh" (older than 13 h).

**[LG]** done: gzip (by `.gz` and the bytes 1F 8B), encoding from `<?xml encoding>` (including windows-1251).
**[Roku]** done in 1.2.0: the EpgService (Task) keeps the guide and answers the scene (programme on air for all channels every minute; current + next for one channel); reading in 4 MB pieces via HTTP Range, otherwise via a file in `cachefs:`; download timeout 30 min. **Roku limits:** a gzip file is not supported → a clear error; no windows-1251. Column 3 = the playing channel, otherwise the focused one. Manual refresh — the "TV guide" row in Settings.
**[Android]** no TV guide yet (planned in 0.5.0).

## 10. Log
- The first line — name and version. Access keys never get into the log or onto the screen.
- Masking (the same everywhere): parameters `token, key, password, pass, pwd, auth, sid, session, signature, sig, username, user, login, mac` → `***`; `login:password@` → `***@`; Xtream `/live|movie|series|timeshift|play/<login>/<password>/` → `/***/***/`; a path segment of 8+ characters of letters/digits/`-`/`_` with both letters and digits → `***`; a segment of 24+ characters without a dot (hash) — `***` even without digits.
- Key presses are logged (`KEY <key> <where>`, digits are not logged), dialogs opened and closed, menu choices, reasons why the archive was refused.
- [Roku] `telnet <ip> 8085`, switched off in config.brs. [LG] in the TV memory, 1500 entries, "Settings → Log"; the code text must not contain the words password/token/pwd (LG scanner). [Android] `adb logcat -s ShaGa`.

## 11. One platform only
- **[Roku]** `rsg_version=1.3`, Roku OS 15.1+, memory monitor, `AppLaunchComplete`, deep link (`contentId` = tvg-id or name, only shown groups), the auto-play rule, `*` not during video, package ≤ 4 MB.
- **[LG]** red key — context menu (instead of `*`; does nothing in full screen yet), yellow — Favorites, blue — Settings; engine choice (built-in webOS by default / hls.js 1.7.3), Magic Remote (pointer and wheel), colour keys, number input, `screenSaverProperties`, Back on the start screen by LG rules, Full HD 1280×720, font ≥ 20 px.
- **[Android]** (from `android/README.md`, version 0.4.2): one app for Android TV / Google TV (remote) and phones/tablets (touch); Kotlin + Compose, Media3 (ExoPlayer) player, Android 7.0+ (API 24), target API 36; the APK is built in GitHub Actions (`shaga-iptv-player-<version>-android`). Context menu — long OK or long press; OK acts on release (to tell a long press). Channel number with digits (up to 4 digits, 1.6 s pause or OK). Phone in full screen: swipe up — next channel, down — previous. Own dialogs (menus, questions, PIN keypad); link and search input — the system dialog (on-screen keyboard). Log — `adb logcat -s ShaGa`, tags as on Roku. Playlist copies — `pl_<id>.m3u` files.
- **[Android] is catching up with Roku and LG:** versions `0.x.y` until parity. Done: main screen and playback, channel search (with masks), Favorites, context menu, language, Settings, group modes, 18+ mark, PIN and master code, up to 5 playlists, autoplay, the 1.4.1 behaviour (OK folds, "where it plays", back to the playing row). Not yet: TV guide, programme and description search, archive (planned in 0.5.0) and the 1.5.0 rules (first OK — full screen at once, 18+ group channels outside "All channels" / "Favorites").

## 12. Open questions
All earlier questions are settled (PIN reset, error at start, playlist name — in Settings, the name, the version rule, PIN hash, Roku EPG: plain UTF-8 + Range).
- The archive was checked only on links, not on a TV. For only4: Settings → Playlist → Archive = shift, depth by the provider.

## 13. Next
- 1.3.2: faster guide parsing (drop other channels before parsing, compact storage, on Roku — without Split); optionally — a guide link of its own for a playlist.
- Faster EPG parsing on Roku (154 s for 175 MB).
- Check the test playlist `docs/test.m3u` on TVs.
- Materials for the LG Store (icons, screenshots, description with the master code).

## 14. How we work
- **Versions:** one number for Roku and LG ([Android] — `0.x.y` until parity, then the shared number). PATCH — fixes; MINOR — a new feature (PATCH → 0); MAJOR — only if saved data cannot be carried over or the interface changes completely. A store release does not change MAJOR. A fix for one platform only may skip the number on the other.
- Archives: Roku — `shaga-iptv-player-<version>-roku.zip`; LG — `shaga-iptv-player-<version>-lg.zip`; only the changed files in the repository structure — `shaga-iptv-player-repo-update-<version>.zip` (the full `shaga-iptv-player-repo-<version>.zip` — on request) (hyphens everywhere). Files inside — right at the root.
- For an LG version — a ready list of commands: unpack, build the ipk, install, launch, delete the temporary folder.
- With each version: how to install, the changed files for GitHub, a commit title up to 50 characters and a description in English, a sync summary; the GitHub About line — with the Android version from the repository (`android/app/build.gradle.kts`).
- The specification is changed in both language versions at once: `SPEC.md` (Russian, main) and `SPEC.en.md` (English).
