# Listen: build walkthrough

This guide builds the Listen app for your Light Phone III one small piece at a time.
You never write code. You type or paste the text in the boxes, approve what Claude Code
asks to do, and test on your phone.

There's a one-time setup and eight sessions (0 through 7). Do one session at a time.
It's fine to spread them over several days.

---

## How to read this guide

- A box marked **PowerShell** gets typed into the blue or black PowerShell window. Press
  **Enter** after each line.
- A box marked **Claude Code** gets pasted into Claude Code once it's running. Paste the
  whole box and press **Enter**.
- To paste in PowerShell or Claude Code, **right-click** in the window or press
  **Ctrl + V**.
- While it works, Claude Code will ask permission to run things, for example
  *"Do you want to run `gradlew assembleDebug`?"*. Choose **Yes** (press **1**, or use the
  arrow keys and **Enter**). If it offers *"Yes, and don't ask again for..."*, that's fine
  to pick too.
- If Claude Code asks you a question, just answer in plain English.
- **Don't close PowerShell while Claude is working.** Wait until it says it's done and
  shows the `>` prompt again.

---

## One-time setup (do this once, about 10 minutes)

### Step 1: Open PowerShell

Press the **Windows key**, type `powershell`, and click **Windows PowerShell**.

### Step 2: Download a fresh copy of your project for Listen

**PowerShell**, one line at a time:

```powershell
cd $HOME\Documents
git clone https://github.com/jordancramer08-png/light-sdk listen
cd listen
git checkout -b listen
```

The last line should say `Switched to a new branch 'listen'`.

### Step 3: Copy in the setup files

**PowerShell:**

```powershell
copy ..\reader\local.properties .
copy "D:\Music\_Listen App Build\CLAUDE.md" .
copy "D:\Music\_Listen App Build\BUILD_GUIDE.md" .
```

If the first line says it can't find `local.properties`, ignore it. Claude will make one
in Session 0.

### Step 4: Close PowerShell

Type:

```powershell
exit
```

Setup is done.

---

## The start and end of every session

Every session begins and ends the same way. Each session below repeats these steps in
full, so you never have to flip back here.

**At the start:**

1. Plug the phone into the laptop with the USB cable and unlock it.
2. Open **Windows PowerShell**.
3. In **PowerShell**, type:
   ```powershell
   cd $HOME\Documents\listen
   git status
   ```
   The first line of the output should say `On branch listen`.
4. In **PowerShell**, type:
   ```powershell
   claude
   ```
5. Paste the session's **start prompt** into **Claude Code**.

**At the end** (only after the phone test passes):

1. Paste the **end prompt** into **Claude Code**.
2. When it says the save is finished, type into **Claude Code**:
   ```
   /exit
   ```
3. Then type into **PowerShell**:
   ```powershell
   exit
   ```

**If the phone test fails,** don't do the end steps. Stay in Claude Code and use the
"Something went wrong" prompts near the bottom of this guide.

---

## Session 0: Planning (no code yet)

### Start

Plug in the phone and unlock it. Open **Windows PowerShell**.

**PowerShell:**

```powershell
cd $HOME\Documents\listen
git status
claude
```

**Claude Code:**

```
We are starting Session 0 of BUILD_GUIDE.md. Read CLAUDE.md and BUILD_GUIDE.md first.
I am a beginner, so keep explanations short and plain.

1. Run "adb devices" and confirm my phone is connected. If adb isn't found, find it on
   my PC and tell me how you found it.
2. If local.properties is missing from this folder, create it with the correct sdk.dir
   for my PC.
3. Study the Reader app on the origin/reader branch using git show and git ls-tree.
   Do NOT switch to that branch. Tell me how the Reader is built, signed and installed,
   how it reads files from /sdcard, and which parts Listen can reuse.
4. Write the plan for the Listen app (project layout, main classes, Media3 version)
   into a new file called PLAN.md.

Do not write any app code yet. When you finish, give me a short summary in plain English.
```

### What you do

Read Claude's summary. If something doesn't make sense, ask, for example
*"What does 'MediaSessionService' mean in plain English?"*.

### End

**Claude Code:**

```
Session 0 is finished. Please:
1. Make sure local.properties is listed in .gitignore so it is never committed.
2. Commit CLAUDE.md, BUILD_GUIDE.md, PLAN.md and .gitignore with the message
   "Session 0 - plan".
3. Push the listen branch to GitHub.
Tell me when it is done.
```

When it says it's done:

**Claude Code:**

```
/exit
```

**PowerShell:**

```powershell
exit
```

---

## Session 1: First version on the phone (file access and song list)

### Start

Plug in the phone and unlock it. Open **Windows PowerShell**.

**PowerShell:**

```powershell
cd $HOME\Documents\listen
git status
claude
```

**Claude Code:**

```
We are starting Session 1 of BUILD_GUIDE.md. Read CLAUDE.md, PLAN.md and NOTES.md (if it
exists) first. I am a beginner.

Build Phase 1:
- Create the Listen tool (package com.thelightphone.listen) using the Reader's patterns.
- Add All files access (MANAGE_EXTERNAL_STORAGE). When it's missing, show a screen that
  says: "Run Listen-Phone-Sync.cmd on the PC and choose option 9".
- Add a background music scanner for /sdcard/Listen/Music that reads song tags, saves an
  index, and only rescans changed files (use .state/last-sync.txt as CLAUDE.md
  describes).
- Add a home screen with Music / Audiobooks / Now Playing buttons, and a simple Songs
  list showing title and artist.

Check my phone is connected with adb devices, then build it and install it on my phone.
When it is installed, tell me exactly what to check on the phone.
```

### Test on the phone

1. Double-click **`D:\Music\Listen-Phone-Sync.cmd`**. Type **1**, then **Enter**. Type
   **2** (by album), then **Enter**. Type **1,2,3** and **Enter**, then **Y** and
   **Enter**.
2. Back at the main menu, type **9**, then **Enter**. It should say Listen now has
   permission. Press **Enter**, type **Q**, and press **Enter** to close it.
3. **Reboot the phone.** The launcher only shows new tools after a restart, and you only
   need to do this after this first install.
4. Open **Listen**, then Music. Your three albums' songs should be listed with the right
   titles and artists.
5. Run the CMD again and send one more album. Reopen Listen, and the new songs should
   appear by themselves.

### End (only if the test passed)

**Claude Code:**

```
Session 1 is finished and works on my phone. Please:
1. Commit all changes with the message "Session 1 - skeleton, file access, music scan".
2. Push the listen branch to GitHub.
3. Create or update NOTES.md with a short note about what was built and anything the
   next session needs to know, and commit that too.
Tell me when it is done.
```

**Claude Code:**

```
/exit
```

**PowerShell:**

```powershell
exit
```

---

## Session 2: Playing music and remembering your spot

### Start

Plug in the phone and unlock it. Open **Windows PowerShell**.

**PowerShell:**

```powershell
cd $HOME\Documents\listen
git status
claude
```

**Claude Code:**

```
We are starting Session 2 of BUILD_GUIDE.md. Read CLAUDE.md, PLAN.md and NOTES.md first.
I am a beginner.

Build Phase 2:
- Add the Media3 ExoPlayer + MediaSessionService playback service exactly as CLAUDE.md
  describes: audio focus, pause when headphones or Bluetooth disconnect, Bluetooth
  buttons, and a media notification.
- Tapping a song plays the list starting from that song.
- Build the music Now Playing screen: album art, title, artist, seek bar with times,
  previous / play-pause / next, shuffle, and repeat.
- Add the small now-playing bar at the bottom of the list screens.
- Save and restore the music queue and position in /sdcard/Listen/.state/music_state.json,
  so that after I pause, swipe the app away and reopen it, it's paused at the same spot
  in the same song.

Check my phone is connected with adb devices, then build and install it.
Tell me exactly what to check.
```

### Test on the phone

1. Play a song, then turn the screen off. It should keep playing.
2. If you have Bluetooth headphones, press their play/pause button.
3. Pause at about 1:23. Swipe Listen away (close it) and reopen it. It should be paused
   at 1:23, and pressing Play should continue from there.
4. While music plays, have someone call you. It should pause, then resume after the call.

### End (only if the test passed)

**Claude Code:**

```
Session 2 is finished and works on my phone. Please:
1. Commit all changes with the message "Session 2 - playback and music resume".
2. Push the listen branch to GitHub.
3. Update NOTES.md with a short note about what was built and anything the next session
   needs to know, and commit that too.
Tell me when it is done.
```

**Claude Code:**

```
/exit
```

**PowerShell:**

```powershell
exit
```

---

## Session 3: Artists, albums, sorting and album art

### Start

Plug in the phone and unlock it. Open **Windows PowerShell**.

**PowerShell:**

```powershell
cd $HOME\Documents\listen
git status
claude
```

**Claude Code:**

```
We are starting Session 3 of BUILD_GUIDE.md. Read CLAUDE.md, PLAN.md and NOTES.md first.
I am a beginner.

Build Phase 3:
- Build the Artists screen, the Albums screen, and the album detail screen. Group by
  album artist + album, and play in disc/track order.
- Add sort controls: Songs by Title / Artist / Album, Albums by Album / Artist, and
  Artists by name. Each can be A-Z or Z-A, ignores a leading The/A/An, and is remembered
  per list.
- Add album artwork everywhere, downscaled, cached, and loaded off the main thread, as in
  the Artwork section of CLAUDE.md.

Check my phone is connected with adb devices, then build and install it.
Tell me exactly what to check.
```

### Test on the phone

1. First, send more music with the CMD. Try option **1**, then **3** (everything), if you
   want the whole library on the phone. It's about 8 GB and takes a while.
2. In Listen, switch each sort between A–Z and Z–A. "The Corner Room" should sort under
   **C**.
3. Each album should show its cover, and *Avengers: Age of Ultron* should be one album,
   not two.
4. Scroll the whole song list quickly. It shouldn't stutter or freeze.

### End (only if the test passed)

**Claude Code:**

```
Session 3 is finished and works on my phone. Please:
1. Commit all changes with the message "Session 3 - artists, albums, sorting, artwork".
2. Push the listen branch to GitHub.
3. Update NOTES.md with a short note about what was built and anything the next session
   needs to know, and commit that too.
Tell me when it is done.
```

**Claude Code:**

```
/exit
```

**PowerShell:**

```powershell
exit
```

---

## Session 4: Playlists

### Start

Plug in the phone and unlock it. Open **Windows PowerShell**.

**PowerShell:**

```powershell
cd $HOME\Documents\listen
git status
claude
```

**Claude Code:**

```
We are starting Session 4 of BUILD_GUIDE.md. Read CLAUDE.md, PLAN.md and NOTES.md first.
I am a beginner.

Build Phase 4, playlists, exactly as the Playlists section of CLAUDE.md describes:
- Live Song / Album / Artist entries, saved in /sdcard/Listen/.state/playlists.json.
- Create, rename and delete playlists.
- "Add to playlist..." from songs, albums, artists and Now Playing.
- Remove entries, and move them up or down.
- Entries that aren't on the phone right now show greyed "not on phone".
- Play and shuffle a playlist.

Check my phone is connected with adb devices, then build and install it.
Tell me exactly what to check.
```

### Test on the phone

1. Make a playlist with one song, one whole album and one whole artist. Play it and check
   the order makes sense.
2. With the CMD, remove that album from the phone (option **2**, then **2**, then pick
   it). In Listen, the entry should turn grey. Send the album back, and it should work
   again.
3. Send a different album by the artist you added. It should join the playlist by itself.

### End (only if the test passed)

**Claude Code:**

```
Session 4 is finished and works on my phone. Please:
1. Commit all changes with the message "Session 4 - playlists".
2. Push the listen branch to GitHub.
3. Update NOTES.md with a short note about what was built and anything the next session
   needs to know, and commit that too.
Tell me when it is done.
```

**Claude Code:**

```
/exit
```

**PowerShell:**

```powershell
exit
```

---

## Session 5: The audiobook library

### Start

Plug in the phone and unlock it. Open **Windows PowerShell**.

**PowerShell:**

```powershell
cd $HOME\Documents\listen
git status
claude
```

**Claude Code:**

```
We are starting Session 5 of BUILD_GUIDE.md. Read CLAUDE.md, PLAN.md and NOTES.md first.
I am a beginner.

Build Phase 5, the Audiobooks section, using the book.json files under
/sdcard/Listen/Audiobooks (with the fallbacks in CLAUDE.md):
- "Continue listening" at the top.
- The library grouped by author, then by series in seriesNumber order.
- Sort by Title / Author / Recently played, A-Z and Z-A.
- Covers, and progress shown on each row.
- The book screen with the chapter list.
No playback changes yet, except that tapping Play starts the book at chapter 1.

Check my phone is connected with adb devices, then build and install it.
Tell me exactly what to check.
```

### Test on the phone

1. Double-click the CMD. Type **3**, then **Enter**. Type **mistborn** and **Enter** to
   filter, then type **1** and **Enter**, then **Y** and **Enter**.
2. Do the same for **martian** and for **potter**. With potter, pick just the first book.
3. In Listen, open **Audiobooks**. Check that the covers, titles, authors and series order
   look right.
4. If a title looks wrong, fix it on the PC. In the CMD, type **5** and **Enter**. Excel
   opens. Fix the Title column (don't touch the Folder column), then save and close
   Excel. Go back to the CMD window, press **Enter**, and type **Y** to update the phone.

### End (only if the test passed)

**Claude Code:**

```
Session 5 is finished and works on my phone. Please:
1. Commit all changes with the message "Session 5 - audiobook library".
2. Push the listen branch to GitHub.
3. Update NOTES.md with a short note about what was built and anything the next session
   needs to know, and commit that too.
Tell me when it is done.
```

**Claude Code:**

```
/exit
```

**PowerShell:**

```powershell
exit
```

---

## Session 6: Listening to audiobooks and remembering your place

### Start

Plug in the phone and unlock it. Open **Windows PowerShell**.

**PowerShell:**

```powershell
cd $HOME\Documents\listen
git status
claude
```

**Claude Code:**

```
We are starting Session 6 of BUILD_GUIDE.md. Read CLAUDE.md, PLAN.md and NOTES.md first.
I am a beginner.

Build Phase 6, audiobook playback exactly as CLAUDE.md describes:
- Per-book positions saved in /sdcard/Listen/.state/book_positions.json: every 10
  seconds, and on every pause, seek, background and stop.
- Auto-rewind on resume, and finished detection.
- Separate resume points for music and books.
- The audiobook Now Playing screen: chapter seek bar with the whole-book position under
  it, back 15 / forward 30, previous/next chapter, and the chapter list.
- Pitch-corrected speed, remembered per book.
- A sleep timer, including "end of chapter".
- Speech audio attributes.

Check my phone is connected with adb devices, then build and install it.
Tell me exactly what to check.
```

### Test on the phone

1. Listen to a book for a few minutes and pause. Swipe Listen away and reopen it. It
   should resume a few seconds before where you stopped.
2. Play some music, then go back to the book. Each should keep its own spot.
3. Set the speed to 1.5× and a 15-minute sleep timer. Check both work.
4. With the CMD, remove the book (option **4**), then send it again (option **3**). It
   should still resume where you were.

### End (only if the test passed)

**Claude Code:**

```
Session 6 is finished and works on my phone. Please:
1. Commit all changes with the message "Session 6 - audiobook playback and positions".
2. Push the listen branch to GitHub.
3. Update NOTES.md with a short note about what was built and anything the next session
   needs to know, and commit that too.
Tell me when it is done.
```

**Claude Code:**

```
/exit
```

**PowerShell:**

```powershell
exit
```

---

## Session 7: Audio quality and final polish

### Start

Plug in the phone and unlock it. Open **Windows PowerShell**.

**PowerShell:**

```powershell
cd $HOME\Documents\listen
git status
claude
```

**Claude Code:**

```
We are starting Session 7 of BUILD_GUIDE.md, the last one. Read CLAUDE.md, PLAN.md and
NOTES.md first. I am a beginner.

Build Phase 7:
1. Go through the "Best possible audio quality" section of CLAUDE.md and confirm each
   point in the code:
   - untouched output
   - gapless albums
   - the file's own sample rate
   - an audio offload setting (default off)
   - a format/bitrate line on Now Playing
   - skipping unplayable files without crashing
2. Do a speed check for a 1,400-song library and a 560-file audiobook.
3. Do a polish pass: spacing, long names, and empty screens with friendly messages.

Check my phone is connected with adb devices, then build and install it.
Then give me a final checklist to test.
```

### Test on the phone

1. Play an album where songs run straight into each other (a soundtrack works well).
   There should be no gap between tracks.
2. Turn on **audio offload** in Listen's settings and listen for a while. Keep it on only
   if pausing, seeking and speed changes all still behave. Otherwise turn it off.
3. Go through Claude's final checklist.
4. Back up your playlists and positions: in the CMD, type **7** and **Enter**.

### End (only if the test passed)

**Claude Code:**

```
Session 7 is finished and works on my phone. The Listen app is complete. Please:
1. Commit all changes with the message "Session 7 - audio quality and polish".
2. Push the listen branch to GitHub.
3. Update NOTES.md with a final summary: what the app does, how to rebuild and reinstall
   it from scratch, and where my playlists and positions are stored.
Tell me when it is done.
```

**Claude Code:**

```
/exit
```

**PowerShell:**

```powershell
exit
```

That's it. The app is built.

---

## Something went wrong? Paste one of these into Claude Code

**The build or install failed** (red error text):

```
The build or install failed. Please read the error, explain what went wrong in one or
two plain sentences, fix it, and try again.
```

**It installed, but something on the phone is wrong:**

```
It installed, but on the phone this happened: [describe what you saw, e.g. "the songs
list is empty" or "it crashes when I tap an album"]. Please find the cause, fix it,
rebuild and reinstall, and tell me what to check.
```

**It crashed and you want Claude to look at the phone's log:**

```
The app crashed on my phone just now. Please read the crash log with adb logcat, find
the cause, fix it, rebuild and reinstall.
```

**You're not sure what Claude just did:**

```
Please explain what you just changed in plain English, in 3 sentences or fewer.
```

**You want to undo the last attempt and go back to the last working version:**

```
Please undo all changes since the last commit so I'm back to the last working version,
then rebuild and reinstall it.
```

---

## If you had to stop in the middle of a session

Maybe the laptop closed, you hit a usage limit, or it got late. Nothing is lost.

**PowerShell:**

```powershell
cd $HOME\Documents\listen
claude --continue
```

`--continue` reopens the conversation where it stopped. Then paste this into **Claude
Code**:

```
We were in the middle of the session. Please tell me where we left off and continue.
```

If `--continue` doesn't work, type `claude` instead. Then paste this into **Claude
Code**, with the right session number:

```
We are in the middle of Session [number] of BUILD_GUIDE.md. Read CLAUDE.md, PLAN.md
and NOTES.md, run "git status" to see what has been done since the last commit, and
continue that session.
```

---

## Progress tracker

| Session | What it builds | Done |
|---|---|---|
| Setup | Project folder and files | ☐ |
| 0 | Plan (no code) | ☐ |
| 1 | App on the phone, file access, song list | ☐ |
| 2 | Playing music, resume where you paused | ☐ |
| 3 | Artists, albums, sorting, album art | ☐ |
| 4 | Playlists | ☐ |
| 5 | Audiobook library and covers | ☐ |
| 6 | Audiobook playback and saved positions | ☐ |
| 7 | Audio quality and polish | ☐ |

---

## The sync CMD at a glance (`D:\Music\Listen-Phone-Sync.cmd`)

Plug in the phone, unlock it, and double-click the file.

| Type | What it does |
|---|---|
| **1** | Add music: by artist, by album, or everything not on the phone yet |
| **2** | Remove music: by artist, by album, or all of it |
| **3** | Add audiobooks |
| **4** | Remove audiobooks |
| **5** | Edit audiobook titles, series and narrators in Excel, then update the phone |
| **6** | Show what's on the phone and how much space is free |
| **7** | Back up playlists and listening positions to `D:\Music\_Listen Backups` |
| **8** | Restore playlists and positions from a backup |
| **9** | Give Listen access to its files (once, after the first install) |
| **Q** | Quit |

In any list:

- Type a number (`3`), several numbers (`1,4,7`) or a range (`10-15`).
- Type a word to filter the list, for example `getty`.
- Type **A** for everything shown, or **C** to clear the filter.
- Press **Enter** with nothing typed to go back.

It always asks **Y/N** before it copies or removes anything. Your PC files are never
changed. If a copy is interrupted, run it again and it sends only what's missing.
