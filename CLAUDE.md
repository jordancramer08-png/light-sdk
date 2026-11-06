# CLAUDE.md: Adventure for Light Phone III

Read this and `NOTES.md` at the start of every session. If anything here conflicts with what you
remember about Android development, **this file wins**. This is not a standard Android
environment.

## What the app is

Adventure is a Light Phone 3 tool that plays Z-machine story files: text adventures such as Zork.
Versions 3, 4, 5 and 8 are supported. Version 6 is not.

Zork I-III are bundled from MIT-licensed source. Every other game is a file Jordan adds himself.

## Platform

- Light Phone III on LightOS: 1080 x 1240, 3.92", API 34, no Google Play Services.
- **Full color.** LightOS grayscale is turned off on this phone.
- **Sideload only**, like Listen. This tool is not being built for Light signing.
- Tool id `com.thelightphone.adventure`, label `Adventure`, `serverPackage = "com.lightos"`.
  Never reuse another tool's id: installing over it destroys that tool's data.

## Hard rules

- **No network calls and no GPS.** The APK lists `INTERNET` because SDK libraries add it, so this
  rule is kept in our code. Never use Ktor, OkHttp or any other network API.
- **No emulator.** Test the engine on the laptop first and the phone last.
- **No third-party libraries** unless the SDK allow-list permits them.
- **Do not install on the phone or commit unless Jordan asks.**
- **Never read, print or commit `local.properties`** in this or any sibling project.
- Do not turn off certificate checks.

## Code layout

- All app code is in the `tool` module, in Kotlin.
- **Engine:** `tool/src/main/kotlin/com/thelightphone/adventure/engine/`. Plain Kotlin with **no
  Android imports**, and no SDK imports. It talks to the UI through plain interfaces.
- **Engine tests:** `tool/src/test/kotlin/com/thelightphone/adventure/engine/`.
- **UI:** `tool/src/main/kotlin/com/thelightphone/adventure/`.
- **Bundled games:** `tool/src/main/assets/`, named `.dat` or `.bin`, not `.z3`, `.z5` or `.z8`.

## SDK rules

- Compose, Coroutines, MVVM. Screens extend `LightScreen` or `SimpleLightScreen`; view models
  extend `LightViewModel`. There is no `LightScreenViewModel`, whatever the README says.
- Navigate with `navigateTo`. Every non-root screen draws its own back button:
  ```kotlin
  LightTopBar(
      leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
  )
  ```
  The `@InitialScreen` root omits it. The README's claim that the SDK draws a back bar is wrong.
- `lighttool.toml` sets the id, label, version and permissions. Do not write an
  `AndroidManifest.xml`, or set `applicationId`, `versionCode`, `versionName` or `namespace` in
  the build file.
- The plugin scans all of `tool/src`, tests included, for blocked code: `Context`, `Intent`,
  Activity access, `getSystemService`, `contentResolver` and reflection such as `.javaClass`.
- Story files are binary. `lightContext.fileShare` is text only and would corrupt them.
- `docs/SDK_RECON_NOTES.md` has detailed SDK component notes. It was written for an earlier tool,
  so check anything important against the source.

## Commands

Run from PowerShell in the project root:

```powershell
.\gradlew.bat :tool:testDebugUnitTest   # laptop tests, no phone
.\gradlew.bat :tool:assembleDebug       # tool\build\outputs\apk\debug\tool-debug.apk
.\gradlew.bat :tool:assembleRelease     # tool\build\outputs\apk\release\tool-release.apk
```

Use the release build on the phone; debug builds run much slower. After installing, reboot the
phone, because the LightOS launcher only shows a new tool after a restart.

## Working agreement

- One session at a time, following the checklist in `NOTES.md`. Stop when a session is done.
- If an SDK component does not exist, say so and propose building it from primitives.
- If a rule blocks an approach, name what blocked it and give two alternatives.
- Keep functions short and the layout obvious. Jordan reads this code and is not an Android
  developer.
- Do not add features outside the plan. Suggest them instead.
