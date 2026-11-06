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

Laptop tests, no phone needed (1 smoke test passed):

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

## Open questions

1. Zork's MIT release is source code. Confirm how playable story files are produced from it.

## Plan for added games

Follow Listen: `/sdcard` with `MANAGE_EXTERNAL_STORAGE`, granted by the laptop `.cmd` sync file.
That permission is not on the stock allow-list, so the SDK plugin needs the same one-line addition
Listen made to `LightToolMetadata.kt`. Not done yet; it belongs in the sync session.

## Status (end of Session 0, 2026-11-06)

Finished:

- Forked project cloned to `lp3-adventure`, branch `zmachine`. Bible code removed on this branch
  only; it is still on `main`.
- Tool renamed to Adventure, with a placeholder screen and one laptop smoke test. No engine code.
- Debug and release APKs both build. Laptop test passes. Nothing installed on the phone.
- `CLAUDE.md` rewritten for Adventure; old SDK notes moved to `docs/SDK_RECON_NOTES.md`.

Next: Session 1, story file loading and header, with the version check, tested on the laptop.

Worth knowing:

- Builds take about 6 minutes and this laptop has under 8 GB of memory. Close other apps first.
- The first release build ran past 10 minutes while failing, so allow extra time.
- The APK lists `INTERNET` and `CAMERA` because of SDK libraries, not our code.
- A clean Listen rebuild is untested since 2026-10-02. See the Build section.
- Zork story files are still an open question. Answer it before Session 4 needs one.

## Sessions

Session 0 is from the brief. Sessions 1-10 are a proposed outline; edit freely.

- [x] Session 0: Setup, SDK review, rename, notes
- [ ] Session 1: Story file loading and header; version check
- [ ] Session 2: Memory, text decoding, dictionary
- [ ] Session 3: Instruction decoding and core opcodes (version 3)
- [ ] Session 4: Objects, input and output; Zork I playable in a laptop test
- [ ] Session 5: Versions 4, 5 and 8; conformance testing on the laptop
- [ ] Session 6: Save, restore and undo
- [ ] Session 7: Phone screen: game text and command input
- [ ] Session 8: Game library; bundled Zork I-III
- [ ] Session 9: Laptop `.cmd` sync file for added games
- [ ] Session 10: Color, polish, first full test on the phone
