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
