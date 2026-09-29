# Listen: build plan

Written in Session 0 (2026-09-29) after studying the Reader on `origin/reader` and the SDK
on this branch. `CLAUDE.md` says *what* the app does; this file says *how* it's built.

---

## 1. What we learned from the Reader and the SDK

### How the Reader is built, signed and installed

- The whole tool lives in the **`tool/` module**. Each branch swaps what's inside it
  (`main` has the Bible reader, `reader` has the Reader). Listen does the same.
- **`tool/lighttool.toml`** holds the package id, label, version and permissions. The
  SDK's Gradle plugin (`plugin/`) **generates the AndroidManifest from it**. A
  hand-written `AndroidManifest.xml` is not allowed, and neither is setting
  `applicationId`/`versionCode` in `build.gradle.kts`.
- **Build:** `.\gradlew.bat :tool:assembleDebug` → `tool\build\outputs\apk\debug\tool-debug.apk`.
- **Signing:** debug and release both use the shared dev key in the repo,
  `sdk/keys/lightsdk-dev.jks` (password `android`). Every build is signed with the same
  key, so `adb install -r` always upgrades in place.
- **Install:** `adb install -r tool\build\outputs\apk\debug\tool-debug.apk`, then reboot
  once after the first install so the launcher shows it. The Reader wraps this in
  `scripts/Build and Install Reader.cmd`; Listen gets the same script.
- **Machine:** `gradle.properties` is already tuned for 8 GB of RAM (2 workers, 2 GB
  heap). Java 17 (Temurin) is installed and `JAVA_HOME` points to it. The SDK is at
  `C:\Users\bjc38\AppData\Local\Android\Sdk` (android-36 platform present).

### How the Reader reads files

- It **does not use `/sdcard`**. Its PC scripts push books with `adb push` to
  `/data/local/tmp` and then `run-as com.thelightphone.reader cp ...` into the app's
  **private** folder (`filesDir/shared/books`). Positions and lists are in a Room
  database, also private.
- So an uninstall wipes the Reader's books and places. That's exactly what CLAUDE.md wants
  Listen to avoid, and `Listen-Phone-Sync.cmd` already writes to `/sdcard/Listen/`.
  **Recommendation: keep the CLAUDE.md approach** (`/sdcard/Listen/` + All files access),
  not the Reader's.

### SDK rules that shape Listen (the "sandbox")

The plugin scans tool code at build time and fails the build if it finds:

- imports of `android.app.*`, `android.content.Context`, `Intent`, `androidx.activity.*`
- `startService`, `bindService`, `registerReceiver`, `getSystemService`,
  `contentResolver`, reflection, casting to `Context`/`Service`/`Activity`
- any Java source, custom `srcDirs`, or plugins outside the allowlist

Allowed and useful: `java.io.File`, `android.os.Environment`, `android.media.MediaMetadataRetriever`,
`android.graphics.BitmapFactory`/`ImageDecoder`, kotlinx.serialization, Room, DataStore,
coroutines, and the SDK's own UI kit (`com.thelightphone.sdk.ui`). Screens get
`lightContext.filesDir` for private files. There is no `cacheDir`, so the index cache goes
in `filesDir/cache/`.

### Two SDK gaps Listen must close (small changes in our fork)

**Gap 1: `MANAGE_EXTERNAL_STORAGE` isn't on the permission allowlist**
(`plugin/.../LightToolMetadata.kt`, `LightToolPolicy.ALLOWED_PERMISSIONS`). Without it in
the manifest, `appops set ... MANAGE_EXTERNAL_STORAGE allow` has nothing to grant.
*Fix:* add that one line to the allowlist (and its test). One-line plugin change.

**Gap 2: the SDK's playback service is too basic.** The SDK already has what CLAUDE.md
asks for in outline: `capabilities = ["detached-audio"]` adds a Media3
**`MediaSessionService`** (`sdk/client/.../audio/LightAudioService.kt`) with one ExoPlayer,
a media notification, Bluetooth/headset buttons and audio focus. Tools talk to it through
`LightAudioPlayer`. We can't write our own service (the plugin forbids manifests and
`startService`), so we **extend the SDK's** instead. It is missing:

| Needed by CLAUDE.md | In SDK today? | Change |
|---|---|---|
| Audio focus, pause for calls | Yes (`handleAudioFocus = true`) | none |
| Pause on headphone/BT disconnect | No | service: `setHandleAudioBecomingNoisy(true)` |
| Keep playing with screen off | Partly | service: `setWakeMode(C.WAKE_MODE_LOCAL)` (+ `WAKE_LOCK` permission, already allowed) |
| Switch Music ↔ Speech attributes on the live player | No (usage locked while playing) | player: `setUsage(...)` that swaps attributes on the one player |
| Seek to track *N* at position *ms* (resume) | No | player: `seekTo(index, ms)` |
| Shuffle, repeat off/all/one | No | player: shuffle/repeat setters + state flows |
| Artwork in the notification | No | metadata: `artworkUri` (file URI of our cached cover) |
| Format / bitrate / sample rate line | No | player: expose current track `Format` |
| Audio offload setting | No | player: set `audioOffloadPreferences` |
| Gapless | Yes (ExoPlayer default, one player + real playlist) | none; don't recreate the player |
| Untouched output, own sample rate | Yes (no effects/EQ added) | none; don't add any |

These are **additive** changes to `sdk/client/.../audio/` (nothing existing breaks), kept
in their own commit, "SDK: extend detached audio for Listen", so they're easy to see.
Jordan: this is the one place Listen changes shared code. It's your fork, and there is no
other way to meet CLAUDE.md, so I recommend it.

---

## 2. Versions

- **Media3 1.10.1**, the version already in `gradle/libs.versions.toml` and used by the
  SDK's audio service. Listen must use the **same** version as the SDK, so we don't
  pick our own. Modules: `media3-exoplayer`, `media3-session`, `media3-common`
  (all MP3/AAC/M4A/M4B/FLAC/Ogg/Opus/WAV extractors are built in; no FFmpeg).
- Kotlin 2.3.20, AGP 9.4.0, compileSdk/targetSdk 36, minSdk 34 (the phone is Android 14,
  API 34). kotlinx.serialization 1.8.1 for JSON.
- **No Room** for Listen. State must live as JSON in `/sdcard/Listen/.state/`, and the
  index cache is small enough for JSON too (about 1,400 songs is roughly 0.5 MB).

---

## 3. `lighttool.toml`

```toml
[tool]
id = "com.thelightphone.listen"
label = "Listen"
versionCode = 1
versionName = "0.1.0"
permissions = ["android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.WAKE_LOCK"]
capabilities = ["detached-audio"]
serverPackage = "com.lightos"
orientation = "portrait"
```

(The media notification from a `MediaSessionService` doesn't need `POST_NOTIFICATIONS`.)

---

## 4. Files on the phone

| What | Where | Who writes it |
|---|---|---|
| Music | `/sdcard/Listen/Music/<Artist>/<Album>/...` | PC script |
| Audiobooks | `/sdcard/Listen/Audiobooks/.../<Book>/` + `book.json` | PC script |
| Sync stamp | `/sdcard/Listen/.state/last-sync.txt` | PC script |
| Playlists | `/sdcard/Listen/.state/playlists.json` | Listen |
| Music resume | `/sdcard/Listen/.state/music_state.json` | Listen |
| Book positions | `/sdcard/Listen/.state/book_positions.json` | Listen |
| Settings + sort choices | `/sdcard/Listen/.state/settings.json` | Listen |
| Tag index, art thumbnails | `filesDir/cache/` (private, rebuildable) | Listen |

Every Listen write is atomic: write `name.tmp`, then rename it over `name`. Unknown JSON
fields are ignored and missing ones get defaults, so an older backup restores cleanly.

---

## 5. Project layout

Everything under `tool/src/main/kotlin/com/thelightphone/listen/`. The Bible code now in
`tool/` gets removed in Session 1 (it stays safe on `main`).

```
listen/
  ListenEntry.kt            initial screen: access check → Home
  HomeScreen.kt             Music / Audiobooks / Now Playing (+ Settings)
  NoAccessScreen.kt         "Run Listen-Phone-Sync.cmd on the PC and pick option 9"

  ui/                       copied/adapted from the Reader
    ListenTheme.kt            ← ReaderTheme.kt (Dark, Light, Sepia, Night + accent)
    UniformRow.kt             ← UniformRow.kt (fixed-height rows for LightLazyScrollView)
    Divider.kt                ← Divider.kt (HairlineDivider)
    RowIconButton.kt          ← RowIconButton.kt
    SettingRows.kt            ← SettingRows.kt
    SortScreen.kt             ← SortFilterScreen.kt (sort field + A–Z/Z–A)
    ArtworkImage.kt           composable that shows a cached thumbnail or initial placeholder
    NowPlayingBar.kt          the small bar at the bottom of list screens
    UpdatingLine.kt           "Updating library..."

  storage/
    ListenPaths.kt            all /sdcard/Listen paths in one place
    StorageAccess.kt          Environment.isExternalStorageManager()
    AtomicJson.kt             read-with-fallback / write .tmp + rename
    SyncWatcher.kt            compares last-sync.txt + folder mtimes on launch/resume
    Settings.kt               settings.json model (theme, sorts, rewind, offload...)

  artwork/
    ArtworkCache.kt           decode downscaled (160 px / 800 px), disk cache by key,
                              small memory LRU, all off the main thread
                              (based on the Reader's CoverImages.kt + BookCover.kt)

  music/
    TagReader.kt              MediaMetadataRetriever → tags + embedded picture; folder fallbacks
    MusicScanner.kt           walk Music/, re-read only changed (path, size, mtime)
    MusicIndex.kt             Song / Album / Artist models, cached as JSON
    Grouping.kt               albums by album artist + album; disc, then track order
    SortKeys.kt               drop leading The/A/An; case- and accent-insensitive
    MusicHomeScreen.kt  SongsScreen.kt  ArtistsScreen.kt  ArtistScreen.kt
    AlbumsScreen.kt  AlbumScreen.kt

  playlists/
    Playlist.kt               ordered entries: Song(path) | Album(artist, album) | Artist(name)
    PlaylistStore.kt          playlists.json
    PlaylistExpander.kt       live expansion against the current library, de-duplicated,
                              "not on phone" when an entry matches nothing
    PlaylistsScreen.kt  PlaylistScreen.kt  PlaylistEntryScreen.kt
    AddToPlaylistScreen.kt    ← modelled on the Reader's AddToListScreen.kt
    PlaylistNameScreen.kt     ← modelled on ListNameScreen.kt

  books/
    BookJson.kt               book.json model (schema 1), tolerant parsing
    BookScanner.kt            finds book folders, falls back to folder name + tags
    BookIndex.kt              Book / chapter list (per-file or m4b chapter marks)
    Chapters.kt               position ↔ "Chapter 12 of 40, 14:32 left in chapter"
    BookPositions.kt          book_positions.json keyed by book id
    SeriesGroups.kt           ← the Reader's SeriesGroups.kt (author → series → number)
    BooksScreen.kt            Continue listening + grouped library + sort
    BookScreen.kt             cover, details, chapters, Resume / Mark finished / Start over

  playback/
    PlaybackHub.kt            app-wide owner of the ONE detached LightAudioPlayer handle;
                              knows whether music or a book is loaded
    MusicSession.kt           build queue, save/restore music_state.json (every 5 s + events)
    BookSession.kt            load a book, save position (every 10 s + events), auto-rewind,
                              finished = last 30 s, speed per book
    SleepTimer.kt             15/30/45/60 min or end of chapter
    MusicNowPlayingScreen.kt  art, seek bar, prev/play/next, shuffle, repeat, queue, add to playlist
    BookNowPlayingScreen.kt   chapter seek bar + book position, -15/+30, chapters, speed, sleep
    QueueScreen.kt  SpeedScreen.kt  SleepTimerScreen.kt  ChaptersScreen.kt

  settings/
    SettingsScreen.kt         theme, rewind amounts, audio offload (default off)

tool/src/test/kotlin/...      plain JVM tests: SortKeys, Grouping, PlaylistExpander,
                              Chapters, BookJson fallbacks, rescan diff, AtomicJson
scripts/Build and Install Listen.cmd   ← copy of the Reader's script, renamed
```

Why `PlaybackHub`: the SDK allows one detached player handle per process. Listen opens it
once and keeps it for the app's life. Music and books share the one ExoPlayer and swap
queues, and each keeps its own saved spot (CLAUDE.md "separate resume points").

---

## 6. Key design decisions

- **Scanning never blocks the UI.** Runs on `Dispatchers.IO`; lists show the cached index
  immediately and update when the scan finishes. Tag reads happen only for new or changed
  files (keyed by path + size + mtime).
- **Albums** key on (album artist, album), normalized. Missing album artist → track
  artist → folder artist.
- **Playlists store intent, not songs.** Expansion happens at play time, so new music by a
  saved artist joins automatically, and removed music greys out instead of vanishing.
- **Positions are keyed by book `id`** from book.json (or the folder path relative to
  `Audiobooks/` when book.json is missing), so a re-sent book resumes.
- **Saving:** music every 5 s, books every 10 s while playing, plus immediately on pause,
  seek, skip, chapter change and when the app goes to the background. The saver runs in
  the app process (same process as the service), so it keeps running with the screen off.
  Worst case after a hard kill: the last 5–10 seconds are lost.
- **Unplayable file:** catch the player error, show "Can't play this file", skip to the
  next item. Never crash.
- **Artwork:** always decoded with a target size; never full-size 2,800 px bitmaps.

---

## 7. Phases (one per session in BUILD_GUIDE.md)

1. **Skeleton + file access + music scan.** Replace Bible code; `lighttool.toml`; plugin
   allowlist change; access screen; scanner + index; Home; plain Songs list; install script.
2. **Playback + music resume.** SDK audio extensions; PlaybackHub; music Now Playing;
   now-playing bar; `music_state.json` restore paused.
3. **Artists, albums, sorting, artwork.** Grouping, sort screens, artwork cache.
4. **Playlists.** Live entries, add/remove/move, greyed "not on phone".
5. **Audiobook library.** book.json scanning, Continue listening, series grouping, book screen.
6. **Audiobook playback.** Positions, auto-rewind, speed, sleep timer, chapter seek bar,
   music/book switching.
7. **Audio quality + polish.** Verify quality checklist, offload setting, format line,
   speed check on 1,400 songs / 560-file book, empty-state messages.

---

## 8. Risks and things to check on the phone

- **Plugin/SDK edits** (section 1): **approved by Jordan on 2026-09-29.** Make them as a
  separate commit ("SDK: ...") during Sessions 1 and 2. Keep the `/sdcard/Listen/` layout
  so `Listen-Phone-Sync.cmd` keeps working. **If LightOS blocks All files access or the
  detached audio service on the phone, stop and tell Jordan. Do not switch to the
  Reader's private-folder method without asking first.**
- **Does LightOS let the detached audio service keep running** with the screen off and
  in other tools? The SDK is designed for it; we'll confirm in Session 2 test step 1.
- **The fenleon Audiobooks app** (probably `com.lightphone.audiobooks` on the phone) is still installed. Leave it
  until Listen's audiobook playback is proven in Session 6.
- **560-file books:** loading 560 media items into one ExoPlayer playlist is fine, but the
  book screen must compute durations from the index, not by probing files on open.
