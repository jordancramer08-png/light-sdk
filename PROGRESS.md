# PROGRESS.md — Watch

## Session 0 — Recon (2026-10-12) ✅

No app code written. Findings:

**Reusable from Listen / Reader (read-only):** Media3 1.10.1 already in
`gradle/libs.versions.toml`; Listen's `scripts\` (.cmd + PowerShell, adb checks, push via
`/data/local/tmp`), MANAGE_EXTERNAL_STORAGE + `java.io.File` storage, media-volume SDK
call + on-screen volume bar, settings/theme screens, Reader's sort screen and list rows.

**(a) Show video — YES with an SDK addition.** `androidx.media3` is allow-listed
(`LightSdkPlugin.kt:38`, covers media3-ui / ui-compose). `AndroidView` and `SurfaceView`
are not blocked. BUT `ExoPlayer.Builder` needs a `Context`, which tools cannot get
(`android.content.Context` import-blocked, `LightSdkPlugin.kt:81`). Listen solves audio
the same way: the player lives in the SDK (`sdk/.../audio/LightAudioPlayer.kt`).
- Way 1 (recommended): commit "SDK: let a tool play video" — a `LightVideoPlayer` in
  `sdk/client` that builds the ExoPlayer and hands back a composable surface.
- Way 2: build ExoPlayer inside `AndroidView(factory = { ctx -> ... })`. Lint likely
  passes, but it sidesteps the Context rule — only if Jordan approves.

**(b) Full screen + screen on — YES.** `LightActivity.kt:99-103` already hides system bars
(swipe shows them briefly) and draws edge to edge. Screen-on: Compose `Modifier.keepScreenOn()`
or `keepScreenOn = true` on the video view; neither is blocked. Cutout area: not set by the
SDK; small SDK tweak if the notch area shows a bar on Phone Day.

**(c) Button-only portrait/landscape — NO from tool code.** `lighttool.toml` allows only
`orientation = "portrait"` (`LightToolMetadata.kt:174`), and switching at runtime needs
`Activity.requestedOrientation`, which tools can't reach (Activity casts are a lint ERROR).
- Way 1 (recommended, no SDK change): keep the app portrait and draw the player rotated
  90° in Compose (TextureView surface + `graphicsLayer` rotation, swapped width/height).
  Playback never restarts because nothing is recreated. Fallback behind
  `FeatureFlags.ROTATED_PLAYER`.
- Way 2: commit "SDK: let a tool set its orientation" — `SealedLightActivity.setOrientation()`
  plus `configChanges="orientation|screenSize|screenLayout"` in `ManifestGenerator.kt`.
  Phone Day must confirm LightOS honours it.

**Repo warnings:** `tool/lighttool.toml` still says `id = "com.thelightphone.bible"` /
`label = "Bible"`, and `tool/` + recent commits are the Bible app. NOTES.md is Prayer List
recon. Session 1 must swap the id/label to `com.jordan.watch` / `Watch` and clear the old
tool code BEFORE any build is installed.

**Decisions (Jordan, 2026-10-12):** (a) Way 1 — SDK video player. (c) Way 1 — rotated player in a portrait app.

## Session 1 — Clean slate and foundations (2026-10-12) ✅

- Bible code removed from `tool/` (still in git history). `tool/lighttool.toml` is now
  `com.jordan.watch` / `Watch`, portrait, permission MANAGE_EXTERNAL_STORAGE only.
  Checked in the built manifest: `package="com.jordan.watch"`, `label="Watch"`.
- SDK commit "SDK: allow MANAGE_EXTERNAL_STORAGE in lighttool.toml" (same as Listen's f91a373).
- `com.jordan.watch`: `HomeScreen` (placeholder + "run Watch-Phone-Sync.cmd" screen when
  there's no file access, re-checked on every return), `FeatureFlags.ROTATED_PLAYER = false`,
  `storage/WatchPaths.kt` + `StorageAccess`.
- `logic/`: `AtomicFile` (tmp + rename), `StateStore` (positions, watched, watchlist,
  settings in `.state/`), `MediaJson` (Movie / Show / Episode + folder-name fallback).
- Tests: 12 passing (StateStoreTest 5, MediaJsonTest 7). Build log: `logs/session1-build.log`.
- **Moved to Session 2:** the "SDK: let a tool play video" player (decision a) wasn't in
  this session's prompt.

## Session 2 — SDK video player + library logic (2026-10-12) ✅

- SDK commit "SDK: let a tool play video": `sdk/client/.../video/LightVideoPlayer.kt`.
  Tools get one with `DefaultLightVideo(sealedActivity).newPlayer()` (same shape as
  `DefaultLightAudio`). It owns the ExoPlayer (audio focus on, pauses on headphone unplug)
  and draws on a **TextureView**: `player.Surface(modifier, quarterTurns = 1)` turns it 90°
  and swaps width/height, so rotating never restarts playback. Play, pause, seekTo,
  positionMs/durationMs, setSpeed (0.25–3.0, pitch kept natural), audioTracks /
  subtitleTracks + select (null = subtitles off), sidecar .srt/.vtt, `subtitleText` (the
  app draws the lines), videoSize/videoFormat (for the Details overlay), keepScreenOn, release.
- Lint: the only thing it flagged was Media3's "unstable API" marker on reading the video
  bitrate; fixed with `@OptIn(UnstableApi::class)` exactly as Listen does. A throwaway tool
  file using `DefaultLightVideo` + `Surface` passed the Light import/code rules (deleted).
- `logic/`: `LibraryItem` (+ `WatchState`), `Sorting` (11 keys, A–Z/Z–A, missing values
  last, ties by title, The/A/An ignored), `Filters` (+ `Quality`, choice lists), `Search`,
  `Resume`, `Speed`, `NextUp`, `StorageMath` (50 GB cap, estimates, "1080p H.264 AAC · 2.1 GB").
- Tests: 49 passing (37 new). Build log: `logs/session2-build.log`.
- Not yet on the phone: the rotated TextureView and subtitle rendering are Phone Day checks.

## Session 3 — App core: scanner, index, posters, repositories (2026-10-12) ✅

- `logic/LibraryScanner`: finds `Movies/<Collection>/<Title (Year)>/` and
  `TV/<Show>/<Season>/S01E01.<ext>` (mp4, mkv, m4v, mov, webm). Skips hidden folders/files
  (`.state`, `.part` sends) and folders with no video. `stamp()` = last-sync.txt + every
  folder's change time; same stamp = no scan. A rescan re-reads only titles whose folder,
  video size/time or json changed; removed titles drop out. Same design as Listen's music.
- `logic/LibraryIndex`: the scan result (saved to the app's own `files/cache/library_index.json`
  so the library shows instantly on launch; always rebuildable) and `Library`: lookups by id,
  collections in story order, seasons, episodes.
- `storage/WatchLibrary`: app-wide `StateFlow<LibraryState>`. `refresh(filesDir)` queues a
  background check (one at a time); shows the saved index first, sets `updating` while
  rescanning. Called from `HomeScreen.willShow()` (launch + every return), which now shows
  "Updating library..." or the movie/show count. No new screens.
- `storage/PosterCache` (+ pure `logic/PosterMath`): posters decoded downscaled (GRID 240 px,
  DETAIL 800 px wide), saved as small JPEGs in `files/cache/posters` keyed by path+size+time,
  16 MB memory LRU, 2 decodes at a time off the main thread, one job per picture. Same as
  Listen's ArtworkCache. Missing poster = `Poster.Missing` (placeholder letter, Session 4).
- `logic/Repositories`: `PlaybackRepository` (positions, watched; reaching the end marks
  watched; Continue Watching ids newest first), `WatchlistRepository`, `SettingsRepository`,
  each a StateFlow over the `.state` files. Re-read on every refresh (PC Restore is noticed).
  A failed write keeps the change in memory.
- Tests: 66 passing (17 new: LibraryScannerTest 8 on a made-up Watch folder,
  RepositoriesTest 6, PosterMathTest 3). Lint clean. Build log: `logs/session3-build.log`.
- Phone Day checks: scan time for 325 titles, poster decode speed.

## Session 4 — Library UI (2026-10-12) ✅

- `HomeScreen` is now the library. Tabs at the bottom: **Home, Movies, TV, Collections**
  (Home added as a 4th tab so Continue Watching has its own place; the chosen tab is
  underlined and brighter, not color only). Home = Continue Watching (progress bar +
  "1h 10m left", episodes as "S1 E3") then Recently Added (newest 20 sends).
- Top bar: left icon switches poster grid / list; right "3 across" / "2 across" switches
  grid columns. Both saved in settings.json (`libraryView` added to `WatchSettings`).
- `ui/`: `WatchTheme` (Dark default, Light; accent color), `PosterImage` (via PosterCache;
  no poster = block in the title's own color + first letter), `LibraryLists` (grid rows of
  2/3 posters at 19 / 13.5 grid units, list rows 5 units, all in `LightLazyScrollView` with
  one fixed row height; watched tick on posters, "· Watched" in lists).
- `logic/Shelves`: turns the library into tiles (continue watching, recently added, movies
  A–Z, shows, collections, grid rows, runtime text, placeholder letter/hue). Tile lists are
  worked out off the main thread.
- **Fake library (debug only):** `tool/src/debug/.../dev/DevLibrary.kt` makes 300 movies +
  25 shows + 7 in-progress titles in memory; `src/release` has an empty twin, so release
  builds contain none of it. Switch: `FeatureFlags.FAKE_LIBRARY` (default OFF). While on,
  positions/watched are never read from or written to `.state`. Nothing fake ever reaches
  the phone's storage or git.
- Taps on a poster do nothing yet (detail / show / collection screens are Session 5).
- Tests: 73 passing (7 new in ShelvesTest, incl. the fake library). Lint: no new warnings.
  Build log: `logs/session4-build.log`.
- Phone Day checks: grid scroll smoothness with 300+ posters, row heights look right.

## Session 5 — Detail, TV, Collections, Watchlist, Sort & Filter, Search, Settings (2026-10-12) ✅

- **Home** top bar: left switches posters/list, right (gear) opens **Settings**. Under it a
  strip: **Watchlist** (Home) or **Sort & Filter** (tabs, shows "(2)" when filters are on),
  **Search**, **Surprise me**. Every poster now opens its screen (`Navigation.openTitle`).
- **DetailScreen** (movies and episodes): poster, title, "2016 · 2h 19m · ★ 8.1 · R",
  genres, quality line, overview, director, cast, progress bar, Mark watched / unwatched,
  Add to / remove from Watchlist, Remove from phone; bottom button PLAY or
  "RESUME FROM 1:10:05". Accent color is taken from the poster (`logic/AccentColor`,
  decoration only: a rule under the header and the progress bar). PLAY shows "Playback
  arrives in the next update" until Session 6.
- **Remove from phone** (`RemoveScreen`, confirmation with size). CLAUDE.md §4 says the app
  never deletes video files, so REMOVE writes the id to **`.state/remove-requests.json`**,
  hides the movie at once, and the detail page offers "Keep" to undo. **Session 8/9: the PC
  script must delete those folders on the next sync and clear the file** (keeping
  positions/watched).
- **TV:** `ShowScreen` (poster, facts, overview, Watchlist, seasons with "10 episodes · 4
  watched" / "All watched") → `SeasonScreen` (episodes in order, runtime, air date,
  "· Watched", progress bar) → episode detail. Specials (season 0) last.
- **CollectionScreen**: story order, numbered "1. …". **WatchlistScreen**: movies + shows in
  the order added.
- **Sort & Filter** (`SortFilterScreen`, Reader style: headings, filled/empty circles,
  hairlines): all 11 sorts, Order with words per sort (A–Z / Z–A, Newest first, Longest
  first…), filters Show (watched state), Genre, Decade, Content rating, Collection (Movies),
  Quality (not TV), "Clear all filters". Each tap is saved at once **per tab** in
  settings.json (`tabViews`); the screen stays open. Logic: `logic/Browse` (`TabView`,
  shows/collections summed into one item each).
- **Search**: title, cast, director across movies and shows (every word, any order, accents
  ignored). Uses the SDK's full-screen `LightTextInputEditor`; SEARCH shows results, tap the
  box to type again. (Listen's live-search keyboard needs an SDK addition that only exists on
  the listen branch; not copied.)
- **Surprise me**: random movie never started, Genre ‹ › and Length ‹ › (any, 1h 30m, 1h 45m,
  2h, 2h 30m); ANOTHER never repeats the last pick. Movies only.
- **Settings**: Theme, Library view, Poster columns 2/3, Skip 10/15/30 s (15 default), Start
  the player in Portrait/Landscape/Last used, Subtitle size S/M/L/XL, and the TMDB line.
- Tests: 83 passing (10 new in BrowseTest). Lint: only the 4 old warnings. Build log:
  `logs/session5-build.log`.
- Phone Day checks: accent colors look good, detail page layout, search keyboard.

## Session 6 — Player core (2026-10-12) ✅

- SDK commit "SDK: let a tool change the media volume" (Listen's `536682a`, cherry-picked
  unchanged): `lightContext.mediaVolume`.
- **Every screen** now extends `ui/VolumeKeyScreen` (Listen's code): volume keys change the
  MEDIA volume and show a "Volume 7 of 15" bar for 2 s; scroll-wheel press (319) and camera
  button (27, 80) do nothing; turning the wheel stays LightOS brightness.
- **`PlayerScreen`** (PLAY / RESUME on the detail page opens it), on the SDK's
  `LightVideoPlayer`. Black, full screen (LightActivity already hides the system bars),
  screen kept on by the video view. Controls (fade after 3 s while playing, tap to show/hide):
  back, title, play/pause (replays at the end), skip back/forward by the setting (15 s
  default, label under the button), hold a skip button to scan (5 s jumps, 15 s after 2 s,
  60 s after 6 s), double-tap left/right half to skip, seek bar with elapsed / remaining and
  a big time label while dragging (seeks when the finger rests 0.3 s), Speed button → panel
  with − / + (0.25 steps, 0.25x–3.0x) and quick 0.5 0.75 1 1.25 1.5 2 (chosen one underlined).
- **Resume:** position + speed saved every 10 s while playing and at once on pause, skip,
  seek, scan, speed change, leaving, app pause. Reopen = 5 s back; a watched title starts at
  0. Speed is remembered per title. Watched at the last 3% / 2 min (`savePosition`) and at
  the end of the file. Can't play / file missing → "Can't play this file." with a back button.
- **Rotate (behind `FeatureFlags.ROTATED_PLAYER`, still OFF):** `ui/quarterTurned` turns the
  whole player box 90° (picture, controls AND touches, width/height swapped). Nothing is
  rebuilt, so playback continues at the same second. Rotate button only shows with the flag
  on; "Start the player in" Portrait / Landscape / Last used is applied then, and every
  rotate saves `lastOrientation`. Flag off = portrait only (the fallback).
- `logic/PlayerMath` (time labels, skip clamping, scan steps, seek fraction, double-tap side,
  start orientation, volume text). Tests: 89 passing (6 new). Lint: only the 4 old warnings.
  Build log: `logs/session6-build.log`.
- **Phone Day checks:** turn ROTATED_PLAYER on and test rotate mid-movie (no restart, no lost
  second, touches match the turned picture); immersive full screen and whether the camera
  cutout shows a bar; skip/scan feel; volume bar (it stays portrait in landscape — fine?);
  wheel press / camera do nothing in the player.
- Session 7 still to do: aspect Fit/Fill/Zoom + pinch, audio/subtitle pickers and drawing
  subtitles, lock button, 90 s skip-ahead, previous/next, sleep timer, next-up countdown,
  Details overlay, sync nudges.

## Session 7 — Player extras (2026-10-12) ✅

- **Layout:** top row back · title · **Details** · rotate; middle play/pause with skip
  buttons, then **‹ Previous · +90s · Next ›** (previous/next film in the collection or
  episode in the show, lighter when there is none); seek bar; **Speed · Audio · Subtitles**;
  **Fit/Fill/Zoom · Sleep · Lock**. A panel opens in the middle area (scrolls if tall);
  its button is underlined. Code: `PlayerScreen.kt` (state + actions) and `PlayerPanels.kt`.
- **Aspect:** button cycles Fit → Fill → Zoom (Zoom = Fill × 1.33); pinch zooms 1–4× on top.
  Both saved per orientation (`aspects`, `pinches` in settings.json). Changing the mode
  resets the pinch. `logic/Aspect`.
- **Subtitles:** Off / tracks inside the file ("(in file)") / `movie*.srt` next to the video
  ("English (.srt)"). The app reads .srt itself (`logic/Subtitles`), so their **sync nudge**
  (100 ms steps, ±10 s) moves lines earlier or later. Tracks inside the file come from
  ExoPlayer as they play, so they can only be held **later** (`DelayedText`); the panel says
  so. Size (S/M/L/XL) and Position (Bottom / Raised / Top) in the panel and in Settings.
  Drawn white on a dark box. Subtitles start Off for every title.
- **Audio track picker** (name or language + codec).
- **Audio/video sync nudge: skipped.** The SDK player has no audio-offset control and
  ExoPlayer has no simple public one; it would need an SDK change. Say if wanted.
- **Lock:** hides the controls; a tap shows "Controls are locked" + Unlock for 3 s. Volume
  keys and headset buttons still work.
- **Sleep timer:** Off / 15 / 30 / 45 / 60 min / End of this one. The button shows "Sleep
  12m". When it runs out: save + pause. "End of this one" also stops next-up.
- **Next up:** at the end of a collection film or episode: "Up next · title · Starts in 10 s"
  with Play now / Cancel. It plays in the same player (no new screen), saving the old one
  first and marking it watched.
- **Details panel:** video codec, picture size (1080p), frame rate, video bitrate when the
  file says, file average bitrate, audio codec, file size. `logic/TechDetails`.
- **Headphones/Bluetooth:** pause on disconnect is the SDK player's
  `setHandleAudioBecomingNoisy(true)` (already on). Headset/Bluetooth buttons: play/pause,
  hook, next/previous (= skip forward/back), fast-forward/rewind handled in
  `PlayerScreen.onKeyDown`. **Phone Day check:** without a MediaSession Android may not
  deliver these keys to the app; if not, propose an SDK change (media session in the SDK player).
- **Can't play:** missing file, player error, or an exception while opening → "Can't play
  this file." with a back button. A broken .srt is skipped.
- **Settings:** new "Subtitle position" and **Storage**: "Watch uses 23.4 GB of its 50.0 GB
  (47%) · 12.1 GB free on the phone" (adds up every file under /sdcard/Watch, off the main
  thread; `storage/StorageUsage`).
- Polish: player text buttons are 48 dp+ tall, panels use the Reader rows, spacing evenned
  out in the player. Library screens were not re-spaced (no phone to judge on).
- Tests: 98 passing (9 new in PlayerExtrasTest). Lint: 0 errors, 5 warnings (4 old +
  `UsableSpace` hint, harmless). Build log: `logs/session7-build.log`.
- **Phone Day checks:** pinch feel; Zoom amount; subtitle sizes readable; headset/BT buttons
  reach the app; pause on unplugging headphones and on Bluetooth off; next-up countdown;
  Details numbers; storage line speed with 50 GB of files.

## Session 8 — PC side part 1: Watch-Phone-Sync.cmd (2026-10-12) ✅

- **Files:** `scripts/Watch-Phone-Sync.cmd` (double-click) runs `scripts/Watch-Phone-Sync.ps1`
  (Windows PowerShell 5.1, ASCII only). Log of every run: `logs/sync-<time>.log`.
- **ffmpeg** was already installed (winget Gyan.FFmpeg, 9.0), so nothing was installed. The
  script finds ffmpeg/ffprobe on PATH or in WinGet\Links; if missing it prints the winget line.
- **D:\TV** did not exist: the script made it with `README.txt` (naming from CLAUDE.md §8).
- **Scan (read only):** every video under D:\Movies. Collection = the folder the file is in
  (top-level files = `_Singles`); `NN - ` prefix = collectionNumber; `Title (Year)` from the
  name; ` - ` in a name becomes `: ` in the title ("2001: A Space Odyssey"). Phone folder keeps
  the file's spelling. **Note:** loose films in a category folder (Christmas/, Family/) get that
  folder as their collection, e.g. Coco -> "Family"; Family/Harry Potter -> "Harry Potter".
  TV: one menu row per season, `SxxEyy` from the file name, episode title after it.
- **Menu:** numbered list with sizes, `[x]` picked, "(on phone)" tag. Numbers/ranges toggle,
  words search (every word, any order, accent/case-insensitive), A/N/S/C/D/Q. Running total =
  already on phone + picked, against the 50 GB cap; a pick that would go over is refused.
  Free space on the phone shown (`df`).
- **Quality:** Original / 1080p (3850k+160k) / 720p (1850k+128k). Shrinks only files taller
  or >20% bigger than the target. Estimate before sending, real size measured after each file
  (warning if >15% over, title skipped if it would break the cap).
- **Library check (ffprobe):** non-MP4/MKV container (AVI), non-H.264/HEVC/VP9 picture
  (Xvid/mpeg4), 10-bit picture, AC3/E-AC3/DTS/TrueHD/PCM sound, cover picture stored as a
  video track, picture subtitles (dropped, noted). **Fixes**, on copies in `%TEMP%\watch-*`:
  `-c copy` remux, sound-only to AAC stereo, x264 re-encode only when the picture needs it or
  when shrinking, cover -> `poster.jpg` and stripped (`-map 0:v:<main> -map 0:a?`). Text
  subtitles kept. Every write goes through `Assert-SafeTarget`, which refuses D:\Movies/D:\TV.
- **Send:** exactly one device required; phone route = `adb push` to `/data/local/tmp/watch-xfer`,
  `cp` to `name.part`, `mv` (poster, video, then json last). Already-there titles are replaced
  only after a yes (positions/watched stay in .state). Writes `.state/last-sync.txt`.
  No phone: explains the 5 steps and stops. `-Check <files...>` = check only, nothing written.
- **Dry run:** `WATCH_DRY_RUN=<folder>` writes `<folder>\Watch\...`. Tested on a small
  synthetic library (AVI+mpeg4+AC3, MP4 with cover+AC3 at 1080p, 10-bit H.264 MKV, good MP4,
  2 TV episodes): layout matches §4, fixed files are H.264 8-bit + AAC with one video track,
  sources byte-identical afterwards, temp folder removed. All 325 real names parse.
- **Check of 10 real files:** 3 AVIs (Pokemon 1, Project X, Beethoven's 2nd) = Xvid picture,
  need a re-encode (Beethoven's 2nd also AC3). A Quiet Place, Hacksaw Ridge, Watchmen,
  Inception = AC3 sound + cover track. Casino, 12 Strong, Harry Potter 1 = cover track only.
  Nearly every MP4/MKV has an embedded cover, so almost all titles get a quick remux.
- Tests: 99 passing (1 new: the app reads the script's PowerShell-style movie.json).
  Build log: `logs/session8-build.log`.
- Session 9: TMDB lookup + cache, Remove menu, Backup/Restore, permission grant.

## Session 9 — PC side part 2: TMDB, Remove, Backup/Restore, grant (2026-10-12) ✅

- **Main menu** in `Watch-Phone-Sync.cmd`: 1 Send, 2 Remove, 3 Backup, 4 Restore, 5 Grant
  file access, Q. No phone = explains all five and stops. ffmpeg is only needed for Send.
- **TMDB** (PC only): key read from `private/tmdb-key.txt`, never printed; error text is
  scrubbed of it (checked: the key appears in no log). v3 key -> `api_key`, long v4 token ->
  Bearer header. Every API answer and picture is cached in `private/tmdb-cache/` (looked up
  online once). Match = search by title + year (then without year), year within 1, exact name
  preferred; a title with no year needs an exact name. No internet / bad key: says so once
  and carries on without TMDB.
- **Saved into movie.json:** overview, genres, runtime (file length wins; TMDB when the file
  can't say), rating (vote average, 1 decimal), US content rating, director(s), top 10 cast,
  collection, poster (w780) + backdrop (w1280). **Collection rule:** a numbered folder
  (`01 - ...`) keeps its folder name (story order); otherwise TMDB's collection beats a
  category folder (Coco -> "Coco Collection"), else the folder stays. Title stays Jordan's
  spelling ("Sorcerer's Stone", not TMDB's "Philosopher's").
- **show.json:** overview, genres, rating, content rating, cast, poster, backdrop.
  **Episode json:** TMDB overview, air date, runtime, still (`S01E02.jpg`, w300); the file's
  episode title wins unless it's just "Episode N".
- **Posters:** TMDB poster when matched; the cover stored in the video only when TMDB has
  no match/no poster (the cover is still stripped from the video either way).
- **adb handoff / last-sync.txt:** already in Session 8 (push to /data/local/tmp, cp as
  `.part`, mv). last-sync.txt is now also written after Remove.
- **Remove:** lists what's on the phone with sizes (one `du` call), pick/search like the send
  menu, confirm, `rm -rf` the title/season; empty collection folders and shows with no seasons
  left go too. `.state` is never touched, so a title sent again resumes.
- **Backup:** pulls `.state` (not .tmp/.part) to `Documents\Watch-Backups\state-<time>`.
  **Restore:** pick a backup, it backs up the current state first (`-before-restore`), stops
  Watch (`am force-stop`), then sends each file with the `.part` -> rename route.
- **Grant:** checks Watch is installed (`pm path`), runs `appops set com.jordan.watch
  MANAGE_EXTERNAL_STORAGE allow`, reads it back to confirm.
- **Dry run** (5 synthetic 4-second movies named after real films + made-up show "The Gravel
  Pit Gang"): Coco, Hacksaw Ridge, Harry Potter 1, Inception matched with poster, backdrop and
  details; "Zzyzx Moonbeam (2031)" no match -> embedded cover as poster; the show had no match
  -> plain show.json, episode titles from file names. Remove/Backup/Restore tested on a copy
  (positions.json kept after removing a title; restore brought the old file back). Extra check:
  "The Office (2005)" S01E02 matched with title, overview, air date and still.
- Tests: 99 passing (no app code changed; Settings already shows the TMDB line).
  Build log: `logs/session9-build.log`.
- **Phone Day checks:** run the real send of a few titles over adb; Remove/Backup/Restore/Grant
  against the real phone; `du`/`find` output format on LightOS.

## Session plan

| # | Session | Done when |
|---|---|---|
| 0 | Recon, PROGRESS.md, .gitignore | ✅ this file committed |
| 1 | Clean slate: id/label, empty Watch tool, FeatureFlags, SDK video player (decision a) | builds; tool id is com.jordan.watch |
| 2 | Logic: json models, folder-name parsing, sort/filter/search, resume + watched math (tests) | unit tests pass |
| 3 | Storage: file access screen, scanner, rescan cache, atomic `.state` files | scanner tests pass |
| 4 | Library UI: tabs, poster grid/list, poster cache, Continue Watching | builds |
| 5 | Detail, TV show/season/episode, Collections, Remove confirm, sort/filter/search screens | builds |
| 6 | Player core: ExoPlayer, full screen, controls, seek, speed, resume, rotate (decision c) | builds |
| 7 | Player extras: tracks, subtitles + nudges, aspect/zoom, sleep timer, next up, volume keys, details overlay | builds |
| 8 | PC script: Watch-Phone-Sync.cmd, menus, 50 GB cap, ffmpeg checks/fixes, dry run | dry run writes correct layout |
| 9 | PC script: TMDB lookup + cache, Remove menu, Backup/Restore, permission grant; Settings + TMDB line | dry run with TMDB data |
| 10 | Phone Day: install, reboot, test everything, flip confirmed FeatureFlags | ✅ checklist passed on phone |

## Session 10 — Phone Day (2026-10-12) ✅

- Built (99 tests passing), `adb install -r` as `com.jordan.watch` (Jordan's other apps all
  still installed), rebooted. Watch appeared in the menu; no-access screen shown first.
- `Watch-Phone-Sync.cmd` on the real phone: Grant (appops reads back `allow`), Send of Ted
  (2012, 702 MB) and Harry Potter 1 (2.3 GB), both with library-check remux, posters,
  backdrops, json. `.state` files written atomically (no `.tmp` left behind).
- **Passed on phone:** library + posters, Collections tab, detail page + quality line, sort
  A–Z/Z–A/Year, smooth scroll, dark theme; full screen; rotate button both ways without
  restart; no auto-rotate when tilting; "Start in: Landscape"; seek + time label; 15 s skip;
  double-tap skip; hold-to-scan; +90 s; speed 0.25x–3.0x pitch-corrected; resume (back and
  Home) with 5 s rewind and per-title speed; Continue Watching bar; volume keys + bar in
  player and library; both files play.
- **FeatureFlags:** `ROTATED_PLAYER` -> **ON** (proved on phone). Before it was on, the "Start
  in: Landscape" setting did nothing; that was the flag, not a bug. `FAKE_LIBRARY` stays off
  (debug-only test data, not a phone ability).
- **Still broken:** nothing found.
- **Not yet tested on the phone** (worked in builds/dry runs; check when convenient):
  TV show/season/episode (no episode sent yet); next-up countdown (needs a second film in a
  collection); subtitles + sync nudge; audio track picker; aspect Fit/Fill/Zoom + pinch;
  lock controls; sleep timer; Details overlay; pause on headphone/Bluetooth disconnect and
  headset buttons; watchlist / mark watched / Remove-from-phone screen; filters + search;
  PC Remove, Backup and Restore against the real phone.
- Build log: `logs/session10-build.log`. Tagged **v1.0**.

## Session 11 part A — Every video Watch-safe (2026-10-12) ✅

- **Decoders:** `adb shell dumpsys media.codec` doesn't exist on LightOS ("Can't find service");
  the same list comes from `dumpsys media.player`, saved to `phone_day/decoders.txt`.
  Hardware: H.264 Baseline/Main/High up to level 5, 1920x1088 max; HEVC **Main only (8-bit)**
  up to 1080p; VP9 1080p. Software only: AV1, VP8, MPEG-4, H.263. Sound: AAC (hardware, up
  to 6 channels = 5.1), MP3, Opus, Vorbis, FLAC, ALAC, APE, WMA, AMR. **No AC3/E-AC3/DTS/TrueHD.**
  Nothing above 1080p, no 10-bit.
- **Phone Day failures:** none (Ted and HP1 played after the old library check). The
  Watch-safe rule is preventive, built from the decoder list.
- **Watch-safe** (CLAUDE.md §8): faststart MP4, one H.264 8-bit 4:2:0 track ≤1920x1080 (level
  ≤5.1), AAC LC/HE stereo or 5.1 (7.1 → stereo), no cover/subtitle/data tracks, text subs as
  sidecar `.srt`. Rules live in `scripts/WatchSafe.ps1` (dot-sourced; pure, no ffmpeg calls).
- **Watch-Phone-Sync:** every file goes through the rules. Already safe → sent untouched
  (checked: same MD5). Otherwise converted in `%TEMP%`: remux only / sound only / picture
  re-encode, all with `-movflags +faststart -sn -dn -map_chapters -1`. Output is always
  `movie.mp4` / `SxxEyy.mp4` (no more MKV on the phone). Original / 1080p / 720p and the 50 GB
  cap unchanged; Original now also caps the picture at 1080p.
- **ffprobe gate before sending:** Watch-safe, exactly one picture track, length within 1 s of
  the original; otherwise "NOT SENT - <reason>." and the title is skipped. movie.json codec /
  width / height now come from the probed file.
- **Tests:** `scripts/tests/Test-WatchSafe.ps1` — 53 rule tests (safe files, every conversion
  reason, channels, the send gate incl. the 1 s limit, .srt names, real MP4 box order).
  Kotlin: unchanged, all passing. Dry run on synthetic files (faststart H.264 = untouched;
  1440p 10-bit HEVC MKV + AC3 5.1 + srt + cover; AVI MPEG-4 + MP3; 7.1 AAC with index at end;
  E-AC3 episode) → all five Watch-safe, srt sidecar made. Build log `logs/session11-build.log`.
- **Not done here:** the new source folders (`D:\Marvel Entertainment`, duplicates) Jordan
  added to CLAUDE.md §8 — that's part B. Real send of a converted title to the phone.

## Session 10b — More source folders (2026-10-12) ✅

- **Sources:** `Watch-Phone-Sync.ps1` scans `$SourceRoots` (top of the script):
  `D:\Movies`, `D:\Marvel Entertainment`, `D:\TV`; missing ones are skipped.
  `WATCH_SOURCES=a;b` replaces the list for tests (old `WATCH_MOVIES_ROOT` / `WATCH_TV_ROOT` removed).
- **Rules:** `SxxExx` in the name = TV episode, grouped by show + season. Show = the
  `Show (Year) - Season N` folder, else the folder above `Season N`, else the show folder
  (Agents of S.H.I.E.L.D. Slingshot has no season folder), else the file name. The year keeps
  Fantastic Four (1978) and (1994) apart; Loki S1 (Phase Four) and S2 (Phase Five) join one show.
  Everything else = movie. A per-title folder (`01 - Iron Man (2008)\Iron Man (2008).mp4`)
  gives the story number. Collection = nearest folder that isn't a plain sorter (Films,
  Series & Specials, Duology...), with its parent added below the top level
  ("Spider-Man (Sony) - Tobey").
- **Looked inside first:** Marvel Studios Assembled = `NN - Assembled - The Making of X.mp4`
  (numbered, no year); Marvel Studios Legends = `Thor.mp4` etc. (no number, no year). Both
  become movies in their own collection. Titles with no year skip TMDB (a test matched
  Legends "Apocalypse" to an unrelated 2026 film).
- **Duplicates:** same title + year (or show + season) in two places is listed at start
  ("also elsewhere" in the menu); the menu refuses to pick the second copy. 7 found today
  (6 Spider-Man films, The Incredible Hulk).
- **Speed:** 478 movies + 109 seasons from ~1,880 files. First scan 7 s; after that the
  saved scan (`private\scan-cache`, reused until any folder's time changes) loads in 2 s;
  menu draw 1.5 s.
- **Fix found in the dry run:** an MKV with picture subtitles was refused ("1,314.8 s vs
  1,319.4 s") because its subtitle tracks run past the picture. The length check now falls
  back to the original's picture/sound end (last packets, read with ffprobe).
- **Dry run** (copies of Marvel Studios Legends\Apocalypse.mp4 and Iron Man (1994) S01E01.mkv
  in their Marvel folder layout): both sent, Watch-safe, json + TMDB show/still. Source files
  checked unchanged (MD5 and size/time of all 1,555 Marvel files).
- Tests: Test-WatchSafe 53/53; Gradle build + unit tests pass (`logs/session10b-build.log`).
