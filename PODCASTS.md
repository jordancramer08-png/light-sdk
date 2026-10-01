# PODCASTS.md — Podcast feature for Listen (LP3)

Read this file at the start of every session. It sits beside the existing CLAUDE.md;
it does not replace it. Where they disagree about podcasts, this file wins.

## What we're building

A Podcasts section inside the existing Listen tool (music + audiobooks) for the
Light Phone III, built on light-sdk. Music and audiobooks already work. Do not
change how they behave unless a session prompt says so.

### Features (all decided by Jordan)

1. **Shows list**: the podcasts he follows, each with its channel art.
2. **Show page**: channel art, title, author, and the show's full description at the
   top (shown as a few lines with a "More" button to expand), then every episode the RSS
   feed contains (the feed decides how far back that goes). Sort toggle: newest first /
   oldest first, remembered per show.
2a. **Episode page**: title, show name, date, length, the episode's full description
   (show notes), and "Has chapters" / "Has transcript" labels when available.
   **Descriptions (both show and episode):** use the fullest version the feed offers.
   Show: `itunes:summary` or `<description>`, whichever is longer. Episode:
   `content:encoded` first (usually the full show notes), then `<description>`, then
   `itunes:summary`. Feeds often put HTML in these, so convert it to clean readable
   text: keep paragraph breaks and list items, show link text (with the address after
   it if useful), drop images, scripts and styling, decode entities. Store the full text
   so it reads offline. If a feed truly has no description, show nothing rather than an
   empty box.
3. **New Episodes screen**: one list of unplayed episodes from all followed shows,
   newest first, filled in after each Refresh. Shows episodes published in the last
   30 days and after the show was followed (so following a show doesn't flood it with
   its back catalog). "Mark all played" clears it. Tools can't send notifications, so
   this screen stands in for them.
4. **Downloads**: tap to download an episode for offline listening; progress, cancel,
   retry; "Remove download" keeps the episode in the list but frees the space. Show a
   podcasts storage total. The episode's chapter file and transcript (if the feed has
   them) download along with the audio, so both work offline.
5. **No automatic background downloading.** Downloads only start when he taps one.
   (A download he started may keep running after he leaves the app; that's fine.)
6. **After finishing an episode**: a setting, "Delete download" or "Keep download".
   Default: Keep. Deleting removes the audio, chapter file and transcript together.
7. **Playback**: reuse Listen's existing player. Per-episode resume position.
   Episodes that aren't downloaded stream when Play is tapped (Jordan, 2026-10-01).
   No sleep timer for podcasts. Speed 1.0x–2.0x (steps of 0.1x, one global setting, pitch preserved). Skip back 15 s /
   forward 30 s. Channel art on Now Playing. Side volume buttons work as they already do
   in Listen.
8. **Played / unplayed marks**: automatic when an episode finishes (last 30 s or 95%),
   plus manual Mark Played / Mark Unplayed.
9. **Chapters**: when an episode has them, a Chapters list on Now Playing (title +
   start time; tap to jump), the current chapter title shown under the episode title,
   and previous/next-chapter buttons. Sources, in priority order:
   - `podcast:chapters` in the feed (Podcasting 2.0, a JSON file URL)
   - `psc:chapters` in the feed (Podlove Simple Chapters, inline)
   - ID3v2 `CHAP` frames embedded in the downloaded MP3 (read the tag header only,
     never load the whole file)
   Use chapter images only if cheap; titles and times are what matter. No chapters
   means no Chapters button, not an empty screen.
10. **Transcripts**: when the feed has `podcast:transcript`, a Transcript screen
    reachable from Now Playing and from episode details. Prefer timed formats in this
    order: `text/vtt`, `application/x-subrip` (SRT), `application/json`, then
    `text/html`, `text/plain`. For timed transcripts: highlight the current line and
    keep it in view while playing, and tap a line to jump there. Untimed transcripts
    are plain readable text. Use readable LP3-sized text and paging/scrolling that
    performs well on long transcripts (lay out only what's on screen). Many shows
    don't publish transcripts; that's expected, not an error.
11. **Finding shows, two routes, one subscription list:**
    - On the phone: search Apple's iTunes Search API
      (`https://itunes.apple.com/search?media=podcast&entity=podcast&term=...`), no key.
      Results show title, author, art; tap Follow. Result field `feedUrl` is the RSS feed.
      Also an "Add by feed URL" fallback. Unfollowing asks whether to delete that show's
      downloads. Keep searches light (Apple asks for about 20 calls a minute max).
    - On the laptop: `Podcasts.cmd` (menu-driven, like the music sync .cmd): search &
      follow, import an OPML file, add a private feed URL, list/unfollow, and sync the
      list with the phone. It must pull the phone's current list first and merge, so
      shows followed on the phone are never lost.

## How Jordan works

- Windows laptop, PowerShell, Claude Code. **No Android Studio, no emulator** (laptop
  has 7.6 GB RAM). Build with Gradle from the command line, install to the physical LP3
  with adb, and verify on the phone.
- He is newer to app development. Explain what you're about to do in plain language,
  give him exact commands when he has to do something, and tell him exactly what to
  check on the phone at the end of each session.
- Anything that changes files on his PC (sync, import, cleanup) is delivered as a
  double-click `.cmd` file.
- Sessions are large on purpose. Work in the listed order inside a session, build and
  install at sensible checkpoints, and if a session is running long, stop at a working
  checkpoint, commit, and tell him what's left rather than rushing.
- Commit at the end of every session with a clear message. Work on the `podcasts`
  branch; never force-push.

## Platform rules that matter here (light-sdk / LP3)

- Kotlin + Compose only, inside `LightScreen` / `LightViewModel`. No `.java`, no
  reflection (including `.javaClass`), no `android.content.Context`, no
  `android.app.*` imports. Dependencies and permissions must be on the plugin's
  allowlist. OkHttp is allowlisted; use it for feeds, search, chapters, transcripts and
  downloads. kotlinx-serialization-json is on the classpath for chapter JSON and
  JSON transcripts.
- **Cleartext HTTP is off** (the plugin generates the manifest). Many podcast feeds and
  enclosure links are `http://`. Strategy: rewrite to `https://` and try; if that fails,
  mark the episode/feed "needs secure link" with a clear message rather than crashing.
  The laptop `.cmd` can fetch http feeds fine, so note that as the workaround.
- Enclosure links usually pass through tracking redirects (podtrac, etc.). Follow them.
- **Memory limit is about 128 MB.** Stream downloads straight to disk in chunks; never
  hold an episode in memory. Downscale channel art when saving (e.g. to ~600 px) because
  feeds often ship 3000×3000 images. Read ID3 chapter frames from the file header only.
- Treat every feed, chapter file and transcript as hostile input: reuse the hardened XML
  approach from the Reader project if it's available (no external entities/DTDs,
  numeric-entity rewrite, size caps), and cap transcript/chapter file sizes. Never
  render description HTML as live HTML; always convert it to plain text first. Handle
  missing `guid` (fall back to enclosure URL), odd date formats, missing durations, and
  `itunes:image` vs `<image>`.
- No notifications exist for Tools. Don't build any.
- The Android back gesture can pop a screen without asking the Tool. Save positions and
  state in `onScreenHide` / `onAppPause`, and on a timer while playing.
- Background work goes through `LightWork` (WorkManager wrapper). Use it only to keep a
  user-started download running.
- Handle hardware keys on key-up as well as key-down, or LightOS also reacts to them.

## Storage layout (confirmed and adjusted in Session 0; details in PODCAST_PLAN.md §8)

The `filesDir` proposal doesn't fit Listen: the PC can't reach `filesDir` on the release
build (no `run-as`), CLAUDE.md keeps app state on `/sdcard/Listen/.state/`, and the sync
.cmd's restore only pushes top-level `.state/` files. So:

```
/sdcard/Listen/.state/podcasts.json            followed shows (showId, title, author, feedUrl, previous URLs, followedAt, unfollowedAt, sort)
/sdcard/Listen/.state/podcast_episodes.json    per-episode state keyed "showId/episodeId" (position, played, playedAt, lastPlayedAt)
/sdcard/Listen/.state/settings.json            existing file + podcastSpeed, afterFinish, newWindowDays
/sdcard/Listen/.state/podcasts_from_pc.json    inbox written by Podcasts.cmd; the app merges it, then deletes it
/sdcard/Listen/Podcasts/<showId>/art.jpg       downscaled channel art (~600 px)
/sdcard/Listen/Podcasts/<showId>/feed.json     parsed episode list (no long text)
/sdcard/Listen/Podcasts/<showId>/notes.json    show + episode descriptions as plain text
/sdcard/Listen/Podcasts/<showId>/<episodeId>.<ext>                 downloaded audio (.part while downloading)
/sdcard/Listen/Podcasts/<showId>/<episodeId>.chapters.json         normalized chapters (from any source)
/sdcard/Listen/Podcasts/<showId>/<episodeId>.transcript.<ext>      transcript as downloaded
```

`showId` = first 12 hex chars of SHA-1 of the normalized feed URL, fixed when followed (it
survives feed moves). `episodeId` = same hash of `guid`, else of the enclosure URL.

## Session log

Add a line here at the end of each session: date, what was built, what Jordan verified
on the phone, anything left open.

- 2026-10-01, Session 0: plan only (PODCAST_PLAN.md). Storage layout adjusted to /sdcard (above). No app code. Open questions for Jordan are in PODCAST_PLAN.md §11.
- 2026-10-01, Session 0 answers: streaming yes; no podcast sleep timer; no OPML export available (shows come in by search); internet allowed for Podcasts only (CLAUDE.md updated).
