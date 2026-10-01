# Podcasts for Listen: build plan

Written in Podcasts Session 0 (2026-10-01), after reading CLAUDE.md, PODCASTS.md, PLAN.md,
NOTES.md, the Listen code on the `podcasts` branch (same as `listen` at `c7d704a`), the
Reader on `origin/reader`, the SDK in this repo, and `D:\Music\Listen-Phone-Sync.cmd`.

`PODCASTS.md` says *what* to build. This file says *how*, and in which order. No app code
was written in this session.

---

## 1. How Listen is built today, and where Podcasts fits

### Screens

All screens live in `tool/src/main/kotlin/com/thelightphone/listen/`. Each one extends
`ListenScreen`, which extends `VolumeKeyScreen`. Because of that, every new podcast screen
gets the volume-button handling and the 2-second volume bar for free.

```
HomeScreen (@InitialScreen)      Music / Audiobooks / Now Playing, gear → Settings
├─ music/MusicHomeScreen         Songs / Artists / Albums / Playlists, magnifier → SearchScreen
├─ books/BooksScreen             Continue listening + library → BookScreen → ChaptersScreen
├─ playback/MusicNowPlayingScreen or BookNowPlayingScreen
└─ settings/SettingsScreen       theme, rewind amounts, audio offload
ui/NowPlayingBar                 small bar at the bottom of every list screen
```

**Where Podcasts goes:** a new `MenuRow("Podcasts")` on `HomeScreen`, between Audiobooks
and Now Playing. Its detail line shows the number of new episodes ("3 new"), like the
Audiobooks row shows the book count. The new code goes in its own `podcasts/` package next
to `music/` and `books/`.

### The player

- `playback/PlaybackHub.kt` (857 lines) is the single owner of the SDK's one detached
  `LightAudioPlayer`. It runs Media3 ExoPlayer inside the SDK's `MediaSessionService`, so
  playback carries on with the screen off, in other tools, and from Bluetooth buttons.
- It has two modes today: **music** (a queue of songs) and **book** (a book's files plus its
  chapter list). They keep separate resume points.
- Podcasts become a **third mode, "episode"**. It's modelled closely on book mode: a
  single file, a chapter list, speech audio attributes, −15 s / +30 s, and a position saved
  every 10 s and on every pause, seek, background and stop. Starting an episode saves the
  music or book spot first, the same way starting a book saves the music spot today.

### Where files and positions are stored

| What | Where |
|---|---|
| Music, audiobooks | `/sdcard/Listen/Music/…`, `/sdcard/Listen/Audiobooks/…` (written by the PC script) |
| Playlists, music resume, book positions, settings | `/sdcard/Listen/.state/*.json`, written atomically (`.tmp` then rename) by `storage/AtomicFile.kt` |
| Tag index, art thumbnails | `filesDir/cache/` (private, rebuildable) |

All paths are in `storage/ListenPaths.kt`. The music and book scanners only walk `Music/`
and `Audiobooks/`, so a new `Podcasts/` folder beside them is never scanned as music.

---

## 2. How the music sync .cmd gets files onto the phone

`D:\Music\Listen-Phone-Sync.cmd` is a `.cmd` wrapper. It reads its own file, finds the line
`#BEGIN-POWERSHELL#`, and runs everything after it as PowerShell. Inside:

- **`Find-Adb`** looks for `adb.exe` on PATH, in the Android SDK and in common folders.
  **`Connect-Phone`** picks the first device from `adb devices` and explains "unauthorized".
- **`Invoke-Adb`** runs adb with `-s <serial>` and returns the exit code and output without
  throwing.
- Files go over with plain **`adb push <pc file> /sdcard/Listen/...`** and come back with
  **`adb pull`**. Backup (option 7) runs `adb pull /sdcard/Listen/.state/. <backup folder>`.
  Restore (option 8) pushes each file **at the top level** of a backup folder back into
  `.state/`, then runs `am force-stop com.thelightphone.listen`.
- It writes `/sdcard/Listen/.state/last-sync.txt` after every change, and the app rescans
  when that file changes.
- It never uses `run-as`. That matters, because `run-as` only works on debug builds, and
  `Build and Install Listen.cmd` installs the **release** build. So anything the PC needs
  to read or write must be on `/sdcard`, not in the app's private folder.

**What Podcasts.cmd will do:** copy the same wrapper and the same helpers (`Find-Adb`,
`Connect-Phone`, `Invoke-Adb`, `Q`, `Select-FromList`, `Parse-Numbers`, `Read-YesNo`, the
colors). For the subscription list:

1. `adb pull /sdcard/Listen/.state/podcasts.json` (the phone's current list).
2. Merge it with the PC's list (see §8 for the merge rule).
3. `adb push` the result to `/sdcard/Listen/.state/podcasts_from_pc.json` (an "inbox"
   file). It does **not** overwrite `podcasts.json` directly.
4. Listen merges the inbox into its own list the next time it opens or comes back to the
   front, then deletes the inbox.

The inbox matters because Listen may be open with the list in memory. If the PC overwrote
`podcasts.json` and Listen then saved, the PC's changes would be lost. With an inbox, both
sides only ever add to each other.

---

## 3. Internet access in lighttool.toml

- **`tool/lighttool.toml` does not declare internet access.** Its permissions are
  `MANAGE_EXTERNAL_STORAGE` and `WAKE_LOCK`.
- **But the installed app already has it.** The SDK pulls in Ktor and OkHttp 5.3.2, and
  OkHttp's library manifest adds `android.permission.INTERNET`. Media3 adds
  `ACCESS_NETWORK_STATE`. Both show up in the merged manifest (checked in
  `tool/build/intermediates/.../manifest-merger-blame-release-report.txt`). Listen has
  simply never used them.
- `INTERNET` and `ACCESS_NETWORK_STATE` are both on the plugin's allowlist
  (`plugin/.../LightToolMetadata.kt`).

**Changes:**

| Change | Why |
|---|---|
| Add `"android.permission.INTERNET"` and `"android.permission.ACCESS_NETWORK_STATE"` to `permissions` in `lighttool.toml` | Says honestly what the tool does, and doesn't depend on a library's manifest |
| Use **OkHttp** directly. It's already on the tool's compile classpath through `:sdk:client` (checked with `gradlew :tool:dependencies`), so no new dependency is needed. | Feeds, the iTunes search, chapter JSON, transcripts, art and downloads |
| Update CLAUDE.md's "Don't reach the internet" rule to "only for Podcasts, and only when Jordan asks (Refresh, search, download)" | The rule is still right for music and books, which must stay offline. Jordan approves the wording in Session 1. |

**Cleartext (plain `http://`) stays blocked.** The target SDK is 36, and the plugin writes
no `usesCleartextTraffic` or network security config, so Android refuses plain `http://`.
The plan:

- Rewrite every `http://` feed, enclosure, art, chapter and transcript URL to `https://`
  before trying it.
- **Follow redirects ourselves** (OkHttp `followRedirects(false)`, a loop of up to 10 hops).
  Tracking redirects (podtrac, chartable and so on) sometimes bounce to an `http://`
  address. OkHttp would then fail with "CLEARTEXT communication not permitted", so each
  `http://` hop is upgraded to `https://` before following it.
- If the `https` version still fails, mark the feed or episode **"Needs a secure link"**
  with a short explanation. Podcasts.cmd can fetch `http` feeds fine on the PC, so that's
  the workaround for a feed.
- **Fallback, only if real feeds need it:** an "SDK: allow cleartext for a tool" commit
  (a `cleartext = true` key in lighttool.toml that the plugin turns into
  `android:usesCleartextTraffic`). Jordan has approved SDK edits in general, but this one
  lowers security, so **ask him first**, and only after Session 3 shows how many of his
  feeds actually fail.

**Check first on the phone (Session 1):** that LightOS lets a sideloaded tool reach the
internet at all. Listen has never tried. If LightOS blocks it, stop and tell Jordan.

---

## 4. Text entry in light-sdk (the search box)

There is no inline editable text field. The SDK offers two patterns, and Listen already
uses both:

1. **`LightTextInputEditor`** (`sdk/ui/.../LightTextInputEditor.kt:48`): a full-screen
   editor with a back button, a big underlined input, the LP3 keyboard and a submit button.
   Listen uses it for playlist names (`playlists/PlaylistDialogs.kt`).
   **Use for:** "Add by feed URL".
2. **`rememberLightKeyboard(state, rememberKeyboardOptions(), key, onReturn = …)`** plus
   **`LightEmbeddedLp3Keyboard`**: lets a screen put the keyboard under its own layout. We
   added this to the SDK in Session 8A for music search (`music/SearchScreen.kt`): a
   `TextFieldState` box at the top, live results between, the keyboard at the bottom, and
   DONE hides the keyboard.
   **Use for:** podcast search. Copy `SearchScreen`'s structure.

One difference from music search: **don't search on every keystroke.** Apple asks for about
20 calls a minute at most. Search when DONE or Return is pressed (and optionally after a
1-second pause in typing), and cache the last few results in memory.

The keyboard is `EnQwertyLp3KeyboardViewModel`. Typing a long feed URL on it will be slow,
and we can't be sure of every symbol (`?`, `=`, `&`) until we try it on the phone. That's
why Podcasts.cmd is the main way to add private feed URLs. The on-phone "Add by URL" is the
fallback.

---

## 5. Can the Reader's XML parser be reused?

**Partly. Copy it in and adapt it. Don't use it as it is.**

What the Reader has (`origin/reader:tool/src/main/kotlin/com/thelightphone/reader/epub/`):

- `MarkupParser.kt`: `tokenizeMarkup` is a hand-written, tolerant tag tokenizer, plus
  `decodeEntities` (a named-entity table plus `&#NNN;` and `&#xHH;`) and a small tree
  (`parseXml`, `firstChild`, `allDescendants`, `allText`).
- `HtmlText.kt`: `extractText`/`extractStyledText` flatten HTML to plain paragraphs, drop
  `<script>` and `<style>`, and turn `<br>` and block tags into paragraph breaks.

What's good about it for hostile feeds:

- It **never processes DTDs or entities beyond its fixed table**. `<!DOCTYPE …>` and
  `<!ENTITY …>` are skipped as `<!…>`. So external-entity (XXE) and "billion laughs"
  attacks simply can't happen. That's the main hardening we want, and it comes free.
- It's pure Kotlin with no Android classes, so it runs in JVM unit tests.

What has to change for podcasts (and what's missing compared with PODCASTS.md's "hardened"
description; there are no size caps or numeric-entity guards in the Reader):

| Problem | Fix |
|---|---|
| **It strips namespace prefixes** (`itunes:image` → `image`, `content:encoded` → `encoded`, and both `podcast:chapters` and `psc:chapters` → `chapters`). Podcast feeds depend on those prefixes. | Keep the full `prefix:name`. Read the `xmlns:` declarations and map each prefix to its namespace URI, so a feed that uses an odd prefix still works. |
| `&#x110000;` or `&#xD800;` makes `Character.toChars` **throw**, despite the "never throws" comment | Replace invalid code points with U+FFFD (the "numeric-entity rewrite") |
| It builds **the whole token list and tree in memory**. A 15 MB feed would mean roughly 100 MB of objects, close to the ~128 MB limit. | Turn the tokenizer into a streaming one (callbacks or a token iterator over the text) and parse one `<item>` at a time |
| `allDescendants`/`firstDescendant` recurse, so a deeply nested hostile document could overflow the stack | The feed parser walks a fixed shape (`rss/channel/item`), and nesting depth is capped (e.g. 64) |
| No size caps | Feed ≤ 20 MB downloaded, chapters JSON ≤ 1 MB, transcript ≤ 5 MB, single description ≤ 200 KB, attributes ≤ 8 KB. Stop reading at the cap and say "Feed too large" rather than running out of memory. |
| HTML → text drops link addresses and list bullets | Add "• " before `<li>`, and show "link text (address)" when the address isn't already the text; drop `<img>`, `<iframe>`, `<script>`, `<style>` |

The feed must be read as bytes and decoded by the XML declaration's encoding (UTF-8
default). It must also cope with a byte-order mark, CDATA (already handled), `guid`
missing (fall back to the enclosure URL), RFC 822 dates in their many forms (parse with
`java.time` and several patterns; give up gracefully and sort undated episodes last),
`itunes:duration` as seconds, `MM:SS` or `HH:MM:SS`, and `itunes:image href=` vs
`<image><url>`.

New files: `podcasts/xml/Markup.kt` (the adapted tokenizer and entities),
`podcasts/xml/HtmlToText.kt`, and `podcasts/feed/FeedParser.kt`, each with JVM tests that
use **synthetic** feeds only (no real show notes in the repo).

---

## 6. Speed without changing pitch, and how position is reported

**Speed: yes, pitch is preserved.** `LightAudioPlayer.speed` sets
`PlaybackParameters(speed)`, which leaves pitch at 1.0. ExoPlayer then uses its built-in
Sonic time-stretcher, so voices don't go squeaky. Audiobooks already use this (0.75×–2.0×),
and Jordan checked it on the phone in Session 6. Podcasts just need a different list of
steps: 1.0, 1.1 … 2.0, **one global setting** (saved in `settings.json`), not per episode.
`PlaybackHub.applyAudioOffload()` already turns offload off whenever speed isn't 1.0.

**Position reporting:**

- `LightAudioPlayer.positionMs` is a `StateFlow<Long>` that the SDK updates **every 250 ms
  while playing** (`POSITION_POLL_MS`), plus `durationMs`, `isPlaying` and
  `currentMediaItemIndex`. `PlaybackHub` mirrors these as its own `positionMs`, `index` and
  so on.
- The position is in **media time** (the place in the file), not wall time, so it stays
  correct at 1.5× or 2×. Chapter and transcript times are in media time too, so they
  compare directly.
- **Chapters:** `PlaybackHub` already combines `chapters`, `index` and `positionMs` into
  the current-chapter index (`chapterIndexAt`). An episode is a one-file book: its chapters
  become `BookChapter(fileIndex = 0, startMs = …)`, and the same code works, including
  previous/next chapter and the end-of-chapter sleep timer.
- **Transcripts:** 250 ms is plenty, since cues last seconds. The transcript screen finds
  the current cue with a binary search over sorted start times whenever `positionMs`
  changes, and only scrolls when the cue changes. Tapping a line calls
  `PlaybackHub.seekTo(cue.startMs)`.

No SDK change is needed for either.

---

## 7. ID3 tags and CHAP frames

- **Listen reads tags, but not in a way that can see chapters.** `music/TagReader.kt` uses
  Android's `MediaMetadataRetriever`, which gives title, artist, album, track, disc, year
  and duration (plus the picture, in `ArtworkCache`). It has **no API for ID3 `CHAP`/`CTOC`
  frames**. Books get their m4b chapters from the PC (ffprobe writes them into book.json),
  so the app has never needed to parse chapters itself.
- Media3 can decode CHAP frames (`Id3Decoder`/`ChapterFrame` in `media3-extractor`), but
  the tool doesn't have Media3 on its compile classpath (the SDK keeps it `implementation`),
  and `Id3Decoder` wants the whole tag in memory, which can be several MB with cover art.
- **Plan: a small pure-Kotlin reader, `podcasts/chapters/Id3Chapters.kt`** (about 150
  lines, with JVM tests on synthetic byte arrays):
  1. Open the file with `RandomAccessFile` and read the 10-byte header. Stop if it isn't
     `ID3` v2.3 or v2.4.
  2. Read the tag size (synchsafe), and cap it (say 16 MB, since it's only walked, never
     loaded whole).
  3. Walk the frames one header at a time. Sizes are plain in v2.3 and synchsafe in v2.4.
     Handle the extended header and the unsynchronisation flag. **Skip** every frame that
     isn't `CHAP` or `CTOC` by seeking past it (so a 3 MB `APIC` image is never read).
  4. For each `CHAP`, read the element id, start ms, end ms, and the `TIT2` sub-frame for
     the title (in any of the 4 text encodings). Ignore chapter images (not cheap).
  5. Order by `CTOC` if there is one, otherwise by start time.
- It runs once, right after a download finishes, and only if the feed had no chapters. The
  result is saved as `<episodeId>.chapters.json` in the same format as feed chapters, so
  Now Playing only ever reads one format.

---

## 8. Storage layout: adjusted

The PODCASTS.md proposal puts everything in `filesDir/podcasts/`. **That doesn't work for
Listen, for three reasons:**

1. **The PC can't reach `filesDir`.** Jordan installs the release build, so `run-as` fails,
   and Podcasts.cmd couldn't sync the subscription list.
2. **CLAUDE.md requires app state on `/sdcard/Listen/.state/`** so that an uninstall or a
   new signing key can't wipe it. Played marks and positions are exactly that kind of state.
3. **Backup and restore** (options 7 and 8 of the sync .cmd) only cover `.state/`, and
   restore only pushes files **at the top level** of the backup. A `.state/podcasts/`
   subfolder would be backed up but never restored.

At the same time, downloaded audio must **not** go in `.state/`, or every backup would copy
gigabytes of episodes.

**Adjusted layout:**

```
PRECIOUS (backed up by option 7, flat files in .state/)
/sdcard/Listen/.state/podcasts.json           followed shows: showId, title, author, feedUrl,
                                              (also previous feed URLs), followedAt,
                                              unfollowedAt (a "tombstone" so merges don't
                                              bring a show back), sort (newest/oldest)
/sdcard/Listen/.state/podcast_episodes.json   per episode, keyed "showId/episodeId":
                                              positionMs, played, playedAt, lastPlayedAt.
                                              Only episodes he has touched get a row.
/sdcard/Listen/.state/settings.json           (existing file) gains podcastSpeed,
                                              afterFinish = "keep" | "delete", newWindowDays
/sdcard/Listen/.state/podcasts_from_pc.json   inbox from Podcasts.cmd; merged, then deleted

REBUILDABLE (re-made by Refresh or a new download; never in backups)
/sdcard/Listen/Podcasts/<showId>/art.jpg                    channel art, downscaled to ~600 px
/sdcard/Listen/Podcasts/<showId>/feed.json                  parsed episode list: id, guid,
                                                            title, date, duration, enclosure
                                                            URL/type/size, chapter and
                                                            transcript URLs, flags
/sdcard/Listen/Podcasts/<showId>/notes.json                 the show's and every episode's
                                                            full description as plain text
                                                            (kept apart so lists load fast)
/sdcard/Listen/Podcasts/<showId>/<episodeId>.<ext>          downloaded audio (.part while
                                                            downloading)
/sdcard/Listen/Podcasts/<showId>/<episodeId>.chapters.json  normalized chapters (any source)
/sdcard/Listen/Podcasts/<showId>/<episodeId>.transcript.<ext>  transcript as downloaded

CACHE (private, as now)
filesDir/cache/…                                            160 px list thumbnails (ArtworkCache)
```

Why `/sdcard/Listen/Podcasts/` and not `filesDir` for downloads: it survives an uninstall
(re-downloading 5 GB of episodes after a reinstall would be painful), the PC's "Show what's
on the phone" can count it, and a ".nomedia" file there keeps other apps from indexing it.
Nothing else scans that folder.

**IDs:**

- `showId` = the first 12 hex characters of SHA-1 of the **normalized** feed URL
  (lowercase scheme and host, `http`→`https`, no trailing `/`). It's assigned **once, when
  the show is followed**, and never changes, even if the feed moves
  (`itunes:new-feed-url`, `podcast:newFeedUrl`, or a permanent 301). The new URL is stored,
  and the old one is kept in the list of previous URLs for matching. Podcasts.cmd computes
  the same hash, so a show followed on both sides ends up with the same id.
- `episodeId` = the first 12 hex characters of SHA-1 of `guid`, or of the enclosure URL
  when there's no guid. Only the phone needs it.

**Merging the subscription lists** (phone ↔ PC, the same rule on both sides): match shows
by `showId` or by any known normalized feed URL. For each show, the latest of
`followedAt`/`unfollowedAt` wins. Titles and art come from whichever side refreshed most
recently. Nothing is ever deleted outright, only marked unfollowed, so a show followed on
the phone can never be lost by a PC sync, and an unfollow can't be undone by a stale copy.

**Memory note:** `feed.json` holds no long text. A show with 1,500 episodes is about
0.5 MB and loads quickly. `notes.json` is read only when a show or episode page opens.

---

## 9. Downloads: how they keep running

- `LightWork` wraps WorkManager. Three limits matter: a normal worker is **stopped after
  about 10 minutes**, `enqueue` uses **`REPLACE`** (enqueuing the same key again cancels a
  running job), and there's no progress API.
- Design:
  - A `PodcastDownloads` singleton keeps a queue (also written to `filesDir`, so it
    survives a restart) and exposes progress as a `StateFlow`. Screens show it, and it
    works because the worker runs in the same process.
  - Downloads stream straight to `<episodeId>.<ext>.part` with OkHttp in 64 KB chunks, and
    are **resumable** with an HTTP `Range` header. The `.part` file is renamed when it's
    complete and its size matches. Chapters and transcript download next, then the ID3
    chapters check runs.
  - One `@LightJob("podcast-downloads")` processes the queue for up to about 8 minutes, then
    returns `Retry` if anything is left (it picks up where the `.part` stopped). It's only
    enqueued when it isn't already running or enqueued, so `REPLACE` never cancels a
    running download.
  - Before starting, check free space (keep 1 GB spare, like the PC script).
- Only started by a tap (PODCASTS.md rule 5). No periodic job, no automatic downloading.
- **If the 10-minute limit proves a problem on the phone** (stuttering, long gaps between
  retries), the fix is an "SDK: let LightWork run as expedited/foreground work" commit.
  That's already within Jordan's SDK approval, but tell him before doing it.

---

## 10. Session-by-session plan

Same rhythm as BUILD_GUIDE.md: one session at a time; build, install, and let Jordan test on
the phone; commit at the end of each session that works. These are the **Podcasts
sessions P1 to P9** (this plan was P0). Every start prompt begins with
"Read CLAUDE.md, PODCASTS.md, PODCAST_PLAN.md and NOTES.md first. I am a beginner."

| # | Session | Builds | Jordan checks on the phone | Size |
|---|---|---|---|---|
| P1 | **Foundations: network + feed parsing** | Internet permission; check LightOS allows network; OkHttp client with http→https upgrade, manual redirects, size caps and timeouts; adapted XML tokenizer; `FeedParser` (all fields in PODCASTS.md); HTML→text; storage files (`podcasts.json`, `feed.json`, `notes.json`) with atomic writes; Podcasts row on Home; **Add by feed URL**; plain Shows list with art | Add one feed by URL. The show appears with its art. Airplane mode gives a friendly "No connection", not a crash. | **Large.** If it runs long, stop after the parser and tests (no UI) and commit. |
| P2 | **Podcasts.cmd (PC)** | The new .cmd: search Apple and follow, **OPML import** (his old app can probably export one), add private feed URL (fetches http feeds fine), list/unfollow, sync (pull, merge, push inbox). App side: merge the inbox on launch/resume. | Import OPML on the PC, sync, open Listen: all shows appear. Follow one on the phone, sync again: it's still there. | Medium. Early on purpose, so he can load his real shows without typing URLs on the phone. |
| P3 | **Show + episode pages, Refresh, New Episodes** | Show page (art, title, author, description with "More", every episode, newest/oldest toggle remembered per show); Episode page (title, show, date, length, full notes, "Has chapters"/"Has transcript"); Refresh (all shows, in the background, with an "Updating…" line); New Episodes (last 30 days and after followedAt, unplayed, newest first, "Mark all played"); manual Mark played/unplayed; "Needs a secure link" message | Long show notes read cleanly (paragraphs, bullets, no HTML junk). New Episodes doesn't flood with back catalog. | Medium-large |
| P4 | **Downloads** | Download queue, progress, cancel, retry, resume after interruption, `LightWork` job, "Remove download", podcast storage total, free-space check; chapters file and transcript come along | Download a 1-hour episode, leave Listen, come back: it finished. Turn Wi-Fi off mid-download, on again, Retry: it continues, not restarts. | Medium-large |
| P5 | **Playback** | Episode mode in `PlaybackHub` (separate resume point, speech attributes, position saved every 10 s and on pause/seek/background/stop); podcast Now Playing (channel art, −15/+30, speed 1.0–2.0 by 0.1, global); auto-played at last 30 s or 95%; after-finish Keep/Delete setting; now-playing bar and Home row show the episode | Play, swipe Listen away, reopen: same spot. Switch to music and back: each keeps its place. Speed 1.7× sounds natural. Finish an episode: marked played (and deleted if set). | **Large and the riskiest.** `PlaybackHub` is 857 lines and music and books must not change. If it runs long, stop once playback and resume work, and commit; move speed and after-finish to P6. |
| P6 | **Chapters** | `podcast:chapters` JSON, `psc:chapters`, ID3 `CHAP` reader; normalized `chapters.json`; Chapters list on Now Playing, current chapter under the title, previous/next chapter (hidden when there are no chapters) | An episode with chapters shows them and jumps correctly. One without shows no Chapters button. | Medium |
| P7 | **Transcripts** | VTT, SRT, Podcasting 2.0 JSON, HTML and plain-text parsers (capped sizes); Transcript screen from Now Playing and Episode page; lazy list; current line highlighted and kept in view; tap a line to jump | A long timed transcript scrolls smoothly and follows along; tapping a line jumps the audio. | Medium |
| P8 | **Search on the phone** | Search screen (keyboard + results with art), iTunes API with the 20-a-minute limit, Follow; Unfollow (asks whether to delete that show's downloads) | Search "ask pastor john", follow it, it appears in Shows. Unfollow asks about downloads. | Medium |
| P9 | **Polish + speed check** | Memory check with a 1,500-episode feed and a 3-hour transcript; empty-state messages; long titles ellipsize; volume keys on every new screen; NOTES.md final podcast summary; backup/restore covers podcast state | His full show list refreshes without stalls; backup and restore keep played marks. | Small-medium |

**Flagged as too big for one session if done as PODCASTS.md lists it:**

- **Playback + chapters + transcripts** in one session: split into P5, P6 and P7 above.
- **Search on the phone + the PC script** together: split into P2 and P8.
- **P1** and **P5** are still the largest. Each has a named stopping point above.

---

## 11. Open questions for Jordan

1. **Streaming.** PODCASTS.md only mentions downloaded playback. Should tapping Play on an
   episode that isn't downloaded stream it? (Easy: the SDK already has `UrlSource`. It uses
   data, and the same http→https rule applies.) The plan assumes **downloads only** unless
   you say otherwise.
2. **Sleep timer for podcasts.** It comes almost free from the book code. Include it in P5?
3. **New Episodes window:** "last 30 days" is fixed in PODCASTS.md. It's stored as a
   setting (`newWindowDays`) in case you ever want to change it, but there's no screen for
   it unless you ask.
4. **CLAUDE.md's "Don't reach the internet" rule** needs a podcast exception. P1 proposes
   the wording for your OK.
5. **Your old app:** does it export OPML? If so, P2's import moves your shows over in one
   step.
