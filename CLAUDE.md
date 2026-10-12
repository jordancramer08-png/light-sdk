# CLAUDE.md — Watch (movies + TV player) for Light Phone III

Read this at the start of every session, together with PROGRESS.md. If anything here
conflicts with what you remember about Android development, **this file wins** — this is
not a standard Android environment.

Jordan is not an Android developer. Explain anything he must do in plain, numbered steps,
including exactly which file to double-click or which command to type. Keep explanations
short. He reads this code himself: short functions, plain names.

---

## 1. What this app is

ONE Light Phone III tool, label **Watch**, that plays movies and TV from files on the phone.
Poster-grid library like Plex/Jellyfin, a full-screen player, full color, and a PC script
that loads titles, posters and details. The phone app itself is offline and uses no GPS.

## 2. Platform constraints (non-negotiable)

- **Target:** Light Phone III on LightOS, 1080 x 1240, 3.92", API 34, no Google Play
  Services. Jordan's phone shows **full color** (grayscale is turned off). Use color freely
  (poster accents, themes), but never let color be the only signal: also use a label, icon or
  position.
- **All app code in the `tool` module**, package `com.jordan.watch`, using primitives from
  `sdk/client` and `sdk/ui`. Kotlin, Compose, Coroutines, MVVM. Screens extend `LightScreen`;
  view models extend `LightViewModel` (there is no `LightScreenViewModel`).
- **Navigate with `navigateTo` only.** Never override `onBackPressed()`. The SDK draws no back
  bar: every non-root screen places its own
  `LightTopBar(leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }))`.
- **Libraries and imports are lint-enforced** (see `NOTES.md` and `lint-rules/`). Media3
  ExoPlayer is already used by Listen, so it is allowed; check the video-surface classes
  (`PlayerView`, `SurfaceView`, `AndroidView`) before using them. If lint rejects something,
  stop, name what it blocked, and offer two alternatives. Do not work around it.
- **No emulator. No Android Studio.** Build from PowerShell with the Gradle wrapper
  (`.\gradlew.bat :tool:testDebugUnitTest :tool:assembleDebug`). Phone testing happens only
  in the last session ("Phone Day"). After the FIRST install the phone must be rebooted
  (the launcher caches its menu).
- `local.properties` may hold private keys. **Never read, print or commit it.**
- Pure logic (sorting, filtering, search matching, title parsing, position and resume math,
  speed steps, next-up choice, storage maths) lives in `com.jordan.watch.logic` with NO
  `android.*` / `androidx.compose.*` imports, covered by JVM unit tests.
- Anything that depends on an unconfirmed phone ability ships with a working fallback and a
  switch in `FeatureFlags.kt`, default OFF until Phone Day confirms it.

## 3. Identity — protects Jordan's other apps

- `applicationId` / `id = "com.jordan.watch"`, `label = "Watch"`, `serverPackage = "com.lightos"`.
- **Never reuse** the ids already on his phone: `com.thelightphone.app`,
  `com.thelightphone.smallgroup`, `com.thelightphone.bible`, `com.thelightphone.reader`,
  `com.thelightphone.listen`, `com.jordan.utilities`, `com.jordan.faith`,
  `com.thelightphone.ferment`. Same id = overwrites that app and destroys its data.
- Branch `watch` of `jordancramer08-png/light-sdk`. Never merge, rebase onto, or push to
  `main`, `reader`, `listen` or any other tool's branch. Commit at the end of each session
  and `git push origin watch`.
- Never edit files outside this folder. Reading `Documents\listen`, `Documents\reader` and
  `Documents\lp3-utilities` is allowed, read-only, in Session 0 and whenever a session prompt
  says so. **Reuse their patterns** (lists, rows, themes, settings, file access, volume keys,
  Gradle, signing, install commands) instead of inventing new ones.

## 4. Where the files are (the PC script depends on this; don't change it)

```
/sdcard/Watch/Movies/<Collection or "_Singles">/<Title (Year)>/movie.<mp4|mkv>
        movie.json   poster.jpg   backdrop.jpg (optional)   movie.en.srt (optional)
/sdcard/Watch/TV/<Show>/show.json   poster.jpg
/sdcard/Watch/TV/<Show>/Season 01/S01E01.<ext>   S01E01.json   (thumb optional)
/sdcard/Watch/.state/   positions.json watched.json watchlist.json settings.json last-sync.txt
```

- **File access:** `MANAGE_EXTERNAL_STORAGE` and plain `java.io.File`, exactly as Listen does
  (PC script grants it with `adb shell appops set com.jordan.watch MANAGE_EXTERNAL_STORAGE allow`).
  If access is missing, show a plain screen telling him to run `Watch-Phone-Sync.cmd`.
- **App state lives in `/sdcard/Watch/.state/`**, not private storage, so an uninstall or new
  signing key can never erase his place in a movie. Write every file atomically
  (`name.tmp`, then rename). The PC script can back it up and restore it.
- **Rescan** on launch/resume when `last-sync.txt` or folder mtimes changed; only re-read
  titles whose path/size/mtime changed. Never block the UI: show "Updating library...".
- Don't scan outside `/sdcard/Watch/`. Don't modify or delete video files from the app
  (the PC script adds and removes). No internet in the app. `permissions` = storage access only.
- Never commit videos, posters, TMDB data, or `private\` contents.

## 5. movie.json / show.json / episode json (the PC script writes them)

```json
{ "schema": 1, "id": "Collection/Title (Year)", "title": "Hacksaw Ridge", "year": 2016,
  "collection": "Harry Potter", "collectionNumber": 1, "overview": "...",
  "genres": ["Drama","War"], "runtimeMin": 139, "rating": 8.1, "contentRating": "R",
  "director": "Mel Gibson", "cast": ["Andrew Garfield"], "poster": "poster.jpg",
  "backdrop": null, "file": "movie.mp4", "width": 1920, "height": 1080,
  "videoCodec": "h264", "audioCodec": "aac", "bytes": 123, "sentAt": "2026-10-12T15:00:00" }
```

If a json is missing or broken: fall back to the folder name `Title (Year)` and never crash.
`collectionNumber` and `rating` may be null. Show/episode json carry title, overview, season,
episode, runtime, air date, still image.

## 6. Library screens

- Tabs (bottom bar): **Movies, TV, Collections**. Home opens with **Continue Watching**
  (progress bars), then Recently Added.
- Poster grid (2 or 3 columns, setting) or list with small poster. Posters decoded downscaled
  (about 240 px grid, 800 px detail), cached to disk by key, loaded off the main thread, small
  memory LRU. No poster = placeholder with the title's first letter.
- Detail page: poster, title, year, runtime, rating, content rating, genres, overview,
  director, cast, quality line (e.g. "1080p H.264 AAC · 2.1 GB"), Play/Resume, Mark watched,
  Watchlist, Remove from phone (confirmation screen).
- TV: show -> seasons -> episodes (watched marks, runtime). Collections open in story order.
- **Sort** (each A–Z / Z–A, remembered per tab): Title (ignore leading The/A/An), Year,
  Recently added, Recently watched, Runtime, Rating, Genre, Collection, Resolution, File
  size, Unwatched first. **Filters:** genre, decade, watched / unwatched / in progress,
  content rating, collection, quality. **Search:** title, cast, director; every typed word
  must match, any order, accent/case-insensitive. Match the Reader's sort screen style.
- Every list is a `LightLazyScrollView` with uniform rows. 325+ titles must scroll smoothly.

## 7. Player

- **Media3 ExoPlayer**, hardware decoding. Original files untouched when the phone can decode
  them. File that fails: "Can't play this file", back to the library, never crash.
- **Full screen:** immersive (hide system bars and cutout area), edge to edge, screen kept on.
- **Orientation: NEVER auto-rotate.** The screen is locked to portrait or landscape and
  changes only when Jordan presses the rotate button in the player. Rotation must not restart
  playback or lose the second (handle the configuration change; keep the player alive).
  Setting "Start in": Portrait / Landscape / Last used. The library screens stay portrait.
  **Feasibility first:** `Context` is import-blocked and the SDK may only allow
  `orientation = "portrait"` in `lighttool.toml`. Session 0 must find out how a tool can
  switch orientation at runtime. If the SDK can't, report it and propose an SDK change in a
  separate "SDK: ..." commit (like Listen's volume keys). If LightOS blocks it, stop and tell
  Jordan.
- **Aspect:** Fit / Fill (crop) / Zoom, pinch to zoom freely, remembered per orientation.
- **Controls** (fade after 3 s, tap to show): play/pause; back/forward **15 s** (setting 10/15/30);
  double-tap left/right half to skip; press-and-hold the skip buttons to scan; seek bar with
  elapsed/remaining and a time label while dragging; **speed 0.25x–3.0x in 0.25 steps**
  (quick buttons 0.5, 0.75, 1.0, 1.25, 1.5, 2.0), pitch corrected, remembered per title;
  previous/next in collection or season; audio track picker; subtitle picker; lock-controls
  button; rotate button; aspect button; 90-second skip-ahead button.
- **Resume:** per title save position, speed, watched flag, last-played; every 10 s while
  playing and at once on pause, seek, leaving the player, app pause. Reopen rewinds 5 s.
  Watched at the last 3% or 2 minutes, whichever is smaller. Keyed by `id`, so a title
  removed and sent again resumes where he stopped.
- Pause on headphone/Bluetooth disconnect; audio focus via Media3 (`handleAudioFocus = true`);
  Bluetooth/headset buttons work.
- **Keys:** volume keys change MEDIA volume everywhere in Watch with a small on-screen volume
  bar for about 2 s (reuse Listen's code); scroll-wheel press and camera button do nothing
  (they never reach LightOS); turning the wheel stays LightOS brightness.
- Subtitles: embedded text tracks and sidecar `.srt`; size and position settings; sync nudge
  in 100 ms steps. Audio/video sync nudge in 50 ms steps if ExoPlayer allows; otherwise skip
  and say so.
- Sleep timer: 15/30/45/60 min or end of movie.
- **Next up:** at the end of a collection film or episode, offer the next one with a 10-second
  countdown that can be cancelled.
- Show codec, resolution and bitrate in small text on a "Details" overlay.

## 8. PC side (scripts\, double-click .cmd files)

Everything that organizes, converts, names or sends files is a double-click `.cmd` in
`scripts\`, calling a PowerShell script. Each one: checks for adb (PATH or a remembered
folder), requires exactly one connected device, prints each step in plain English, logs to
`logs\`, never deletes phone data without asking, and with no phone connected explains what
it would do and stops. Phone handoff is proven: `adb push` to `/data/local/tmp`, then
`adb shell` copy into `/sdcard/Watch/...` as `.part`, rename at the end.

- `Watch-Phone-Sync.cmd`: menu of D:\Movies and D:\TV titles with sizes; pick what goes; a
  running total against the **50 GB cap** (Jordan's choice; the script refuses to exceed it
  and shows free space on the phone); per-send quality: Original / 1080p / 720p small
  (estimate 720p about 2 Mbps, 1080p about 4 Mbps, then measure the real size and warn).
  Parses `Title (Year)` from file names, folders become collections (numbered 01, 02 = story
  order). Library check flags files the phone can't play (AVI, AC3/DTS/TrueHD audio,
  10-bit HEVC, embedded cover track) and fixes them: remux with `-c copy`, or convert only
  the audio to AAC, or re-encode when shrinking. Embedded cover art is extracted to
  poster.jpg and stripped from the video (`-map 0:v:0 -map 0:a`).
- **TMDB**: free key in `private\tmdb-key.txt` (git-ignored; never print it). PC-side only.
  Look up by title + year, save poster, backdrop, overview, genres, cast, director, runtime,
  rating, content rating, collection into json. Cache lookups in `private\tmdb-cache\`.
  Embedded cover wins over a downloaded poster only if no TMDB match. Show the required
  line in the app's Settings: "This product uses the TMDB API but is not endorsed or
  certified by TMDB."
- Remove menu (deletes chosen titles, keeps watched marks and positions), Backup/Restore of
  `.state`, and an option that grants MANAGE_EXTERNAL_STORAGE.
- **Ask before installing software.** ffmpeg is needed on the PC:
  `winget install Gyan.FFmpeg` after Jordan says yes. Prefer PowerShell + ffmpeg only.
- **Nothing in `D:\Movies` or `D:\TV` is ever changed.** All work happens on copies in
  `%TEMP%\watch-*`, deleted at the end.
- TV naming expected on the PC: `D:\TV\<Show>\Season 01\<Show> - S01E01 - <Title>.mp4`.
  Create `D:\TV` with a short README.txt explaining this if it doesn't exist.
- Test mode (no phone): `WATCH_DRY_RUN=<folder>` writes the finished layout there and sends
  nothing.

## 9. Look and feel

Match the Reader and Listen: same theme options, spacing and row style, 48 dp+ tap targets,
long names ellipsize or scroll, never wrap into three lines. Full color: poster-derived
accent on the detail page, a dark default so a movie library doesn't glare. Quiet and fast.
No streaks, no badges, no notifications.

## 10. Out of scope (suggest, don't build)

Family mode, casting, trailers, online playback, accounts, sync, HDR/Dolby Vision, DTS
decoding on the phone, picture-in-picture, background video, downloads inside the app.

## 11. Session hygiene

Start by reading PROGRESS.md. Every session ends with `gradlew.bat :tool:testDebugUnitTest
:tool:assembleDebug` passing (log to `logs\sessionN-build.log`, git-ignored), PROGRESS.md
updated, a commit with the session number, and Jordan shown the session's Done-when
checklist marked with a check or a cross for each item. If something conflicts with the SDK,
stop and explain before changing course.
