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
| 10 | Phone Day: install, reboot, test everything, flip confirmed FeatureFlags | checklist passed on phone |
