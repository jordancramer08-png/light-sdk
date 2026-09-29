# Listen: music + audiobook player for the Light Phone III

Copy this file into the root of the Listen project as `CLAUDE.md`. Claude Code reads it at
the start of every session.

## Who I am and how I work

- I'm Jordan. I'm not a developer. I build LP3 tools with Claude Code from **PowerShell on
  my Windows laptop**. Give me short, plain steps and exact commands to paste.
- **Don't use Android Studio or the emulator.** The laptop has 7.6 GB of RAM, and I've
  uninstalled Android Studio. Build with the Gradle wrapper from PowerShell and test on the
  **real phone** over USB (`adb install -r`).
- The launcher caches its menu. After the *first* install, reboot the phone to make the
  tool show up.
- This is my fifth LP3 tool. The **Reader** (EPUB + comics) is on the `reader` branch of my
  fork, github.com/jordancramer08-png/light-sdk, and it works well. **Reuse its patterns**:
  theme and color themes, list and row components, settings screen, fonts, file access,
  Gradle setup, signing, and the install commands. Read that code before inventing
  anything new. If the Reader already solved something, do it the same way.
- Work in the phases in `BUILD_GUIDE.md`. Finish, build, install and let me test each
  phase before starting the next. Commit at the end of every phase that works.

## What the app is

A separate LP3 tool called **Listen** (package `com.thelightphone.listen`, its own branch
`listen` in the same fork). It has three areas:

1. **Music**: Songs, Artists, Albums, Playlists
2. **Audiobooks**: its own library, kept completely separate from music
3. **Now Playing**: one player screen that adapts to music or audiobook

It replaces the fenleon Audiobooks app I use now.

## Where the files are (don't change this; the PC script depends on it)

My PC script `Listen-Phone-Sync.cmd` copies files onto the phone like this:

```
/sdcard/Listen/Music/<Artist folder>/<Album folder>/<song files>
/sdcard/Listen/Audiobooks/<Author>/<...series...>/<Book folder>/
        <audio files>   book.json   cover.jpg (or cover.png, optional)
/sdcard/Listen/.state/last-sync.txt   <- the PC script writes a timestamp after every change
```

- **File access:** use `MANAGE_EXTERNAL_STORAGE` (All files access) and plain `java.io.File`.
  The PC script grants it with `adb shell appops set com.thelightphone.listen
  MANAGE_EXTERNAL_STORAGE allow`. If access is missing, show a plain screen that says
  "Run Listen-Phone-Sync.cmd on the PC and pick option 9". Don't depend on a Settings
  screen, because LightOS may hide it. (If the Reader's approach is better on this phone,
  tell me and match it.)
- **App state goes in `/sdcard/Listen/.state/`**, not in private app storage. That covers
  playlists, positions, queue and settings. Then an uninstall or a new signing key can't
  wipe my playlists or my place in a book (I've lost an app before), and the PC script can
  back it up and restore it. Write each file atomically: write `name.tmp`, then rename it.
  Suggested files: `playlists.json`, `music_state.json`, `book_positions.json`,
  `settings.json`.
- **Index cache** (tag scan results, artwork thumbnails) can live in the app's own cache
  or files dir. It can always be rebuilt.
- **Rescan:** on launch and on resume, compare `last-sync.txt` (and the folder mtimes)
  with what was last indexed. If anything changed, rescan in the background and only
  re-read files whose path, size or mtime changed. Never block the UI during a scan. Show
  a small "Updating library..." line instead.

## Music library

- **Read tags on the phone** with `MediaMetadataRetriever` (or Media3's
  `MetadataRetriever`): title, artist, album artist, album, track number, disc number,
  year, duration and embedded picture. Fall back to the folder names (Artist\Album) and
  the file name when a tag is missing.
- **Group albums by album artist + album**, never by track artist. An album with several
  performing artists (for example Avengers: Age of Ultron with two composers) must stay
  one album. Albums play in disc, then track order.
- My library is about 1,400 MP3s in about 270 albums (tagged, with APIC front covers).
  Some older files tag the art as type 0 "Other", so accept any embedded picture.
- **Sorting.** Songs sort by Title, Artist or Album. Albums sort by Album or Artist.
  Artists sort by name. Every sort works A–Z and Z–A. Show a sort control at the top of
  each list, the same way the Reader's library sort works. Remember the choice per list.
  Ignore a leading "The ", "A " or "An " when sorting (Keith Green and The Corner Room
  sort under K and C). Compare case- and accent-insensitively.
- **Screens.** Music home shows Songs / Artists / Albums / Playlists. An artist opens to
  their albums (with art), then "All songs". An album shows its art, title, artist, year,
  track list, and Play and Shuffle buttons.

## Playlists

- A playlist is an **ordered list of entries**. Each entry is one of three kinds:
  - **Song**: a file path relative to `/sdcard/Listen/Music`
  - **Album**: album artist + album name
  - **Artist**: artist name (matched on album artist, and also on track artist)
- Entries are **live**. When a playlist plays, each entry expands against the *current*
  library. Adding "Shane & Shane" includes Shane & Shane songs I send later. An album
  expands in track order. An artist expands album by album (by year, then title).
  Duplicate songs are played once.
- If an entry currently matches nothing (because I removed that music from the phone),
  keep it and show it greyed as "not on phone". It works again when the music comes back.
- Actions:
  - Create, rename and delete a playlist.
  - "Add to playlist..." from any song, album or artist (long-press or a menu button), and
    from Now Playing.
  - Remove an entry, move an entry up or down, play or shuffle a playlist.
- The playlist detail screen lists its entries: Artist and Album entries show their art
  and a song count, and you can tap one to see the songs it expands to.

## Audiobooks

- **One book = one folder with a `book.json`.** The PC script writes it. It's the source
  of truth for the display:
  ```json
  { "schema": 1, "id": "Author/Series/Book folder",
    "title": "Dune Messiah", "author": "Frank Herbert",
    "series": "Dune Chronicles", "seriesNumber": 2, "narrator": "", "year": "",
    "category": "", "cover": "cover.jpg",
    "files": [ { "name": "01.mp3", "label": "Chapter 1", "bytes": 123,
                 "chapters": [ { "title": "...", "startMs": 0 } ] } ],
    "sentAt": "2026-09-29T16:36:18" }
  ```
  - `files` is already in play order.
  - `chapters` inside a file is filled in only for m4b files that have chapter marks.
    Otherwise each file is one chapter, named by its `label`.
  - `cover` may be null. Then use the embedded art of the first file, then a plain
    placeholder.
  - `seriesNumber` may be null or a decimal (for example 2.5).
  - If `book.json` is missing or broken, fall back to the folder name and the file tags.
    Never crash.
- My library has 141 books, from single m4b files to 560-file MP3 books. The phone only
  holds the ones I send.
- **Library screen:** "Continue listening" goes at the top (books in progress, most
  recent first). Below it is the library. Group by author, and then by series in
  `seriesNumber` order (like the Reader's series grouping). Sort by Title, Author or
  Recently played, A–Z and Z–A. Show progress on each row (e.g. "43%" or "Finished").
- **Book screen:** cover, title, author, narrator, series with its number, total length,
  time left, the chapter list with the current chapter marked, Resume/Play, and
  "Mark finished" / "Start over".
- **Position memory (this matters most):** per book, save the file index, position in ms,
  playback speed, last-played time and a finished flag. Save every 10 seconds while
  playing, and immediately on pause, seek, chapter change, when the app goes to the
  background, and when the service is stopped or the task is removed. When I come back,
  rewind a little (3 s if paused under 10 minutes, 10 s if longer; make it a setting). A
  book counts as finished once I reach the last 30 seconds. Positions are keyed by the
  book `id`, so a book I remove and send again resumes where I was.
- Resolve a position into "Chapter 12 of 40, 14:32 left in chapter" for display.

## Now Playing and playback

- **Media3 ExoPlayer + `MediaSessionService`** (foreground service with a media
  notification). Playback keeps going with the screen off or in another tool. Bluetooth
  and headset buttons (play/pause/next/previous) work.
- **Audio focus:** use Media3's built-in handling (`handleAudioFocus = true`). Pause for
  calls and resume afterwards. Pause when headphones or Bluetooth disconnect
  (`setHandleAudioBecomingNoisy(true)`).
- **Music attributes:** `USAGE_MEDIA` / `AUDIO_CONTENT_TYPE_MUSIC`. **Audiobook
  attributes:** `AUDIO_CONTENT_TYPE_SPEECH`.
- **Music controls:** big album art, title, artist and album, a seek bar with times,
  previous / play-pause / next, shuffle, repeat (off, all, one), the queue, and Add to
  playlist.
- **Audiobook controls:** cover, book title, chapter name, a seek bar for the chapter
  (with the whole-book position underneath), back 15 s, forward 30 s, previous/next
  chapter, the chapter list, a speed button (0.75, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0; pitch
  corrected; remembered per book), and a sleep timer (15, 30, 45 or 60 min, or end of
  chapter).
- **Music resume:** save the current queue (as a list of paths plus where it came from:
  album, playlist, artist or all songs), the index, the position, and the shuffle and
  repeat state. Save every 5 seconds while playing and immediately on pause, skip or
  background. On launch, restore everything **paused** at the saved spot, so one tap on
  Play continues the song mid-track.
- Music and audiobooks keep **separate** resume points. Starting a book saves the music
  spot, and switching back to music restores it (and the same the other way).
- A small "now playing" bar sits at the bottom of every list screen. Tap it to open Now
  Playing.

## Best possible audio quality

- **Play the original files untouched.** No transcoding, no default EQ or effects, and
  no volume normalization unless I turn it on.
- **Formats:** MP3, AAC/M4A, M4B, FLAC, Ogg Vorbis, Opus and WAV, all through ExoPlayer's
  built-in extractors. Don't add the FFmpeg extension (it's a native build). If a file
  can't be decoded, show "Can't play this file" and skip to the next track. Never crash.
- **Gapless albums:** make sure ExoPlayer's gapless handling for MP3 (LAME/Xing header)
  and AAC (iTunSMPB) is active, and keep one player with a real playlist instead of
  recreating the player per track.
- Keep the output at the file's own sample rate. Don't force resampling.
- Offer **audio offload** as a setting (`setAudioOffloadPreferences`, enabled when the
  speed is 1.0). It saves battery on long audiobooks. Default it to off until I've tested
  it on my phone, and turn it off automatically if playback misbehaves.
- Show the file format and bitrate in small text on Now Playing (e.g. "MP3 · 190 kbps ·
  44.1 kHz").

## Album and book artwork

- Artwork is a first-class feature. The phone shows full color (I turned off LightOS's
  grayscale).
- Embedded art can be **2,800 px square**, so always decode it downscaled (`inSampleSize`
  or `ImageDecoder` with a target size): about 160 px for list rows and about 800 px for
  Now Playing. Cache thumbnails to disk by album or book key. Load them off the main
  thread. Keep a small memory LRU cache.
- **Music art:** use the embedded picture of the album's first track, then
  `cover.jpg`/`folder.jpg` in the album folder, then a placeholder with the album
  initial.
- **Book art:** use the `cover` from book.json, then the embedded picture, then a
  placeholder.

## Look and feel

- Match the Reader: the same color themes, typeface choice, spacing and row style, with
  large tap targets for the small screen. Keep it simple and quiet, in the LP3 spirit.
- Long names scroll or ellipsize. They never wrap into three lines.
- Scrolling must stay smooth with 1,400 songs. Use lazy lists and never read tags or
  decode art on the main thread.

## Things not to do

- Don't scan anything outside `/sdcard/Listen/`.
- Don't delete or modify audio files on the phone. The PC script handles adding and
  removing.
- Don't reach the internet. No online metadata or artwork lookups.
- Don't put copyrighted audio or my library in the repo.
