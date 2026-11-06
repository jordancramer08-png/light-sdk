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
| Galatea.zblorb | 441992 | bundle, needs unwrapping in Session 8 | | |
| huntdark.z5 | 116224 | 5 | 4 | 991119 |
| LostPig.z8 | 285184 | 8 | 2 | 080406 |
| photopia.z5 | 239616 | 5 | 1 | 120416 |
| Savoir-Faire.zblorb | 442212 | bundle, needs unwrapping in Session 8 | | |
| shade.z5 | 108544 | 5 | 3 | 001127 |
| SoFar.z8 | 300032 | 8 | 6 | 961218 |
| Tangle.z5 | 221184 | 5 | 4 | 980226 |
| vespers.z8 | 309760 | 8 | 1 | 051128 |
| vgame.z8 | 501760 | 8 | 1 | 990831 |

Version is header byte 0, release is bytes 2-3, and serial is bytes 18-23.

## Plan for added games

Follow Listen: `/sdcard` with `MANAGE_EXTERNAL_STORAGE`, granted by the laptop `.cmd` sync file.
That permission is not on the stock allow-list, so the SDK plugin needs the same one-line addition
Listen made to `LightToolMetadata.kt`. Not done yet; it belongs in the sync session.

## Status (end of Session 5, 2026-11-06)

Finished:

- Sessions 0 and 1: project created, tool renamed, story files collected and checked.
- Session 2: engine memory, header, packed addresses, text decoding, object table, dictionary and
  the `ZIo` interface, in `tool/src/main/kotlin/com/thelightphone/adventure/engine/`.
- Session 3: instruction decoding, the opcode set, the version 3 status line and the console
  player.
- Session 4: Quetzal save and restore, replay tests and the gap log.
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

Next: try the game screen on the phone, then Session 6, typing aids.

How the new parts work:

- **Game screen:** `GameScreen` draws and `GameViewModel` runs the engine on a background
  coroutine. Its private `GameIo` is the only link to the engine. `GameUiState` is plain data.
  There is no `LightScreenViewModel`: it is `LightScreen<Unit, GameViewModel>`.
- **Theme:** all colors and text sizes are in `AdventureTheme.kt`. Colors a game sets are kept on
  each `StorySpan`; `ZColor.DEFAULT` means "use the theme".
- **Keyboard:** the SDK's text editor is full-screen and hides the story, so `GameScreen` embeds
  `LightEmbeddedLp3Keyboard` with its own `InputLineKeys`. Tap the input line to open it and the
  story to close it. Return or SEND sends.
- **Keys:** `GameKeys` ignores 27, 80 and 319, as Listen does. Wheel turns reach LightOS.
- **Phone files:** saves are `filesDir/saves/<game>.qzl`, gaps are `filesDir/gaps.txt`.
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
  `Aa` text size button all need checking on the phone.
- Each game has one save slot, with no prompt, until the save menu in Session 7. A new save
  replaces the old one.
- Pressing back ends the game with no autosave, so unsaved progress is lost.
- Games are told the screen is 40 columns by 24 lines. The upper band scales to fit 40 columns.
- Keyboard presses have no haptic click: the SDK helper needs `LocalContext`, which is blocked.
- `read_char` is a Send: the first letter typed, or Return if the line is empty.
- `erase_window` clears the scrollback as well as the screen.
- Text sizes are 15, 18 (default), 21 and 25 sp. These are guesses until seen on the phone.
- Only the three Zork games can be started until the game shelf in Session 8.

Saves:

- Loading files from other interpreters has only been tested with hand-built files written from
  the Quetzal specification. No other interpreter or real foreign save was on this laptop.
- Our saves have not been loaded by another interpreter either.
- Auxiliary files (`save` and `restore` with operands) are refused and logged as a stub.
- Restoring clears the undo history. Undo keeps the last 10 turns and does not survive quitting.

Input and output:

- Stream 2 calls `ZIo.transcript`, which does nothing in the console, so `script` writes no file.
- Stream 4 (command record) and input stream 1 (commands from a file) are logged stubs.
- Timed input has never run in a real game. The phone view model supports it and keeps typed
  text in the input line; the console waits forever and does not claim support.
- Read terminators other than Enter are ignored (the header's terminating characters table).
- Sound, pictures, `set_true_colour` and fonts other than 1 and 4 are logged stubs.
- The console shows neither colors nor text styles. The game screen is written to.
- The console shows the upper window as `|` lines before each prompt, not as a real screen.
- Curses: the second status row is missing after one `inventory`, repeatably, with three blank
  lines, `look`, `inventory` and seed 42. The same command elsewhere shows both rows. Cause not
  found; it could be the console or the engine. Curses is not on the temporary home screen, so
  recheck it after the game shelf in Session 8.
- Characters beyond the 16-bit range cannot be typed into a buffer correctly.

Engine:

- An instruction that fails stores 0, counts its test as false, and the game carries on. That
  keeps games alive but may hide a wrong result, so check `logs\` if a game misbehaves.
- The engine stops only if the program counter leaves the story.
- `restart` does not reset the font number or the upper-window flag held in `Opcodes`.

Testing:

- The Standard and Quetzal documents were not open while writing, and no conformance suite
  (Czech, Praxix) was available. Running those would be the best next check.
- Keyboard play has not been tried by hand; all runs used `-Commands`.
- Colossal Cave and Vespers were played for only about 40 and 25 commands. No game has been
  played to its end.
- `.zblorb` games (Galatea, Savoir-Faire) cannot load until Session 8.

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
- `story-info` calls a `.zblorb` file "version 70" rather than explaining it is a bundle.
- Builds take about 6 minutes and this laptop has under 8 GB of memory. Close other apps first.
- The APK lists `INTERNET` and `CAMERA` because of SDK libraries, not our code.
- A clean Listen rebuild is untested since 2026-10-02. See the Build section.

## Sessions

- [x] Session 0: Create the project
- [x] Session 1: Collect story files
- [x] Session 2: Memory, text and objects
- [x] Session 3: Running the game in PowerShell
- [x] Session 4: Saving, undo and a game test
- [x] Session 5: The game screen (not yet seen on the phone)
- [ ] Session 6: Typing aids
- [ ] Session 7: Saving and the game menu
- [ ] Session 8: Game shelf and add-games script
- [ ] Session 9: Settings, safety and a full test run
- [ ] Session 10: Install on the phone and test
- [ ] Sessions 11-16: Bestiary mod
