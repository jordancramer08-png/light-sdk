# NOTES.md — Listen

Session-by-session notes for the Listen tool. `CLAUDE.md` says what the app does,
`PLAN.md` says how it's built. The older SDK recon below the line is still accurate
reference for the SDK's UI kit, screens and sandbox rules.

## Final summary (2026-09-30): Listen 1.0.0 is complete

### What the app does

Listen is a music and audiobook player for the Light Phone III. It replaces the fenleon
Audiobooks app.

- **Music:** Songs, Artists, Albums and Playlists, read from the files' own tags with album
  art. Every list sorts A–Z or Z–A and remembers the choice. Albums are grouped by album
  artist, and they play in disc, then track order, gaplessly. Playlists are live: an artist
  or album entry picks up music sent later. Music removed from the phone shows greyed as
  "Not on phone" and comes back when the music does.
- **Audiobooks:** Continue listening at the top, then the library grouped by author and
  series. Each book has its own place, speed (0.75–2.0×, natural pitch) and finished
  state. It rewinds a little after a pause (amounts in Settings). Chapters, a sleep timer,
  −15 / +30 buttons.
- **Now Playing:** one player for both. Playback keeps going with the screen off or in
  another tool, and works from the notification and Bluetooth/headset buttons. It pauses
  when headphones disconnect. A small line shows the file's format, bitrate and sample rate.
  Music and books keep separate resume points.
- **Settings** (gear on Home): color theme, audiobook rewind amounts, audio offload (off by
  default; it saves battery, and Listen turns it off by itself after a playback error).
- Files are played untouched (no EQ, effects or resampling). A file that can't be played
  shows "Can't play this file" and is skipped.

### Where your playlists and positions are stored

Everything you'd hate to lose is on the phone's shared storage, **not** inside the app, so an
uninstall or a new signing key can't wipe it. The PC script backs it up (option 7) and can
restore it.

| What | File on the phone |
|---|---|
| Playlists | `/sdcard/Listen/.state/playlists.json` |
| Audiobook places, speeds, finished flags | `/sdcard/Listen/.state/book_positions.json` |
| Music resume point (queue, song, position, shuffle, repeat) and which book was loaded | `/sdcard/Listen/.state/music_state.json` |
| Settings and sort choices | `/sdcard/Listen/.state/settings.json` |
| Last sync time (written by the PC script) | `/sdcard/Listen/.state/last-sync.txt` |

The music lives in `/sdcard/Listen/Music/` and the books in `/sdcard/Listen/Audiobooks/`,
put there by `Listen-Phone-Sync.cmd`. The tag index and artwork thumbnails are a cache inside
the app (`files/cache/`). An uninstall throws them away, and Listen rebuilds them on its own.
Every state file is written safely (to `name.tmp` first, then renamed). A broken
playlists file is set aside as `playlists.broken-<time>.json`, never overwritten.

### How to rebuild and reinstall from scratch

On the Windows laptop, in PowerShell:

1. Get the code (skip if the folder already exists):
   ```powershell
   cd $HOME\Documents
   git clone https://github.com/jordancramer08-png/light-sdk listen
   cd listen
   git checkout listen
   ```
   Needs Java 17 (Temurin, `JAVA_HOME` set) and the Android SDK at
   `%LOCALAPPDATA%\Android\Sdk`. `local.properties` (not in git) holds one line,
   `sdk.dir=C\:\\Users\\bjc38\\AppData\\Local\\Android\\Sdk`. Copy it from the old folder or
   create it with that line.
2. Plug in the phone (USB debugging on) and check it shows up: `adb devices`
3. Build and install, one of:
   - Double-click `scripts\Build and Install Listen.cmd` (it builds, installs and reboots), or
   - by hand:
     ```powershell
     .\gradlew.bat :tool:assembleRelease
     adb install -r tool\build\outputs\apk\release\tool-release.apk
     ```
   The release build is optimized; it loads the library about 40× faster than a debug build.
   Both are signed with the repo's dev key (`sdk/keys/lightsdk-dev.jks`), so `install -r`
   always upgrades in place and keeps everything.
4. **First install on a phone only:** reboot the phone so the launcher shows Listen. Then run
   `Listen-Phone-Sync.cmd` and choose **9** to grant All files access (without it Listen
   shows a screen saying so).
5. To bring back playlists and positions after a reset, restore the `.state` backup with the
   PC script.

Tests (no phone needed): `.\gradlew.bat :tool:testDebugUnitTest` (70 tests, including
`SpeedCheckTest` for a 1,400-song library and a 560-file book).

## Session 7 (2026-09-30): audio quality and polish — works on the phone

Committed as "SDK: expose the playing track's format and an audio offload switch" and
"Session 7 - audio quality and polish". (Jordan unplugged mid-session once; the notes below
were written then and on resuming.)

**Finished**
- Quality checks against CLAUDE.md, confirmed in code:
  - Untouched output: no EQ/effects/normalization anywhere; `LightAudioService` builds a
    plain ExoPlayer.
  - Gapless: one player with a real playlist (`setMediaQueue`), never recreated per track.
    The default extractors read LAME/Xing and iTunSMPB gapless data.
  - Own sample rate: no forced resampling. Speed changes only resample for books at ≠1.0×.
  - Unplayable files: `PlaybackHub.onError` shows "Can't play this file" and skips, with a
    loop guard.
- SDK commit `5692f15` "SDK: expose the playing track's format and an audio offload
  switch": `LightAudioPlayer.currentFormat` (`LightAudioFormat`: codec, container, bitrate,
  sample rate, channels) and `setAudioOffload(enabled)` (gapless support required). Checked
  in the Media3 1.10.1 jar that both are bundled across the MediaController. 2 new SDK tests
  (13/13 pass).
- Uncommitted app changes:
  - `playback/FormatLine.kt` + `QualityLine` on both Now Playing screens ("MP3 · 190 kbps ·
    44.1 kHz"). If the file has no bitrate, it's worked out from size/length.
  - Audio offload: `ListenSettings.audioOffload` (default false). `PlaybackHub.applyAudioOffload`
    turns it on only at 1.0×. A playback error while offload is on switches the setting off
    and retries the same file ("Audio offload switched off").
  - Settings screen (`settings/SettingsScreen.kt`, `settings/ThemeScreen.kt`,
    `ui/SettingRows.kt`): theme, rewind short/long steppers, audio offload. The theme is now
    saved in settings.json (`ListenSettings.theme`); `ListenThemeController` was removed.
  - Speed: `MusicLibraryState.albumsInArtistOrder` and `artistKey()` caches for playlist
    artist entries. `plainLowercase()` fast ASCII path in `sortKey`/`groupKey` (same results;
    test added in `SortKeysTest`). Timing log lines ("Music: … in N ms") in MusicLibrary and
    BookLibrary.
  - `SpeedCheckTest` (1,400 songs / 270 albums, 560-file book) and `FormatLineTest`.
    All 70 app tests pass.
  - Polish: sort/choice rows are one line. Music Now Playing no longer flashes "Nothing is
    playing" right after "Resume music".
  - Version 1.0.0 (7) in `tool/lighttool.toml`.
- Phone speed results (1,422 songs): the debug build took 6.5 s to show music, 3.8 s after
  the key fix. The **release build takes 82 ms** (books 3 ms). The release build ran fine on
  the phone: it restored the book, showed art and library counts.

**Resumed later the same day (phone plugged back in)**
- `scripts/Build and Install Listen.cmd` now builds and installs the **release** APK
  (`assembleRelease` → `tool/build/outputs/apk/release/tool-release.apk`). It uses the same dev
  key, so it upgrades in place over debug builds.
- Home: Settings is a gear icon in the top bar. Home and Music home menus scroll instead of
  squashing rows. Dividers stop at the scrollbar gutter on these two screens.
- Settings: rewind labels are "Short pause" / "Long pause" (they were cut off), with a note
  that a long pause is 10 minutes or more.
- The quality line uses `LightTextVariant.Superfine`. The SDK's `Fine` is 25 sp, larger than
  `Detail`.
- Checked on the phone (release build): Home, Settings, and Book Now Playing showing
  "MP3 · 128 kbps · 44.1 kHz". Playback played at 1.25× and paused with no errors. Music
  shows from the index in about 85 ms.
- settings.json on the phone had `"audioOffload": true`, set by hand on the phone (the default
  is false). Left as Jordan set it.
- All 70 app tests pass. Jordan ran the final checklist on the phone and it passed.

**Done.** Committed, pushed, and the final summary is at the top of this file. The
"Music: … in N ms" / "Books: …" timing lines stay in logcat (tag `Listen`); they're cheap and
useful if the library ever feels slow.
- Known and left as is: music_state.json is about 117 KB with a 1,400-song queue and is
  rewritten every 5 s while playing (encoding takes about 3 ms). Settings load in the
  background, so a non-Dark theme can flash Dark for a moment on launch.

## Session 6 (2026-09-30): audiobook playback and positions — works on the phone

**Built**
- SDK change (own commit, "SDK: let a detached player switch its audio usage"):
  `LightAudioPlayer.setUsage(usage)` sends a custom session command (`setUsageCommand` in
  `DetachedConnectionHints.kt`); `LightAudioService` offers it to tool controllers in
  `onConnect` and applies it in `onCustomCommand` (`adoptUsage`). One player now plays music
  (`Music`) and books (`Speech`). Speed uses the SDK's `speed` (`PlaybackParameters(speed)`,
  pitch 1.0, so pitch-corrected).
- `PlaybackHub` now handles books too:
  - `playBook(book, chapter?)` resumes from the saved place, rewound by `rewindMs`. It starts
    over when the saved place is in the last 30 s, and when the book is already loaded it
    just plays or jumps.
  - Book controls: `skipBook(±ms)` (across files via `locate`), `previousChapter` /
    `nextChapter` / `seekToChapter`, `setSpeed`, `startSleepTimer(minutes | null = end of
    chapter)`, `cancelSleepTimer`, `startBookOver`, and `resumeMusic()`.
  - New flows: `chapters`, `chapter` (current index), `speed`, `sleep`, `musicSpot`.
  - Saves: music every 5 s, book every 10 s (`BookPositions.record`), plus on pause, seek,
    chapter change, background (`saveNow`) and when the service is released.
  - The book place's `lastPlayedAt` only moves while playing or when the place changed, so
    the rewind knows how long a pause really was.
  - `finished` = last 30 s (index lengths, or the player's length on the last file); a
    manual "Mark finished" is kept until the place moves.
- Auto-rewind: no rewind for pauses under 2 s (`REWIND_MIN_PAUSE_MS`, because seeks briefly
  pause), 3 s under 10 minutes, 10 s after that. The amounts are `ListenSettings.rewindShortSeconds` /
  `rewindLongSeconds` (settings.json only, no screen yet). It runs in `play()`, or in the
  isPlaying collector when resumed from the notification or a headset.
- Separate resume points: `MusicState.book` (music_state.json) = id of the book loaded on top
  of the music spot. On launch `restoreInto` reopens that book paused (else the music).
  Music writes go through one IO thread (`io`) so they stay in order.
- Screens: `BookNowPlayingScreen` (chapter seek bar, "Chapter N of M · 3:12:05 of 11:40:00",
  ⏮ −15 ⏯ +30 ⏭, speed and sleep buttons), `ChaptersScreen` (opens at the current chapter,
  tap to jump), `ui/ChoiceScreen` (speed, sleep). `openNowPlaying()` opens the book player
  while a book is loaded. Chapter rows on `BookScreen` play from there, and Play reads
  "Resume" when a book is in progress. Music home shows "Resume music" while a book is loaded.
  `SeekBarWithTimes(position, duration, onSeek)`, `ControlButton`, `CenteredLine`,
  `FramedPlaceholder` and `ChoiceRow` are now shared.
- Version 0.6.0 (6). 7 new JVM tests in `tool/src/test/.../books/BookPlaybackTest.kt`.

**For Session 7 (quality + polish)**
- Add the Settings screen: rewind amounts (already in `ListenSettings`), audio offload
  (default off; only at speed 1.0), theme.
- The format/bitrate line needs the SDK player to expose the current track `Format`
  (PLAN.md section 1). Add it in an "SDK: ..." commit like the others.
- The sleep timer isn't saved, so it ends if Listen's process is killed. Loading music cancels
  it (`leaveBook`), and so does loading a different book.
- The fenleon Audiobooks app can now be uninstalled if Jordan wants (Listen's book playback is
  proven).
- `resumeMusic` loads on a background thread, so Music Now Playing can show "Nothing is
  playing" for a moment right after "Resume music".

## Session 5 (2026-09-29): audiobook library — works on the phone

**Built** (all in `books/`)
- `Book.kt`: `Book` (id, folder relative to `Audiobooks/`, title, author, narrator, series,
  `seriesNumber: Double?`, year, `coverFile`, files) and `BookFile` (path relative to the
  book folder, label, size, mtime, `durationMs`, m4b `chapters`, tag title/artist).
- `BookJson.kt`: tolerant field-by-field parse of book.json (BOM stripped, numbers as text
  OK, blank = missing, `..` paths refused). Broken file → null → folder fallback.
- `BookScanner.kt`: a book = a folder with book.json or with audio files (not searched
  deeper). book.json's files in its order, missing ones skipped; none on phone → every audio
  file in natural order. Fallbacks: title = folder name ("02 - X" → number 2, title X),
  author = `Audiobooks/<Author>/…` folder, then tag; series = the folder above the book.
  Labels: json, else title tag (unless every file has the same tag), else file name.
  File lengths come from `TagReader` and are re-read only when path/size/mtime change.
- `BookLibrary.kt`: app-wide, like `MusicLibrary`: index cache `filesDir/cache/book_index.json`
  (`BookIndex.VERSION` = 1), same stamp idea (last-sync.txt + folder mtimes), publishes as each
  book is read. Duplicate ids: first one wins.
- `BookPositions.kt`: store for `/sdcard/Listen/.state/book_positions.json`
  (`{version, books: {id: {fileIndex, positionMs, speed, lastPlayedAt, finished}}}`), same
  pattern as `Playlists` (mtime check, broken file moved aside, no write before load). Only
  `markFinished` / `startOver` write it so far.
- `BookProgress.kt`: `chaptersOf` (m4b marks or one per file, blank → "Chapter N"),
  `bookPositionMs`, `isFinished` (flag or last 30 s), `isInProgress`, `percentListened`,
  `chapterIndexAt`, `chapterSpotText` ("Chapter 12 of 40, 14:32 left in chapter"), `bookLengthText`.
- `BookGroups.kt`: `libraryEntries` (series of 2+ books by the same author → one row, in
  `SERIES_ORDER`; Title / Author / Recently played, Z–A reverses only the main field;
  never-played last), `continueListening`, `seriesBooks`.
- Screens: `BooksScreen` (Continue listening rows tinted, then entries; sort saved as
  `ListenSettings.bookSort`), `BookSeriesScreen`, `BookScreen` (header, progress, Play/Pause,
  Mark finished, Start over, lazy chapter list with ▶ on the current chapter).
- Shared changes: `ArtSource.folderFirst` (book.json cover before embedded art) and
  `Book.artSource()`; `SortField.directions` ("newest first / oldest first"); `ArtAndText`
  takes a `modifier`; Home's Audiobooks row is live.
- `PlaybackHub.playBook(book)`: saves the music spot, sets `queue = null` and `book = book`,
  plays file 0 from 0. While a book is loaded nothing writes music_state.json, and music's
  shuffle/repeat are kept aside (put back by `playSongs`). `openNowPlaying()` opens the book
  screen while a book is loaded. `PlaybackHub.index` is now public.
- Version 0.5.0 (5). 9 new JVM tests in `tool/src/test/.../books/BooksTest.kt`.

**For Session 6 (audiobook playback)**
- Nothing saves book positions while playing yet, so Continue listening / Recently played
  only fill once Session 6 writes `BookPositions` (every 10 s + pause/seek/chapter/background,
  set `lastPlayedAt`). Add the writers to the `BookPositions` object; keep the file format.
- `playBook` always starts at file 0 and the player still uses `LightAudioUsage.Music`;
  switch to Speech attributes, resume from the saved spot with auto-rewind, and restore the
  book after the service restarts (today a restart restores music and clears `book`).
- Chapter rows on `BookScreen` aren't tappable yet; `BookChapter` has `fileIndex` + `startMs`
  ready for "play from this chapter". The book screen's header is one lazy item taller than
  a chapter row, so its scrollbar is slightly approximate.
- The Book Now Playing screen doesn't exist yet; the bottom bar shows book title + file label.

## Session 4 (2026-09-29): playlists — works on the phone

**Built**
- `playlists/Playlist.kt`: `PlaylistEntry(kind, path, artist, album)` with kinds `song`
  (path relative to `Music/`), `album` (album artist + album), `artist`. An unknown kind is
  kept and matches nothing. `matchKey` compares entries like the library does (`groupKey`,
  `albumKey`), so adding the same music twice does nothing. `Playlist(id, name, entries)`
  with `adding` / `removing` / `removingAt` / `moving`.
- `playlists/PlaylistExpander.kt`: `matchEntry` / `matchEntries` / `playlistSongs` expand
  entries against the *current* `MusicLibraryState` (album = track order; artist = albums
  whose album artist matches, plus songs they perform on other albums, in
  `ARTIST_ALBUM_ORDER`). Duplicates play once, first place wins. Empty match = "Not on phone".
- `playlists/Playlists.kt`: app-wide store for `/sdcard/Listen/.state/playlists.json`
  (pretty JSON, atomic write, one IO thread). `load()` runs from `ListenScreen.willShow()`
  and re-reads only if the file's mtime changed (a PC restore). Changes are ignored until
  loaded, so an empty list is never written over real playlists. A broken file is renamed
  to `playlists.broken-<time>.json`, never overwritten. `removeAt`/`move` take the expected
  entry so a stale tap can't hit the wrong row.
- Screens: `PlaylistsScreen` (+ new, pencil rename, bin delete), `PlaylistScreen` (count ·
  length, Play/Shuffle, entries; pencil = edit mode with up/down/remove), `PlaylistEntryScreen`
  (songs an album/artist entry covers; tapping plays the playlist from there),
  `AddToPlaylistScreen` (toggle per playlist + "New playlist…"), `PlaylistNameScreen` and
  `DeletePlaylistScreen` (from the Reader).
- "Add to playlist…": long-press on song/album/artist rows (`songRows`/`albumRows`/
  `artistRows` take `onHold`; `ui/Buttons.kt` `Modifier.tapOrHold` uses `combinedClickable`),
  and "+" in the top bar of the album screen, artist screens and Now Playing
  (`ListTopBar(onAddToPlaylist = …)`). Helpers `ListenScreen.addToPlaylist(song|album|artist)`.
- `ui/Buttons.kt` now holds `ActionButton` (moved from AlbumScreen), `RowIconButton`
  (from the Reader) and `DISABLED_ALPHA`. `MusicLibraryState.song(path)` added.
  `QueueSource.KIND_PLAYLIST` (key = playlist id).
- Version 0.4.0 (4). 9 new JVM tests in `tool/src/test/.../playlists/PlaylistTest.kt`.

**For Session 5 (audiobook library)**
- Long-press has no LP3 haptic tick (the SDK's haptic needs a Context the tool can't reach);
  only Compose's own long-press haptic. Fine so far.
- Playlist screens compute matches on `Dispatchers.Default` with `produceState`; do the same
  for book grouping so the main thread never walks the library.
- Book positions can follow the `Playlists` store pattern (load-once + mtime check,
  single IO thread, move a broken file aside).
- A playing playlist queue doesn't change when the playlist is edited; it's a snapshot of
  the paths (same as album/artist queues).

## Session 3 (2026-09-29): artists, albums, sorting, artwork — works on the phone

**Built**
- `music/Albums.kt`: `groupAlbums` (key = `groupKey(albumArtist)` + `groupKey(album)`,
  case/accent/space-insensitive; songs in `TRACK_ORDER` = disc (0 → 1), track, path) and
  `groupArtists` (by album artist; albums by year, undated last, then title).
  `MusicLibraryState` now carries `albums` and `artists` (grouped on the IO thread in
  `publish`) plus `album(key)`, `artist(key)`, `albumOf(song)`.
- `music/Sorting.kt`: `sortSongs` / `sortAlbums` / `sortArtists`. Only the chosen field
  is reversed for Z–A, so albums stay in track order. Keys use `sortKey()` (drops The/A/An).
- `storage/Settings.kt`: `/sdcard/Listen/.state/settings.json` (`ListenSettings`:
  `songSort`, `albumSort`, `artistSort` as `ListSort(field, descending)`). `Settings.load()`
  runs from `ListenScreen.willShow()`; lists wait for `Settings.loaded` so they don't jump.
  **Add later settings (theme, rewind, offload) to `ListenSettings`.**
- `ui/SortScreen.kt`: generic sort screen (like the Reader's), opened from the top-bar
  ⇅ (`REVERSE_ORDER`) icon via `ListTopBar(onSort = …)`.
- Screens: `AlbumsScreen`, `ArtistsScreen` (both in `AlbumsScreen.kt`), `ArtistScreen`
  (albums + "All songs") and `ArtistSongsScreen`, `AlbumScreen` (cover, details, Play /
  Shuffle, tracks with disc headings). Shared rows in `music/MusicLists.kt`: `songRows`,
  `albumRows`, `artistRows`, `MusicLazyList` (resets to the top when the sort changes),
  `rememberSorted` (sorts on `Dispatchers.Default`).
- `artwork/ArtworkCache.kt` replaces `SongArtwork`: `ArtSize.THUMB` 160 px / `LARGE` 800 px,
  disk cache `filesDir/cache/art/<hash>-<px>.jpg` (a `.none` file = no art), 24 MB memory
  LRU, 2 decodes at a time, one shared load per picture. `artwork/ArtImage.kt`: `ArtImage`
  composable, `Album.artSource()`, `songArtSource(song, album)`. Art is on song rows, album
  and artist rows, the album screen, Now Playing and the now-playing bar.
- `PlaybackHub.playSongs(..., shuffle: Boolean?)`: album Play = shuffle off, Shuffle =
  shuffle on from a random track; tapping a song in a list leaves shuffle as it is.
  `QueueSource` kinds now include `album` and `artist` (key = album/artist key).
- SDK change (in the Session 3 commit): `LightAudioService` resets ExoPlayer's shuffle
  order to start at the current item whenever shuffle is turned on or the queue changes,
  so no songs are skipped with repeat off (fixes the Session 2 shuffle concern). Test in
  `LightAudioServiceTest`.
- Version 0.3.0 (3). 10 new JVM tests (grouping, sorting, settings file).

**For Session 4 (playlists)**
- Album entries match on `albumKey(albumArtist, album)`; artist entries can use
  `groupKey(name)` against both `Artist.key` (album artist) and `groupKey(song.artist)`.
  An artist's albums are already in playlist order (`ARTIST_ALBUM_ORDER`: year, then title).
- Add a `QueueSource.KIND_PLAYLIST` and reuse `songRows` / `albumRows` / `ArtImage` for
  the playlist screens. "Add to playlist…" can go in the `ListTopBar` right slot on the album
  and artist screens (the sort icon only sits on the three main lists).
- The art disk cache is never trimmed. That's fine for about 270 albums (a few MB), but
  consider cleaning it if books add many more covers.
- Book covers (Session 5) can use `ArtSource(key, audioFile, folder, folderImages = listOf(cover))`.

## Session 2 (2026-09-29): playback and music resume — works on the phone

**Built**
- SDK audio changes (in the Session 2 commit, `sdk/client/.../audio/`):
  - Service: `setHandleAudioBecomingNoisy(true)`, `WAKE_MODE_LOCAL` (only if the tool
    declares `WAKE_LOCK`), and tapping the notification opens the tool.
  - `LightAudioPlayer`: `setMediaQueue(items, startIndex, startPositionMs)`,
    `seekTo(index, ms)`, `prepare()`, `setShuffleEnabled`/`shuffleEnabled`,
    `setRepeatMode`/`repeatMode` (new `LightRepeatMode` Off/All/One).
  - The detached controller now uses the application context, so one handle can live
    for the whole process. If the service stops (Media3 stops it when the task is
    swiped away while paused), the controller disconnects and the handle is
    **released**; the tool must open a new one.
- `lighttool.toml`: `capabilities = ["detached-audio"]`, `WAKE_LOCK` added, version 0.2.0 (2).
- `ListenScreen` (base class for every Listen screen): `willShow()`
  refreshes the library and calls `PlaybackHub.attach(...)`; `onAppPause()` saves.
- `playback/PlaybackHub` (app-wide object): owns the one detached player
  (`LightAudioUsage.Music`). `ensurePlayer()` reopens it after a release and restores the
  saved queue **paused**, unless the service still has a live queue. Mirrors player
  state into its own flows (`currentSong`, `isPlaying`, `positionMs`, `shuffle`,
  `repeat`, `message`) so the UI doesn't flicker when the player is replaced.
- `playback/MusicState` + `MusicStateStore`: `/sdcard/Listen/.state/music_state.json`
  (paths relative to `Music/`, index, position, shuffle, repeat, source). Saved every
  5 s while playing, and after pause/skip/seek/shuffle/repeat/background. Skips the
  write when nothing changed. `restorable()` drops songs no longer on the phone.
- `playback/MusicNowPlayingScreen`: art, title/artist/album, tap-or-drag seek bar,
  shuffle / previous / play-pause / next / repeat. Previous restarts the song after 3 s.
- `ui/NowPlayingBar` on Home, Music and Songs. Tapping a song plays the Songs list from
  it and opens Now Playing.
- `artwork/SongArtwork`: embedded picture, then cover/folder.jpg/png, decoded
  downscaled (800 px) off the main thread, small memory LRU. No disk cache yet.
- Unplayable file: "Can't play this file" on the album line, then skip to next
  (stops after the whole queue has failed).
- 9 new JVM tests in `tool/src/test/.../playback/MusicStateTest.kt`.

**For Session 3**
- Replace `SongArtwork` with the planned `ArtworkCache` (disk cache by album key,
  160 px thumbnails for rows); Now Playing can switch to it.
- Album/artist/playlist queues: call `PlaybackHub.playSongs(songs, index,
  QueueSource(kind, key))`. `QueueSource` kinds besides "songs" aren't used yet.
- Watch shuffle: with shuffle on, ExoPlayer's shuffle order may not start the rest of
  the list right after the tapped song, so with repeat off some songs could be missed.
  Jordan hasn't reported it; check before building album Shuffle buttons.
- Speech usage for audiobooks (Session 6) still needs a way to switch attributes on the
  live player; the hub currently always opens a Music player.

## Session 1 (2026-09-29): skeleton, file access, music scan — works on the phone

**Built**
- `tool/` now holds Listen (`com.thelightphone.listen`, label "Listen"). The Bible code
  was removed from this branch (it's still on `main`).
- SDK change, own commit: `MANAGE_EXTERNAL_STORAGE` added to the plugin's permission
  allowlist (`plugin/.../LightToolMetadata.kt`) with a test. The phone granted it through
  `Listen-Phone-Sync.cmd` option 9, and plain `java.io.File` reads `/sdcard/Listen` fine.
- `HomeScreen` (`@InitialScreen`): Music / Audiobooks / Now Playing. It checks
  `Environment.isExternalStorageManager()` in `willShow()` (runs on launch and every
  resume) and shows the "choose option 9" message when access is missing.
- `music/MusicHomeScreen` (Songs live; Artists/Albums/Playlists greyed) and
  `music/SongsScreen` (lazy list, title + artist, A–Z by title).
- Scanner: `MusicLibrary` (app-wide object, background `Dispatchers.IO`, conflated
  request channel) → `MusicScanner` → `TagReader` (MediaMetadataRetriever). Every list
  screen calls `MusicLibrary.refresh(lightContext.filesDir)` in `willShow()`.
- Index cache: `filesDir/cache/music_index.json` (`MusicIndex`, versioned; bump
  `MusicIndex.VERSION` if `Song` changes meaning). Written with `AtomicFile` (.tmp + rename).
- Install script: `scripts/Build and Install Listen.cmd` (copy of the Reader's).
- 19 JVM tests in `tool/src/test/.../music/`.

**How change detection works**
- `MusicScanner.stamp()` = `last-sync.txt` text + CRC of every folder's path and mtime
  under `Music/`. If it matches the stamp saved in the index, nothing is scanned.
- Otherwise every audio file is listed, and tags are re-read only when path, size or
  mtime differ (`planRescan`). The list updates every 50 files during a long scan.
  "Updating library…" shows while that runs.

**Decisions worth knowing**
- Album-artist fallback order is: album-artist tag → Artist *folder* → track artist.
  (PLAN.md said track artist before folder. The folder keeps multi-artist albums together.)
- Missing title → file name without a leading track number ("01 - ", "1-03 ").
- `sortKey()` in `music/SortKeys.kt` drops a leading The/A/An and ignores case and
  accents. Reuse it for every sort in Session 3.
- Theme: `ui/ListenTheme.kt` has the Reader's four themes, but only Dark is used. There
  is no theme picker or `settings.json` yet.
- Embedded pictures are **not** read yet (that's slow). Session 3 adds artwork.
- Song rows aren't tappable yet.

**For Session 2**
- `lighttool.toml` still has `capabilities = []`. Add `"detached-audio"` (and
  `WAKE_LOCK`) when the playback service goes in.
- The SDK audio extensions (PLAN.md section 1, Gap 2) go in their own "SDK: ..." commit.
- `Song.path` is relative to `Music/`. The file is `File(ListenPaths.music, song.path)`.
- `Song.durationMs` is already in the index, so Now Playing can show it before the
  player reports it.

---

# Appendix: Light SDK recon (written for the Prayer List tool)

Phase 0 recon. No app code written. Every claim below points at a file and line
in this repo so it can be re-checked. All paths are repo-relative.

Sources read: `docs/`, `sdk/client/`, `sdk/ui/`, `lint-rules/`, `plugin/`,
`examples/authenticator`, `examples/weather`, `examples/ui-demo`,
`examples/audio-demo`, `tool/`.

---

## 1. UI components the SDK gives you

All Compose components live in `:sdk:ui`
(`sdk/ui/src/main/kotlin/com/thelightphone/sdk/ui/`). They render in black &
white and size themselves off a 27 × 31 grid, so there is no pixel sizing —
you multiply grid units. Nothing here carries meaning by color.

### Text

| Component | Where | Notes |
|---|---|---|
| `LightText(text, variant, …)` | `LightText.kt:76` | The text primitive. `align`, `lighten` (secondary color), `underline`, `monospace`, `maxLines`, `overflow`, `color`. |
| `LightTextVariant` enum | `LightText.kt:22` | `Title, Subtitle, Heading, Subheading, Copy, Button, Paragraph, ParagraphWide, Detail, Fine, Superfine, Micro` (12 sizes). Actual sizes: `LightTheme.kt:72`. |

There is **no** editable inline text field. Text entry is a full-screen flow
(see below).

### Text entry (full screen + read-only field)

| Component | Where | Notes |
|---|---|---|
| `LightTextInputEditor(title, state, onSubmit, onBack, keyboardOptionsFlow, …)` | `LightTextInputEditor.kt:48` | Full-screen editor: top bar with back, big underlined input, embedded LP3 keyboard, submit button in a bottom bar. `singleLine`, `initialCaps`, `submitLabel`, `submitIcon`, `showBackButton`, `editorKey`. |
| overload taking a `Lp3KeyboardViewModel` directly | `LightTextInputEditor.kt:104` | Lower-level; the first overload is what tools use. |
| `LightTextField(label, value, placeholder, onClick)` | `LightTextField.kt:24` | **Read-only** display of a value with a label and underline. Tapping it is expected to open a `LightTextInputEditor` on another screen. This is the standard "edit a field" pattern (see `examples/weather`). |
| `rememberKeyboardOptions()` | `sdk/client/.../LightKeyboardManager.kt:51` | Supplies the `StateFlow<KeyboardOptions>` the editor needs; pulls user prefs from LightOS. `defaultKeyboardOptions()` at `LightTextInputEditor.kt:271` is the offline fallback. |
| `state` = `androidx.compose.foundation.text.input.TextFieldState` | used at `LightTextInputEditor.kt:48` | Create with `rememberTextFieldState(initial)`. |

State machine pattern: `examples/weather/.../WeatherHomeScreen.kt:86` switches
one screen between an editor mode and content modes rather than navigating.
`examples/ui-demo/.../UiDemoTextInputEditorScreen.kt` shows the editor as its
own `SimpleLightScreen<String>` returning the typed value via `goBack`.

### Bars & buttons

| Component | Where | Notes |
|---|---|---|
| `LightTopBar(leftButton, center, rightButton)` | `LightTopBar.kt:40` | Fixed height 3 grid units (`LightTopBar.kt:17`). `center` is `LightTopBarCenter.Text` or `.TwoLineDetail` (`LightTopBar.kt:24`), optionally clickable. |
| `LightBottomBar(items, …)` | `LightBottomBar.kt:31` | Action bar. **Max 5 items** (`LightBottomBar.kt:36`); **if any item is text, max 3 items** (`LightBottomBar.kt:39`). `null` entries render as spacers. |
| `LightBarButton` sealed interface | `LightBarButton.kt:14` | `LightBarButton.Text(text, onClick)` (`:18`), `LightBarButton.Icon(painter, …)` for your own painter (`:29`), `LightBarButton.LightIcon(icon, onClick, …)` for a built-in icon (`:39`). `typealias LightTopBarButton` / `LightBottomBarItem` both = `LightBarButton` (`LightBarButton.kt:51`). |
| **Back button** | you place it, per screen | `sdk/client/README.md:64` claims the SDK "renders a back bar" — **it does not.** `LightActivity.onCreate` (`LightActivity.kt:113-137`) renders only `Content()` plus a modal overlay; there is no back-bar composable anywhere in `sdk/ui/`. The SDK does keep the back stack and wires the system back gesture to `goBack()` (`LightActivity.kt:60,73-88,139-146`), but LightOS/the LP3 exposes no system back to apps, so every non-root screen must draw its own: `LightTopBar(leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }), …)`. This is what every example does (`examples/ui-demo/.../UiDemoSecondScreen.kt:61`, `examples/authenticator/.../AuthenticatorCodeScreen.kt:62`, `examples/weather/.../WeatherHomeScreen.kt:211`). Root/`@InitialScreen` screens omit it — `goBack()` there calls `finish()`. |

### Icons

| Component | Where | Notes |
|---|---|---|
| `LightIcon(icon, …)` | `LightIcon.kt:23` | Tinted with `colors.content`. Size in grid units (`width`/`height`/`size`). |
| `LightIcons` object | `LightIcons.kt:8` | ~130 named monochrome icons, e.g. `ADD`, `BACK`, `CLOSE`, `DELETE`, `TRASH`, `PENCIL`, `ACCEPT`, `DENY`, `SETTINGS`, `SEARCH`, `LIST`, `LARGE_LIST`, `STAR` / `STAR_OUTLINE`, `SELECT_ON` / `SELECT_OFF`, `TOGGLE_STATE_ON` / `_OFF`, `UP` / `DOWN`, `ARROW_RIGHT`, `REVERSE_ORDER`, `LOOP`, `ELLIPSES`, `CONTACTS`, `SPACER`. Full list is the file. `LightIcons.allEntries` (`:434`) enumerates them. |

There is **no** checkbox / radio / switch composable — `TOGGLE_STATE_ON/OFF`
and `SELECT_ON/OFF` are just icons you place yourself.

### Scrolling

| Component | Where | Notes |
|---|---|---|
| `LightScrollView(modifier, scrollBarPosition, scrollState) { … }` | `LightScrollView.kt:97` | Column-based scroll region with the Light scrollbar. Used everywhere for lists of a few dozen rows (`examples/*`). Put it in a `Column` with `Modifier.weight(1f)`. |
| `LightLazyScrollView(…, uniformItemHeightGridUnits, content)` | `LightScrollView.kt:147` | Lazy version; needs a **uniform row height** in grid units. Use for long lists. |
| `LightScrollBarPosition` | `LightScrollView.kt:51` | `Outside` (default) / `Inside`. |

### Modals / overlays

| Component | Where | Notes |
|---|---|---|
| `LightFullscreenModal(message, onClose)` | `LightFullscreenModal.kt:16` | Full-screen message with a single close button. Tools drive it with a nullable `String` in the view model (`examples/authenticator/.../AuthenticatorViewModel.kt:18`, rendered at `AuthenticatorHomeScreen.kt:141`). |
| `LightModalManager` (object) + `LightModal` interface | `LightModalManager.kt:52` / `:25` | App-wide **transient** overlay, one at a time, auto-dismiss after `DEFAULT_DURATION` = 2s (`LightModalManager.kt:54`). For toasts/confir…-flash, not for dialogs. |

There is **no** confirm/alert dialog component. The established pattern for a
destructive confirm is a dedicated screen returning `Boolean` —
`examples/authenticator/.../AuthenticatorConfirmRemoveScreen.kt` (a
`SimpleLightScreen<Boolean>` with a "CONFIRM" bottom-bar button).

### Progress

| Component | Where | Notes |
|---|---|---|
| `LightProgressBar(colors, progress)` | `LightProgressBar.kt:27` | Static bar. |
| `LightTouchableProgressBar(colors, progress, onValueChange)` | `LightProgressBar.kt:45` | Draggable — doubles as a slider. |

### Theme

| Component | Where | Notes |
|---|---|---|
| `LightTheme(colors, typography, surfaceScheme) { … }` | `LightTheme.kt:203` | Wrap every screen's content in this. |
| `LightThemeTokens.colors` / `.typography` | `LightTheme.kt:163` | `colors.background`, `colors.content`, `colors.contentSecondary` — the only three colors (`LightTheme.kt:31`, values at `:53`). Dark = black bg/white content; Light = the inverse. |
| `LightThemeController` (object) | `LightThemeController.kt:12` | Global light/dark state: `colors` `StateFlow`, `setDarkTheme()`, `setLightTheme()`, `toggle()`, `isDarkTheme`. Screens do `val c by LightThemeController.colors.collectAsState()` then `LightTheme(colors = c)` (`tool/.../HomeScreen.kt:80`). |

### Layout helpers (grid)

`LightGrid.kt` — `LightGrid.WIDTH = 27`, `HEIGHT = 31` (`:13`). Extension fns,
all `@Composable`:
- `Float.gridUnitsAsDp()` — horizontal units → dp (`:19`)
- `Float.verticalGridUnitsAsDp()` — vertical units → dp (`:25`)
- `Float.designVerticalPxToSp()` / `designVerticalPxToDp()` — scale a design px value (`:33`, `:39`)

Idiom seen everywhere: `Modifier.padding(horizontal = 1f.gridUnitsAsDp())`,
`padding(vertical = 0.75f.gridUnitsAsDp())`.

### Interaction

`Modifier.lightClickable(onClick = …)` — `LightClickable.kt:22`. No visual
press ripple (monochrome design); fires haptics on finger-down if the user has
them enabled. **Use this, not `Modifier.clickable`.**

### Also present (not needed for Prayer List, noted for completeness)

`LightQrCodeScanner` (`LightQrCodeScanner.kt:59`, plus a permission-aware
wrapper in `sdk/client/.../LightClientUiUtils.kt:38`), `LightNfcTapReader`
(`LightNfcTapReader.kt:30`), `LightEmbeddedLp3Keyboard`
(`keyboard/LightEmbeddedLp3Keyboard.kt:24`), `LightHapticFeedback`
(`LightHapticFeedback.kt:9`), `lightFontFamily()` (`LightFont.kt:10`).

---

## 2. What `LightScreen` / `LightScreenViewModel` require

Base classes are in `sdk/client/src/main/kotlin/com/thelightphone/sdk/`.
Note: the base view-model class is named **`LightViewModel`**, not
`LightScreenViewModel` (`LightViewModel.kt:6`).

### `SimpleLightScreen<ResultType>` — `LightScreen.kt:11`

Screen with no view model. You must override:
- `@Composable fun Content()` — `LightScreen.kt:17`

Optional lifecycle overrides: `willShow()`, `willHide()`, `onAppPause()`,
`onScreenDestroy()` — `LightScreen.kt:19-22`.

Provided to you:
- `navigateTo(screenFactory, resultCallback?)` — `LightScreen.kt:40`. Factory is
  `(SealedLightActivity) -> SimpleLightScreen<T>`; the callback fires when the
  child calls `goBack(result)`.
- `goBack(result: ResultType? = null)` — `LightScreen.kt:45`.
- `lightContext: SealedLightContext` — `LightScreen.kt:15` (persistence handles,
  see §4).
- Implements `LightKeyHandler` (`LightKeyHandler.kt:5`) — override `onKeyDown` /
  `onKeyUp` / `onKeyMultiple` for hardware keys. BACK/HOME never reach the
  screen (`examples/ui-demo/.../UiDemoKeyEventsScreen.kt:44`).

### `LightScreen<ResultType, VM : LightViewModel<ResultType>>` — `LightScreen.kt:51`

Screen with a view model. In addition to `Content()`, you must provide:
- `override val viewModelClass: Class<VM>` — `LightScreen.kt:54`
- `override fun createViewModel(): VM` — `LightScreen.kt:55`
- `viewModel: VM` is then available (lazy, `LightScreen.kt:60`).

This is exactly the "direct reference to the ViewModel's class type + a factory
method" the project's CLAUDE.md calls for. Canonical shape
(`tool/src/main/kotlin/com/thelightphone/sample/HomeScreen.kt:66`):

```kotlin
class FooScreen(sealedActivity: SealedLightActivity)
    : LightScreen<Unit, FooViewModel>(sealedActivity) {
    override val viewModelClass get() = FooViewModel::class.java
    override fun createViewModel() = FooViewModel(lightContext.dataStore)
    @Composable override fun Content() { /* wrap in LightTheme { } */ }
}
```

The framework forwards lifecycle into the VM: `notifyWillShow` → `onScreenShow`,
etc. (`LightScreen.kt:74-87`).

### `LightViewModel<T>` — `LightViewModel.kt:6`

Extends `androidx.lifecycle.ViewModel`, so `viewModelScope`, `onCleared()` and
`MutableStateFlow` are the tools. Overridable hooks:
- `onScreenShow(screen)` — `LightViewModel.kt:7` (called every time the screen
  comes to front — examples re-load data here)
- `onScreenHide(screen)` — `:8`
- `onAppPause()` — `:9`
- `onBackPressed(): Boolean` — `:14`. Return `true` to consume the back press
  (blocks the provided back button). Default `false`. Per CLAUDE.md, only
  override if genuinely needed.

### The initial screen

Exactly one screen in the tool must be annotated `@InitialScreen`
(`sdk/client/.../InitialScreen.kt:8`; requirement stated at
`sdk/client/README.md:28`). More than one, or zero, fails the build.

### Optional tool entry point

`@EntryPoint object … : LightEntryPoint` (`sdk/client/.../EntryPoint.kt:10`).
`onToolCreate(serverData)` runs once at process start (`:13`);
`onPushNotification` (`:15`) only matters if `enablePushNotifications` is `true`
(`:17`). Prayer List needs no network/push, so the stub in
`tool/.../ToolEntryPoint.kt` can stay as-is or be trimmed.

### Navigation rules (from CLAUDE.md + `sdk/client/README.md:53`)

- Navigate only with `navigateTo`. No Android Intents / system nav.
- `navigateTo` maintains the back stack automatically (`LightActivity.kt:60,65-88`);
  `goBack(result?)` pops it. You never manage the stack yourself.
- **Each non-root screen must draw its own back control** in the `LightTopBar`
  left slot — the SDK does not render one despite the README. See the Back button
  row in §1. `HomeScreen` (root) omits it.
- Don't override `LightViewModel.onBackPressed()` (default `false`). It only
  *consumes* the back press when it returns `true` — an unsaved-changes guard,
  not a fix for missing navigation.
- To hand a value back to the opener: child `goBack(result)` → opener's
  `resultCallback`. Examples: `AuthenticatorHomeScreen.kt:93` (open detail),
  `UiDemoTextInputEditorScreen.kt:39` (return typed string).

---

## 3. What the lint rules / build plugin forbid

Two enforcement layers. **Both run at build time**; violations fail
`./gradlew :tool:assembleDebug`.

### A. Gradle plugin static scan — `plugin/src/main/kotlin/com/thelightphone/plugin/LightSdkPlugin.kt`

Scans every `.kt` file in `tool/src` line-by-line
(`LightSdkPlugin.kt:370`, `findSourceLineViolations` `:188`).

**Blocked imports** (`LightSdkPlugin.kt:81`): `android.app.*`,
`android.content.Context`, `android.content.Intent`, `ComponentName`,
`BroadcastReceiver`, `ContentProvider`, `ServiceConnection`,
`androidx.compose.ui.platform.LocalContext` / `LocalView` /
`LocalLifecycleOwner`, `androidx.lifecycle.compose.LocalLifecycleOwner`,
`androidx.activity.*`, `androidx.appcompat.*`, `java.lang.reflect.*`,
`java.lang.invoke.*`, `kotlin.reflect.*`.

**Blocked code patterns** (`LightSdkPlugin.kt:100`): any use of
`LocalContext` / `LocalView` / `LocalActivity` / `LocalLifecycleOwner`;
casting to anything ending `Activity` or to
`Context/ContextWrapper/Application/Service/ContentProvider/BroadcastReceiver`;
`startActivity(` / `startService(` / `bindService(` / `registerReceiver(` /
`getSystemService(` / `contentResolver` / `getBaseContext(` /
`attachBaseContext(` / the `createXContext(` family; reflection —
`.javaClass`, `.java.<x>`, `Class.forName(`, `getDeclaredMethod`/`getMethod`/
`getDeclaredField`/`getField`, `MethodHandles`.
Message for `startActivity`: *"use `LightScreen.navigateTo()` instead"*.

**Dependencies — allow-list only** (`LightSdkPlugin.kt:17`, `ALLOWED_DEPENDENCIES`).
Relevant entries that ARE allowed: `androidx.compose*`,
`androidx.activity:activity-compose`, `androidx.annotation`,
`org.jetbrains.kotlinx:kotlinx-coroutines`, `androidx.lifecycle`,
`androidx.datastore`, `androidx.room`, `androidx.work`, `androidx.startup`,
`org.jetbrains.kotlinx:kotlinx-serialization`, `kotlinx-io`,
`kotlinx-datetime`, `com.squareup.okhttp3:okhttp`, `io.ktor`, plus keyboard /
media3 / zxing / sol4k / bouncycastle. Anything else → configuration fails,
and the error prints the whole allow-list. It also checks the **resolved**
graph for substitution (`:475`).

**Plugins — allow-list only** (`LightSdkPlugin.kt:47`): the six already in
`tool/build.gradle.kts` plus `com.android.library`. No `buildscript {}`, no
`resolutionStrategy`, no `apply(from=…)`, no custom `srcDirs`
(`UNIVERSAL_BUILD_SCRIPT_PATTERNS` `:133`).

**Build-script fields you may NOT set** in `tool/build.gradle.kts`
(`CONSUMER_BUILD_SCRIPT_PATTERNS` `:146`): `applicationId`, `versionCode`,
`versionName`, `namespace` — these come from `tool/lighttool.toml`
(`docs/tool_metadata/README.md`).

**KSP processors — allow-list** (`LightSdkPlugin.kt:77`): only
`androidx.room:room-compiler`.

**Other:** no hand-written `src/main/AndroidManifest.xml` (`:352`); no `.java`
files, Kotlin only (`:362` / `:214`).

### B. Android Lint custom rules — `lint-rules/src/main/kotlin/com/thelightphone/lint/`

Registered in `LightSdkIssueRegistry.kt:9`. All `Severity.ERROR`.

| Issue id | Where | What it blocks |
|---|---|---|
| `LightSdkLocalContext` | `ActivityAccessDetector.kt:20` | reading `LocalContext.current` |
| `LightSdkLocalView` | `ActivityAccessDetector.kt:34` | reading `LocalView.current` |
| `LightSdkActivityCast` | `ActivityAccessDetector.kt:48` | `as`/`as?` cast to `Activity` / `ComponentActivity` / `AppCompatActivity` / `LightActivity` |
| `LightSdkInvalidLightJob` | `LightJobDetector.kt:23` | `@LightJob` not on a **top-level `val` of type `LightJobHandler`** |
| `LightSdkLightJobEmptyKey` | `LightJobDetector.kt:38` | `@LightJob` with an empty/non-literal key |

`tool/build.gradle.kts:43` sets `warningsAsErrors = false` but promotes
`RestrictedApi` to an error.

### Practical effect for Prayer List

Everything the app needs is inside the allow-list. Don't reach for a
Context, don't add a JSON library (use `kotlinx-serialization`, already
allowed), don't add a dialog library (build from primitives), get grouping/
sorting done in Kotlin. If something seems to need a forbidden API, stop and
ask (per CLAUDE.md §7).

---

## 4. Options for saving data on the device

All offline, all on-device (CLAUDE.md §1). Handles come from
`SealedLightContext` — `sdk/client/.../LightActivity.kt:229`:

```
lightContext.dataStore : DataStore<Preferences>   // LightActivity.kt:230
lightContext.filesDir  : File                     // LightActivity.kt:231
lightContext.fileShare : LightFileShare           // LightActivity.kt:232
lightContext.readAsset(path): ByteArray           // LightActivity.kt:233
lightContext.buildDatabase(cls, name): RoomDatabase  // LightDb.kt:6 (extension)
```

### Option A — Room (SQLite) — recommended for the Prayer List model

- `SealedLightContext.buildDatabase(dbClass, dbName)` — `LightDb.kt:6`. Thin
  wrapper over `Room.databaseBuilder`.
- `androidx.room` is allow-listed (`LightSdkPlugin.kt:38`); `room-compiler` is
  the one allowed KSP processor (`:78`); `tool/build.gradle.kts:63` **already**
  wires `ksp(libs.androidx.room.compiler)`. Room runtime/ktx are re-exported
  `api` by `:sdk:client` (`sdk/client/build.gradle.kts:62-63`), so no new
  dependency is needed. Room version 2.7.0 (`gradle/libs.versions.toml:10`).
- Full worked example: `examples/authenticator/` —
  `TotpDatabase` (`@Database`, `TotpDatabase.kt:6`),
  `TotpAccountDao` (`@Dao` with `@Query`/`@Insert`/`@Update`, `TotpAccountDao.kt`),
  a repository holding a singleton DB (`TotpAccountRepository.kt:61`),
  DB built from the screen via `lightContext.buildDatabase(...)`
  (`AuthenticatorHomeScreen.kt:37`), VM reads it on `Dispatchers.IO`
  (`AuthenticatorViewModel.kt:37`).
- Fits Groups / People / Entries with foreign keys, `ORDER BY … COLLATE NOCASE`
  for the alphabetical lists, and soft-delete flags (`archived`) as columns.

### Option B — Preferences DataStore

- `lightContext.dataStore` — one shared store per tool, name `"DEFAULT_DATASTORE"`
  (`LightActivity.kt:241`). `androidx.datastore` allow-listed; re-exported `api`
  (`sdk/client/build.gradle.kts:55`).
- Key/value only. Pattern: `object … { val X = stringPreferencesKey("x") }`
  (`examples/weather/.../WeatherPreferences.kt`), read with
  `dataStore.data.first()` / observe `dataStore.data`, write with
  `dataStore.edit { }` (`examples/weather/.../WeatherViewModel.kt:187,288`).
- Good for small singletons: last-opened tab, "seed already imported" flag,
  theme choice. **Not** a good fit for the relational person/entry data.

### Option C — plain files under `filesDir`

- `lightContext.filesDir: File` — standard app-private dir. Example:
  `examples/audio-demo/.../AudioLibraryRepository.kt:90` keeps a
  `File(filesDir, "recordings")` subdir and manages files directly.
- Could hold a single serialized JSON blob (via `kotlinx-serialization`,
  allow-listed). Simple, but you lose queries/indexes and hand-roll all
  concurrency. Reasonable fallback if Room feels heavy; Room is still the
  cleaner match for this data.

### Option D — `LightFileShare` (shared dir) — NOT for app data

- `lightContext.fileShare` — `sdk/client/.../LightFileShare.kt:14`. `read`
  (`:18`), `write` (`:42`), `list` (`:32`), `exists` (`:28`), `delete` (`:24`),
  `getUri` (`:37`). Path traversal is blocked (`:50`).
- Purpose is files **LightOS** reads via a content provider (ringtones,
  wallpapers) — `sdk/client/README.md:72`, `tool/.../HomeScreen.kt:47`. Private
  prayer data does not belong here.

### Seed data (Build order phase 1 & 6)

- Phase 1 "small seed dataset": just insert rows into Room on first run if the
  DB is empty (guard with a DataStore boolean or a `SELECT COUNT(*)`).
- Phase 6 "JSON seed import from the app's external files directory": **gap
  resolved (2026-09-07).** `SealedLightContext` exposes `filesDir` (internal)
  and `readAsset(path)` (`LightActivity.kt:233`, bundled `assets/`) but **no
  accessor for `getExternalFilesDir(...)`**, and `android.content.Context` is
  import-blocked (`LightSdkPlugin.kt:83`) plus `Context` casts are a lint
  ERROR. So a true `/sdcard/Android/data/<pkg>/files/` path is unreachable from
  the `tool` module. Decision (user picked): use `lightContext.fileShare`
  (`LightFileShare`, root `filesDir/shared/`) as the drop location. Import in
  `SeedFileImporter` reads `prayer_seed.json` there, calls
  `PrayerRepository.importSeed`, then copy+deletes it to
  `prayer_seed.imported-<ts>.json` (LightFileShare has no rename). Groups/people
  only; JSON via `kotlinx-serialization-json` (added to `tool/build.gradle.kts`,
  allow-listed). Format + fillable file: `docs/prayer_seed.example.json`.

### Background work (only if ever needed)

`LightWork` + `@LightJob` — `sdk/client/README.md:362`. Not needed for Prayer
List (no sync, no notifications) and comes with the two lint rules in §3B.

---

## 5. Quick reference — building order alignment

| Phase | Key SDK pieces |
|---|---|
| 1 Data layer | Room via `buildDatabase` (`LightDb.kt:6`); repo singleton pattern (`TotpAccountRepository.kt:61`) |
| 2 Home + Group screens | `LightScreen` + `LightViewModel`; `LightTopBar`, `LightScrollView`, `LightText`, `lightClickable`; `navigateTo` |
| 3 PersonScreen tabs | no tab component exists — build tabs from a `Row` of `LightText`/`LightIcon` + selected-state `MutableStateFlow`; body is `LightScrollView` |
| 4 Entry create/edit + mark-answered | dedicated `LightScreen` with `LightTextInputEditor` (`LightTextInputEditor.kt:48`) + `rememberKeyboardOptions()`; a type selector built from `LightBarButton`s; mark-answered = set `answeredAt` column |
| 5 ManageScreen | forms of the same primitives; reorder = `sortOrder` int + `UP`/`DOWN` icons |
| 6 JSON seed | `lightContext.fileShare` drop of `prayer_seed.json` + `kotlinx-serialization-json`; see §4 (done) |
| 7 Polish | `LightGrid` units, `LightText` empty-state messages, emulator at 1080×1240 (`docs/system_app/README.md`) |

## 6. Build

```bash
./gradlew :tool:assembleDebug     # must pass to finish a phase
./gradlew tasks                   # confirm task names
```

Needs `gpr.user` / `gpr.key` in `local.properties` (CLAUDE.md §2 — never read
or print that file). Metadata (id/label/version/permissions) lives in
`tool/lighttool.toml`, not the build script.

---

## 7. Open issues found verifying this doc for Small Group (2026-09-13)

- **External files directory is still unreachable.** §4's phase-6 gap applies
  here too: `SealedLightContext` has no `getExternalFilesDir` equivalent, and
  `Context` is import-blocked, so a true `/sdcard/Android/data/<pkg>/files/`
  path can't be reached from the `tool` module. CLAUDE.md §5's export
  requirement has been reworded to use `lightContext.fileShare`
  (`LightFileShare`) instead, matching the prayer-list project's resolution.
- **`tool/lighttool.toml` declared `android.permission.INTERNET`** despite
  CLAUDE.md §1 requiring the app be offline-only. Likely a leftover from the
  prayer-list template. Removed; rebuild confirmed clean.

## 8. Launcher visibility — corrected (2026-09-15)

Earlier recon (during a launcher-access investigation for the Bible reader)
wrongly concluded sideloaded tools have no way to show up in the LightOS
launcher and can only be launched via `adb` from a laptop. **That's wrong —
Jordan corrected it from direct hardware experience.**

- Sideloaded tools **do** appear in the LightOS launcher menu and launch
  normally from the phone, no laptop/adb needed. Both the prayer-list and
  reader tools do this today.
- The catch: **the launcher caches its menu and doesn't pick up a newly
  installed tool until it's restarted.** A reboot of the phone after
  `./gradlew :tool:assembleDebug` + install is what makes a freshly installed
  tool show up. Skipping the reboot and expecting to find it in the menu is
  the likely source of the earlier wrong conclusion.
- The top-level `README.md`'s "no easy way to share your tool" language is
  about there being no *install/distribution* path for ordinary (non-ADB)
  users yet — not about launcher visibility once a tool is actually
  installed. Don't conflate the two again.
