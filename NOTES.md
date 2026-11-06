# NOTES.md: Adventure for Light Phone III

## Goal

Adventure is a Light Phone 3 tool that plays Z-machine story files: text adventures such as Zork.

## Decisions

- App name: **Adventure**. Tool id: `com.thelightphone.adventure`.
- Z-machine versions **3, 4, 5 and 8** are supported. Version 6 is not.
- No GPS and no network calls.
- No emulator. Test the engine on the laptop first and the phone last.
- Full color. (LightOS grayscale is turned off on this phone, per `listen/CLAUDE.md`.)
- No third-party libraries unless the SDK allows them.
- Zork I-III are bundled from MIT-licensed source. Every other game is a file added by hand.
- **Sideload only**, like Listen. Adventure is not being built for Light signing.
- Engine location: inside the `tool` module, in package `com.thelightphone.adventure.engine`, with
  no Android imports. Tested on the laptop with `:tool:testDebugUnitTest`.
- Bundled games use a `.dat` or `.bin` extension, not `.z3`, `.z5` or `.z8`. Only Light's server
  build requires this, but it costs nothing and keeps that route open.

## Build

Run from PowerShell in the project root. Needs Java 17 (`JAVA_HOME`) and `ANDROID_HOME`, both
already set on this laptop. No `local.properties` was needed.

Exact command that worked (2026-11-06, about 6 minutes on a first build):

```powershell
.\gradlew.bat :tool:assembleDebug
```

Output: `tool\build\outputs\apk\debug\tool-debug.apk` (78.7 MB, label "Adventure").

Laptop tests, no phone needed (28 tests passed):

```powershell
.\gradlew.bat :tool:testDebugUnitTest
```

**Release build works** (2026-11-06). Use this one on the phone; debug is much slower:

```powershell
.\gradlew.bat :tool:assembleRelease
```

Output: `tool\build\outputs\apk\release\tool-release.apk` (24.2 MB, label "Adventure").

It first failed with `PKIX path building failed` while downloading from Maven Central: Java did
not trust the certificate it was given. The fix is this line in `gradle.properties`, which makes
Java use the Windows certificate store. Certificate checks stay on.

```properties
systemProp.javax.net.ssl.trustStoreType=Windows-ROOT
```

Why Listen did not hit this: its build files match, and it has no such fix. Its last real release
build was 2026-10-02, and its saved output means Gradle has not needed those downloads since.
Listen has not been rebuilt from clean since, so it is not proven to work today. The jar that
failed here was not in the Gradle cache, and it is now. Not known: why Java stopped trusting the
certificate. If a clean Listen build fails the same way, the same line should fix it.

## Folder layout

```text
lp3-adventure/                 branch zmachine, fork of light-sdk
  NOTES.md                     this file
  CLAUDE.md                    project rules, read first each session
  docs/SDK_RECON_NOTES.md      older SDK recon, moved from NOTES.md
  tool/
    lighttool.toml             id, label, version, permissions
    build.gradle.kts
    src/main/kotlin/com/thelightphone/adventure/HomeScreen.kt       placeholder screen
    src/test/kotlin/com/thelightphone/adventure/LaptopTestSmokeTest.kt
  sdk/ plugin/ lint-rules/ builder/ examples/                       SDK, leave alone
```

Planned: engine code in `tool/src/main/kotlin/com/thelightphone/adventure/engine/`, engine tests
in `tool/src/test/kotlin/com/thelightphone/adventure/engine/`, Zork files in
`tool/src/main/assets/`.

## SDK facts that affect this project

- Light's server build keeps only `tool/src/main/{kotlin,java,res,assets}`,
  `tool/build.gradle.kts` and `tool/lighttool.toml`. Other modules are discarded.
- Light's server build accepts only certain asset types. `.z3`, `.z5` and `.z8` are not on the
  list; `.dat` and `.bin` are. Bundle story files under those extensions.
- `lightContext.fileShare` reads and writes text only, so it would corrupt binary story files.
  Use `java.io.File` with `filesDir`.
- The APK contains `INTERNET` and `CAMERA` even though `permissions = []`. They come from SDK
  libraries (okhttp and `:sdk:ui`). "No network" is therefore a rule for our code, not something
  the APK enforces.

## Story files

Bundled games are in `tool/src/main/assets/` as `.dat` files, with their licence and credits. The
three Zork files matched the kit's `SHA256SUMS.txt` (2026-11-06). Every other game is in
`story-files/`, which Git ignores along with everything in it.

The Zork source repos are in `C:\Users\bjc38\Documents\lp3-adventure-kit\zork-source`. They are
**read-only: never edit them**.

| File | Size (bytes) | Z-machine version | Release | Serial |
|---|---:|---|---:|---|
| zork1.dat | 86838 | 3 | 119 | 880429 |
| zork2.dat | 92524 | 3 | 63 | 860811 |
| zork3.dat | 87984 | 3 | 25 | 860811 |
| adv550.z8 | 231424 | 8 | 1 | 160307 |
| advent.z8 | 147456 | 8 | 1 | 160307 |
| anchor.z8 | 520192 | 8 | 5 | 990206 |
| bluechairs.z5 | 241152 | 5 | 2 | 041229 |
| curses.z5 | 259072 | 5 | 16 | 951024 |
| dreamhold.z8 | 386560 | 8 | 5 | 041231 |
| Galatea.zblorb | 441992 | bundle holding a version 8 story | | |
| huntdark.z5 | 116224 | 5 | 4 | 991119 |
| LostPig.z8 | 285184 | 8 | 2 | 080406 |
| photopia.z5 | 239616 | 5 | 1 | 120416 |
| Savoir-Faire.zblorb | 442212 | bundle holding a version 8 story | | |
| shade.z5 | 108544 | 5 | 3 | 001127 |
| SoFar.z8 | 300032 | 8 | 6 | 961218 |
| Tangle.z5 | 221184 | 5 | 4 | 980226 |
| vespers.z8 | 309760 | 8 | 1 | 051128 |
| vgame.z8 | 501760 | 8 | 1 | 990831 |

Version is header byte 0, release is bytes 2-3, and serial is bytes 18-23.

## Added games

Games live in `/sdcard/Adventure/Games`. `MANAGE_EXTERNAL_STORAGE` is in `lighttool.toml`, and the
SDK plugin's allowlist in `LightToolMetadata.kt` has the same addition Listen made (the file now
matches Listen's exactly). `Adventure Games.cmd` in the project root copies games over with adb and
grants the permission. Unlike Listen's sync file, it does not read `local.properties` to find adb.

## Status (end of Session 9, 2026-11-06)

Finished:

- Sessions 0 and 1: project created, tool renamed, story files collected and checked.
- Session 2: engine memory, header, packed addresses, text decoding, object table, dictionary and
  the `ZIo` interface, in `tool/src/main/kotlin/com/thelightphone/adventure/engine/`.
- Session 3: instruction decoding, the opcode set, the version 3 status line and the console
  player.
- Session 4: Quetzal save and restore, replay tests and the gap log.
- Session 9: Settings screen, three color themes, plain error messages, stuck-game handling and
  fixes for the gaps that affected the test games.
- Session 8: game shelf, Licenses screen, Blorb unwrapping and `Adventure Games.cmd`.
- Session 7: game menu, five save slots per game, automatic save and resume.
- Session 6: typing aids. All rules are in `TypingAids.kt`, tested without a screen.
- Session 5: game screen, temporary home screen and view model tests. **The screen has only been
  compiled. It has never been drawn or used**, because there is no emulator; the view model
  tests cover everything below the drawing.
- `.\tools\play.ps1 tool\src\main\assets\zork1.dat` plays a game in PowerShell. Add `-Build`
  after engine changes, `-Seed 42` for repeatable randomness, `-Commands file.txt` to script it.
  `gradlew run` does not exist because `:tool` is an Android module. The player's code is under
  `tool/src/test`, so it does not ship in the APK.
- The console keeps saves in `saves\` and gap reports in `logs\<story>-gaps.txt`. Git ignores both.
- `.\tools\story-info.ps1 <story file>` prints a story's summary. Add `-Build` after engine
  changes. Its code is under `tool/src/test` too.
- Nothing has been installed on the phone.

**Session 9's final APK build is still pending.** All 145 tests pass, but
`tool\build\outputs\apk\release\tool-release.apk` is an old build from Session 8 and has none of the
Session 9 work. On 2026-11-06 `.\gradlew.bat :tool:assembleRelease` failed once with its error not
captured, and new `hs_err` files suggest Java crashed. A second try was stopped for critically low
memory (814 MB of 7.8 GB free) during shrinking. Close other apps before trying again.

**Order from here (Jordan's decision):**

1. Sessions 11-16, the Bestiary mod.
2. Finish Session 9's pending final APK build, then check the new APK's permissions.
3. Session 10, install on the phone and test. Follow "Installing on the phone" below.

**Do not build an APK until Session 16 is done.** Laptop tests are still fine to run.

How the new parts work:

- **Game screen:** `GameScreen` draws and `GameViewModel` runs the engine on a background
  coroutine. Its private `GameIo` is the only link to the engine. `GameUiState` is plain data.
  There is no `LightScreenViewModel`: it is `LightScreen<Unit, GameViewModel>`.
- **Theme:** all colors and text sizes are in `AdventureTheme.kt`. Colors a game sets are kept on
  each `StorySpan`; `ZColor.DEFAULT` means "use the theme".
- **Keyboard:** the SDK's text editor is full-screen and hides the story, so `GameScreen` embeds
  `LightEmbeddedLp3Keyboard` with its own `InputLineKeys`. Tap the input line to open it and the
  story to close it. Return or SEND sends.
- **Typing aids:** direction and command buttons send at once. Verbs, tapped story words and
  suggestions add to the input line. Suggestions replace the verb row while a word is being
  typed. History and Settings swap into the story area. The Settings button replaces `Aa`.
- **Keys:** `GameKeys` ignores 27, 80 and 319, as Listen does. Wheel turns reach LightOS.
- **Phone files:** saves are in `filesDir/saves/r<release>-<serial>-<checksum>/`, as `slot1.qzl`
  to `slot5.qzl` with `slotN.txt` (time and label), and `auto.qzl` with `auto.txt` (recent text).
  Gaps are in `filesDir/gaps.txt`.
- **Settings:** `SettingsScreen.kt` holds the screen and `AppSettings`, kept in the SDK data store.
  The three themes are in `ThemePalettes` in `AdventureTheme.kt`.
- **Errors:** `GameUiState.problem` replaces the game with a plain message and a way back to the
  shelf. The technical reason goes in `gaps.txt`. Only `ZMachine.quitByGame` clears the automatic
  save. An instruction that fails is still logged and the game carries on.
- **Stuck games:** the engine runs on its own thread, and text reaches the screen every 100 ms even
  if the game never asks for input. Leaving calls `requestStop`, which allows 1.5 seconds to reach a
  prompt, so a finishing turn is still saved. A stuck game then stops without an automatic save.
- **Quote boxes:** when the upper window shrinks, cut-off rows move into the story as plain lines.
  Curses shows its quotes this way. Its status band loses row 2 until the next turn, as on any
  interpreter, which is what the old "missing row" note was.
- **Shelf:** `Shelf.kt` holds the rules, in `ShelfViewModel`. Custom names and play dates are in
  `filesDir/shelf/<game key>.txt`; `last.txt` names the game for Continue.
- **Saving at a prompt:** Quetzal can only resume from a `save` instruction, so menu saves, automatic
  saves and Undo use `ZMachine.saveAtInput`. It saves the waiting read instruction and adds a chunk
  of our own, `LPin`, so that loading runs the read again. `GameIo` does this on the game's thread,
  then throws `ZInputCancelled` if the game was replaced.
- **Menu Undo:** `GameIo` keeps a save from each of the last 10 turns, so it works in version 3
  games too. The `undo` command a game has of its own is separate.
- **Saves:** `Quetzal.kt` reads and writes the files. `ZIo` picks a slot, then `writeSave` and
  `readSave` move the bytes. A bad or wrong-story file is refused and the game carries on.
- **Replays:** command files are in `tool/src/test/replays/`. `hashes.txt` holds a fingerprint of
  each output, not the text, because game text is copyrighted. After an intended output change,
  read the transcript, then replace the fingerprint with the one the failed test shows.
- **Gap log:** `GapLog.kt` counts each different gap. Lines begin `unimplemented opcode`, `error:`,
  `stub:` (left out on purpose) or `unusual:` (odd, but handled). `ZIo.log` gets each one once.

## Known gaps

Game screen:

- Nothing on it has been seen: layout, colors, keyboard fit, scrolling, key handling and the
  text size setting all need checking on the phone.
- An automatic save only happens at a prompt. If the app is killed while a turn is running, or
  while the game's own slot picker is open, the previous automatic save stays.
- After resuming, the story shows the last 40 lines without colors or command highlighting, and the
  upper band returns without its colors. History and menu Undo start empty.
- A game stuck in a loop stops without saving, so it resumes from the last automatic save.
- With automatic save off, an older automatic save is kept but ignored.
- Restoring a slot does not put back the story text; it adds a note to what is on screen.
- Games are told the screen is 40 columns by 24 lines. The upper band scales to fit 40 columns.
- Keyboard presses have no haptic click: the SDK helper needs `LocalContext`, which is blocked.
- `read_char` is a Send: the first letter typed, or Return if the line is empty.
- `erase_window` clears the scrollback as well as the screen.
- Text sizes are 15, 18 (default), 21 and 25 sp. These are guesses until seen on the phone.

Shelf and phone script:

- `Adventure Games.cmd` has only been run against a fake phone, in dry-run mode and for real with a
  stand-in adb. It has never touched the real phone.
- Bundled games can be renamed but not removed. Their saves can only be replaced.
- Removing a game with the `.cmd` leaves its saves in the app, because the app's storage is private.
  They come back if the same game is added again. Remove on the phone deletes both.
- Two files holding the same game show once, and share saves.
- Each shelf refresh reads every added game in full. Fine for 20 games; slow for hundreds.
- `.cmd` adds only the top level of the chosen folder, not subfolders.

Typing aids:

- Suggestions come from the game's whole dictionary, so they can reveal words a player has not
  met yet, such as magic words, and the odd internal word. Zork's `intnum` is one.
- `advent.z8` and `adv550.z8` have empty dictionaries, so they get no suggestions.
- A truncated word stays cut short (`mailbo`) until the game has printed the full word. It still
  works, because games compare only the stored letters.
- `Again` sends the word `again`. A game without that command will just reject it.
- History lasts only while the game is open and leaves out repeats in a row.
- The input line has no movable cursor: typing and backspace work at the end only.
- With the keyboard open, the two rows leave little room for the story. Check on the phone, where
  hiding the quick row in Settings is the first fix to try.

Saves:

- Loading files from other interpreters has only been tested with hand-built files written from
  the Quetzal specification. No other interpreter or real foreign save was on this laptop.
- Our saves have not been loaded by another interpreter either.
- Saves from the menu and automatic saves carry the `LPin` chunk and **will not work elsewhere**.
  Only saves made with the game's own `save` command are plain Quetzal.
- Auxiliary files (`save` and `restore` with operands) are refused and logged as a stub.
- Restoring clears the undo history. Undo keeps the last 10 turns and does not survive quitting.

Input and output:

- Stream 2 calls `ZIo.transcript`, which does nothing in the console, so `script` writes no file.
- Stream 4 (command record) and input stream 1 (commands from a file) are logged stubs.
- Timed input has never run in a real game. The phone view model supports it and keeps typed
  text in the input line; the console waits forever and does not claim support.
- Read terminators other than Enter are ignored. No test game is affected: all their tables are
  empty or absent (checked 2026-11-06).
- Sound, pictures, `set_true_colour` and fonts other than 1 and 4 are logged stubs.
- The console shows neither colors nor text styles. The game screen is written to.
- The console shows the upper window as `|` lines before each prompt, not as a real screen.
- Characters beyond the 16-bit range cannot be typed into a buffer correctly.

Engine:

- An instruction that fails stores 0, counts its test as false, and the game carries on. That
  keeps games alive but may hide a wrong result, so check `logs\` if a game misbehaves.
- The engine stops only if the program counter leaves the story, a game returns from its main
  routine, or recovery from an error itself fails. Each shows the plain message.

Testing:

- The Standard and Quetzal documents were not open while writing, and no conformance suite
  (Czech, Praxix) was available. Running those would be the best next check.
- Keyboard play has not been tried by hand; all runs used `-Commands`.
- Colossal Cave and Vespers were played for only about 40 and 25 commands. No game has been
  played to its end.

Worth knowing:

- `advent.z8` and `adv550.z8` have 4 objects and an empty dictionary, so they handle typed
  commands their own way.
- Curses object 448 lists property 11 twice. `nextProperty` now moves on from the last match,
  so `get_next_prop` cannot loop on it.
- Lost Pig dictionary entry 0 is `$#$`, cut off mid-character, and displays as `$#`.
- The engine was written from knowledge of the Standard without the document open, and nothing
  was ported from JSZM or ifvms.js. Check it against the document if a game misbehaves.
- Object counts are calculated, because story files do not store them.
- Only object names and dictionary words have been decoded from real games so far.
- Without `story-files`, tests cover only the Zork files and print a note instead of failing.
- `.zblorb` files work everywhere: `storyFromBlorb` takes the ZCOD chunk. Pictures and sounds in
  them are ignored.
- Builds take about 6 minutes and this laptop has under 8 GB of memory. Close other apps first.
- The APK lists `INTERNET` and `CAMERA` because of SDK libraries, not our code.
- A clean Listen rebuild is untested since 2026-10-02. See the Build section.

## Known issue: permissions from the SDK

`tool/lighttool.toml` asks only for `MANAGE_EXTERNAL_STORAGE`. The Session 8 APK also lists these
(recheck once the Session 9 APK is built),
all added by SDK libraries and not by our code: `INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`,
`FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE` and `WAKE_LOCK`. They have not been removed
by hand. No location permission is present. Our code has no network or location calls, and
`FinishingTest` fails if any are added.

## Installing on the phone

Copied from how Listen is installed (`listen\HOW TO USE.txt` and `listen\kit\kit.ps1`). Adventure has
no install `.cmd` of its own, so these are the same commands typed by hand. Run them from PowerShell
in the project root. Nothing has been installed yet.

1. Build the APK: `.\gradlew.bat :tool:assembleRelease`
2. Plug in and unlock the phone with a data USB cable. Check it is listed: `adb devices`
   If adb is not on the PATH, use its full path. Tap "Allow" on the phone if it asks.
3. Install as an update, which keeps data and never uninstalls:
   `adb install -r tool\build\outputs\apk\release\tool-release.apk`
4. Give Adventure storage access. Either use option 4 in `Adventure Games.cmd`, or:
   `adb shell appops set com.thelightphone.adventure MANAGE_EXTERNAL_STORAGE allow`
5. Restart the phone, because the launcher only shows a new tool after a restart: `adb reboot`
6. Add games with `Adventure Games.cmd`, option 2.

If the install reports `UPDATE_INCOMPATIBLE` or that signatures do not match, stop. Do not uninstall
Adventure, because that erases its saves. Keep the error and work out which key signed each APK. Builds
use the SDK dev key, `sdk\keys\lightsdk-dev.jks`.

## Sessions

- [x] Session 0: Create the project
- [x] Session 1: Collect story files
- [x] Session 2: Memory, text and objects
- [x] Session 3: Running the game in PowerShell
- [x] Session 4: Saving, undo and a game test
- [x] Session 5: The game screen (not yet seen on the phone)
- [x] Session 6: Typing aids (not yet seen on the phone)
- [x] Session 7: Saving and the game menu (not yet seen on the phone)
- [x] Session 8: Game shelf and add-games script (not yet seen on the phone)
- [ ] Session 9: Settings, safety and a full test run (all done except the final APK build)
- [ ] Session 10: Install on the phone and test

Work these in the order given under Status: Sessions 11-16 first, then Session 9's APK build, then
Session 10.

- [ ] Sessions 11-16: Bestiary mod
