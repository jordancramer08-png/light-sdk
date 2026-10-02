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
4a. **Streaming** (added 2026-10-01): an episode that isn't downloaded can be played
   straight away with **Stream** (or "Resume (stream)"), over Wi-Fi or mobile data, with
   the same resume position, speed, played marks and controls as a downloaded one.
   Listen follows the enclosure's tracking redirects itself (each hop upgraded to https)
   and hands the player the final address; the network is kept awake while a stream plays
   with the screen off. Chapters come from the feed; the transcript is read online. Now
   Playing says "Streaming". "No connection" is only shown when the phone really is
   offline; if a server can't be reached, it says so instead. Streaming never saves
   partial files.
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
- 2026-10-01, P1a (logic only, no UI): podcasts/ package: hardened streaming XML reader, feed parser, HTML-to-text, http→https + redirect handling (OkHttp), iTunes search + rate limit, subscription store with PC-inbox merge, episode state store, New Episodes query, chapters (podcast:chapters JSON, psc, ID3 CHAP), transcripts (VTT, SRT, JSON, HTML, text). 75 new unit tests, all 152 pass; nothing installed on the phone yet. Stores use the /sdcard/Listen/.state layout from PODCAST_PLAN.md §8, not filesDir (the session prompt said filesDir; flagged to Jordan).
- 2026-10-01, P1b: Podcasts row on Home, Podcasts (shows) screen with channel art, Add by feed address; internet permission declared (v1.3.0). Jordan verified on the phone: added Planet Money (art shown), "not a podcast feed" and airplane-mode "Can't connect" messages, music and audiobooks unchanged. 153 tests pass.
- 2026-10-01, P3 + P8: Podcasts screen (New Episodes entry, REFRESH, search), New Episodes (Mark all played), show page (description with More, sort remembered per show, Unfollow), episode page (clean notes, chapters/transcript labels, Mark played), search with Apple's directory (follow, unfollow asks about downloads, add by feed address). v1.4.0. Jordan verified on the phone. 156 tests pass. Still to do: P2 Podcasts.cmd, P4 downloads, P5 playback, P6 chapters, P7 transcripts, P9 polish.
- 2026-10-01, P4: downloads (resumable, chunked to disk, one LightWork job per episode, Cancel/Retry, Remove download, chapters + transcript saved with the audio, ID3 chapters as fallback, storage total on the Podcasts screen). v1.5.0. Found and fixed an SDK bug (LightWork job input arrived as "key=value"), committed separately. Jordan verified on the phone, including leaving mid-download, Cancel and airplane-mode Retry. 166 app tests + 1 SDK test pass.
- 2026-10-01, P5 + P6 + P7 (downloaded episodes): podcast playback through the book path (separate place per episode in podcast_episodes.json, one global speed 1.0-2.0, -15/+30, rewind after a pause, auto-played at last 30 s / 95%, After finishing: Keep/Delete download), podcast Now Playing with channel art, chapters list with start times and prev/next chapter (hidden without chapters), transcript screen (timed: marks and follows the spoken line, tap to jump; untimed: plain text; lazy list). Bracketed speaker labels handled, machine labels hidden. v1.6.0. Jordan verified on the phone (Changelog: feed chapters + HTML transcript; Friends From Work: timed VTT). 167 tests pass. Streaming (decided yes) is not built yet.
- 2026-10-01, streaming (v1.7.0, NOT committed; work in progress on the podcasts branch): built Stream for episodes that aren't downloaded, plus an SDK change (keep the network awake while a stream plays). Jordan's test: steps 1, 2 and 4-7 not finished yet. Step 3 FAILED: on mobile data (Wi-Fi off), both Stream and Download show "No connection. Download the episode to listen offline." To look at next session: read the phone's log (adb logcat) while reproducing on mobile data. Note: that exact message comes from the stream path; a failed download normally says "Can't connect. Check Wi-Fi or mobile data.", so check which screen and button showed it.
- 2026-10-02, streaming finished (v1.7.1): Stream for episodes that aren't downloaded (redirects resolved to https first; feed chapters; transcript online; "Streaming" line on Now Playing), on Wi-Fi or mobile data. Last night's mobile-data failure couldn't be reproduced (Refresh, Stream and Download all worked on data only); the real flaw was that any failed connection said "No connection". Now: one retry after 2 s (covers the Wi-Fi to data switch), and "No connection" only when Android says the phone is offline (new SDK helper), otherwise "Couldn't reach the show's server". Causes are logged. Two SDK commits (stream wake mode, online check). Jordan re-ran streaming tests 1-7 on the phone: all passed. 168 app + 5 SDK tests pass.
- 2026-10-02, Part 0 + Part 1: streaming added to the features list (4a). Scrolling title and second line on Now Playing (music, books, podcasts) and the now-playing bar, only when the text doesn't fit, paused when Listen isn't on screen. Downloads screen (all downloaded episodes with art, title, show, size; total at the top; remove each; REMOVE ALL PLAYED after confirming), reached from a New Episodes | Downloads row on the Podcasts screen. Refresh moved to a top-bar icon next to search (faded while it works); storage line and REFRESH button removed. v1.8.0. Jordan checked it on the phone. 168 tests pass.
