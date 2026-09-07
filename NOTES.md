# NOTES.md — Light SDK recon for the Prayer List tool

Phase 0 recon. No app code written. Every claim below points at a file and line
in this repo so it can be re-checked. All paths are repo-relative.

Sources read: `docs/`, `sdk/client/`, `sdk/ui/`, `lint-rules/`, `plugin/`,
`examples/authenticator`, `examples/weather`, `examples/ui-demo`,
`examples/audio-demo`, `tool/`.

---

## 1. UI components the SDK gives you

All Compose components live in `:sdk:ui`
(`sdk/ui/src/main/kotlin/com/thelightphone/sdk/ui/`). They render in black &
white and size themselves off a 27 × 31 grid, so there is no pixel sizing —
you multiply grid units. Nothing here carries meaning by color.

### Text

| Component | Where | Notes |
|---|---|---|
| `LightText(text, variant, …)` | `LightText.kt:76` | The text primitive. `align`, `lighten` (secondary color), `underline`, `monospace`, `maxLines`, `overflow`, `color`. |
| `LightTextVariant` enum | `LightText.kt:22` | `Title, Subtitle, Heading, Subheading, Copy, Button, Paragraph, ParagraphWide, Detail, Fine, Superfine, Micro` (12 sizes). Actual sizes: `LightTheme.kt:72`. |

There is **no** editable inline text field. Text entry is a full-screen flow
(see below).

### Text entry (full screen + read-only field)

| Component | Where | Notes |
|---|---|---|
| `LightTextInputEditor(title, state, onSubmit, onBack, keyboardOptionsFlow, …)` | `LightTextInputEditor.kt:48` | Full-screen editor: top bar with back, big underlined input, embedded LP3 keyboard, submit button in a bottom bar. `singleLine`, `initialCaps`, `submitLabel`, `submitIcon`, `showBackButton`, `editorKey`. |
| overload taking a `Lp3KeyboardViewModel` directly | `LightTextInputEditor.kt:104` | Lower-level; the first overload is what tools use. |
| `LightTextField(label, value, placeholder, onClick)` | `LightTextField.kt:24` | **Read-only** display of a value with a label and underline. Tapping it is expected to open a `LightTextInputEditor` on another screen. This is the standard "edit a field" pattern (see `examples/weather`). |
| `rememberKeyboardOptions()` | `sdk/client/.../LightKeyboardManager.kt:51` | Supplies the `StateFlow<KeyboardOptions>` the editor needs; pulls user prefs from LightOS. `defaultKeyboardOptions()` at `LightTextInputEditor.kt:271` is the offline fallback. |
| `state` = `androidx.compose.foundation.text.input.TextFieldState` | used at `LightTextInputEditor.kt:48` | Create with `rememberTextFieldState(initial)`. |

State machine pattern: `examples/weather/.../WeatherHomeScreen.kt:86` switches
one screen between an editor mode and content modes rather than navigating.
`examples/ui-demo/.../UiDemoTextInputEditorScreen.kt` shows the editor as its
own `SimpleLightScreen<String>` returning the typed value via `goBack`.

### Bars & buttons

| Component | Where | Notes |
|---|---|---|
| `LightTopBar(leftButton, center, rightButton)` | `LightTopBar.kt:40` | Fixed height 3 grid units (`LightTopBar.kt:17`). `center` is `LightTopBarCenter.Text` or `.TwoLineDetail` (`LightTopBar.kt:24`), optionally clickable. |
| `LightBottomBar(items, …)` | `LightBottomBar.kt:31` | Action bar. **Max 5 items** (`LightBottomBar.kt:36`); **if any item is text, max 3 items** (`LightBottomBar.kt:39`). `null` entries render as spacers. |
| `LightBarButton` sealed interface | `LightBarButton.kt:14` | `LightBarButton.Text(text, onClick)` (`:18`), `LightBarButton.Icon(painter, …)` for your own painter (`:29`), `LightBarButton.LightIcon(icon, onClick, …)` for a built-in icon (`:39`). `typealias LightTopBarButton` / `LightBottomBarItem` both = `LightBarButton` (`LightBarButton.kt:51`). |
| **Back button** | provided by the framework | `sdk/client/README.md:64` — the SDK renders its own back bar and wires the system back gesture. **Do not build one.** |

### Icons

| Component | Where | Notes |
|---|---|---|
| `LightIcon(icon, …)` | `LightIcon.kt:23` | Tinted with `colors.content`. Size in grid units (`width`/`height`/`size`). |
| `LightIcons` object | `LightIcons.kt:8` | ~130 named monochrome icons, e.g. `ADD`, `BACK`, `CLOSE`, `DELETE`, `TRASH`, `PENCIL`, `ACCEPT`, `DENY`, `SETTINGS`, `SEARCH`, `LIST`, `LARGE_LIST`, `STAR` / `STAR_OUTLINE`, `SELECT_ON` / `SELECT_OFF`, `TOGGLE_STATE_ON` / `_OFF`, `UP` / `DOWN`, `ARROW_RIGHT`, `REVERSE_ORDER`, `LOOP`, `ELLIPSES`, `CONTACTS`, `SPACER`. Full list is the file. `LightIcons.allEntries` (`:434`) enumerates them. |

There is **no** checkbox / radio / switch composable — `TOGGLE_STATE_ON/OFF`
and `SELECT_ON/OFF` are just icons you place yourself.

### Scrolling

| Component | Where | Notes |
|---|---|---|
| `LightScrollView(modifier, scrollBarPosition, scrollState) { … }` | `LightScrollView.kt:97` | Column-based scroll region with the Light scrollbar. Used everywhere for lists of a few dozen rows (`examples/*`). Put it in a `Column` with `Modifier.weight(1f)`. |
| `LightLazyScrollView(…, uniformItemHeightGridUnits, content)` | `LightScrollView.kt:147` | Lazy version; needs a **uniform row height** in grid units. Use for long lists. |
| `LightScrollBarPosition` | `LightScrollView.kt:51` | `Outside` (default) / `Inside`. |

### Modals / overlays

| Component | Where | Notes |
|---|---|---|
| `LightFullscreenModal(message, onClose)` | `LightFullscreenModal.kt:16` | Full-screen message with a single close button. Tools drive it with a nullable `String` in the view model (`examples/authenticator/.../AuthenticatorViewModel.kt:18`, rendered at `AuthenticatorHomeScreen.kt:141`). |
| `LightModalManager` (object) + `LightModal` interface | `LightModalManager.kt:52` / `:25` | App-wide **transient** overlay, one at a time, auto-dismiss after `DEFAULT_DURATION` = 2s (`LightModalManager.kt:54`). For toasts/confir…-flash, not for dialogs. |

There is **no** confirm/alert dialog component. The established pattern for a
destructive confirm is a dedicated screen returning `Boolean` —
`examples/authenticator/.../AuthenticatorConfirmRemoveScreen.kt` (a
`SimpleLightScreen<Boolean>` with a "CONFIRM" bottom-bar button).

### Progress

| Component | Where | Notes |
|---|---|---|
| `LightProgressBar(colors, progress)` | `LightProgressBar.kt:27` | Static bar. |
| `LightTouchableProgressBar(colors, progress, onValueChange)` | `LightProgressBar.kt:45` | Draggable — doubles as a slider. |

### Theme

| Component | Where | Notes |
|---|---|---|
| `LightTheme(colors, typography, surfaceScheme) { … }` | `LightTheme.kt:203` | Wrap every screen's content in this. |
| `LightThemeTokens.colors` / `.typography` | `LightTheme.kt:163` | `colors.background`, `colors.content`, `colors.contentSecondary` — the only three colors (`LightTheme.kt:31`, values at `:53`). Dark = black bg/white content; Light = the inverse. |
| `LightThemeController` (object) | `LightThemeController.kt:12` | Global light/dark state: `colors` `StateFlow`, `setDarkTheme()`, `setLightTheme()`, `toggle()`, `isDarkTheme`. Screens do `val c by LightThemeController.colors.collectAsState()` then `LightTheme(colors = c)` (`tool/.../HomeScreen.kt:80`). |

### Layout helpers (grid)

`LightGrid.kt` — `LightGrid.WIDTH = 27`, `HEIGHT = 31` (`:13`). Extension fns,
all `@Composable`:
- `Float.gridUnitsAsDp()` — horizontal units → dp (`:19`)
- `Float.verticalGridUnitsAsDp()` — vertical units → dp (`:25`)
- `Float.designVerticalPxToSp()` / `designVerticalPxToDp()` — scale a design px value (`:33`, `:39`)

Idiom seen everywhere: `Modifier.padding(horizontal = 1f.gridUnitsAsDp())`,
`padding(vertical = 0.75f.gridUnitsAsDp())`.

### Interaction

`Modifier.lightClickable(onClick = …)` — `LightClickable.kt:22`. No visual
press ripple (monochrome design); fires haptics on finger-down if the user has
them enabled. **Use this, not `Modifier.clickable`.**

### Also present (not needed for Prayer List, noted for completeness)

`LightQrCodeScanner` (`LightQrCodeScanner.kt:59`, plus a permission-aware
wrapper in `sdk/client/.../LightClientUiUtils.kt:38`), `LightNfcTapReader`
(`LightNfcTapReader.kt:30`), `LightEmbeddedLp3Keyboard`
(`keyboard/LightEmbeddedLp3Keyboard.kt:24`), `LightHapticFeedback`
(`LightHapticFeedback.kt:9`), `lightFontFamily()` (`LightFont.kt:10`).

---

## 2. What `LightScreen` / `LightScreenViewModel` require

Base classes are in `sdk/client/src/main/kotlin/com/thelightphone/sdk/`.
Note: the base view-model class is named **`LightViewModel`**, not
`LightScreenViewModel` (`LightViewModel.kt:6`).

### `SimpleLightScreen<ResultType>` — `LightScreen.kt:11`

Screen with no view model. You must override:
- `@Composable fun Content()` — `LightScreen.kt:17`

Optional lifecycle overrides: `willShow()`, `willHide()`, `onAppPause()`,
`onScreenDestroy()` — `LightScreen.kt:19-22`.

Provided to you:
- `navigateTo(screenFactory, resultCallback?)` — `LightScreen.kt:40`. Factory is
  `(SealedLightActivity) -> SimpleLightScreen<T>`; the callback fires when the
  child calls `goBack(result)`.
- `goBack(result: ResultType? = null)` — `LightScreen.kt:45`.
- `lightContext: SealedLightContext` — `LightScreen.kt:15` (persistence handles,
  see §4).
- Implements `LightKeyHandler` (`LightKeyHandler.kt:5`) — override `onKeyDown` /
  `onKeyUp` / `onKeyMultiple` for hardware keys. BACK/HOME never reach the
  screen (`examples/ui-demo/.../UiDemoKeyEventsScreen.kt:44`).

### `LightScreen<ResultType, VM : LightViewModel<ResultType>>` — `LightScreen.kt:51`

Screen with a view model. In addition to `Content()`, you must provide:
- `override val viewModelClass: Class<VM>` — `LightScreen.kt:54`
- `override fun createViewModel(): VM` — `LightScreen.kt:55`
- `viewModel: VM` is then available (lazy, `LightScreen.kt:60`).

This is exactly the "direct reference to the ViewModel's class type + a factory
method" the project's CLAUDE.md calls for. Canonical shape
(`tool/src/main/kotlin/com/thelightphone/sample/HomeScreen.kt:66`):

```kotlin
class FooScreen(sealedActivity: SealedLightActivity)
    : LightScreen<Unit, FooViewModel>(sealedActivity) {
    override val viewModelClass get() = FooViewModel::class.java
    override fun createViewModel() = FooViewModel(lightContext.dataStore)
    @Composable override fun Content() { /* wrap in LightTheme { } */ }
}
```

The framework forwards lifecycle into the VM: `notifyWillShow` → `onScreenShow`,
etc. (`LightScreen.kt:74-87`).

### `LightViewModel<T>` — `LightViewModel.kt:6`

Extends `androidx.lifecycle.ViewModel`, so `viewModelScope`, `onCleared()` and
`MutableStateFlow` are the tools. Overridable hooks:
- `onScreenShow(screen)` — `LightViewModel.kt:7` (called every time the screen
  comes to front — examples re-load data here)
- `onScreenHide(screen)` — `:8`
- `onAppPause()` — `:9`
- `onBackPressed(): Boolean` — `:14`. Return `true` to consume the back press
  (blocks the provided back button). Default `false`. Per CLAUDE.md, only
  override if genuinely needed.

### The initial screen

Exactly one screen in the tool must be annotated `@InitialScreen`
(`sdk/client/.../InitialScreen.kt:8`; requirement stated at
`sdk/client/README.md:28`). More than one, or zero, fails the build.

### Optional tool entry point

`@EntryPoint object … : LightEntryPoint` (`sdk/client/.../EntryPoint.kt:10`).
`onToolCreate(serverData)` runs once at process start (`:13`);
`onPushNotification` (`:15`) only matters if `enablePushNotifications` is `true`
(`:17`). Prayer List needs no network/push, so the stub in
`tool/.../ToolEntryPoint.kt` can stay as-is or be trimmed.

### Navigation rules (from CLAUDE.md + `sdk/client/README.md:53`)

- Navigate only with `navigateTo`. No Android Intents / system nav.
- No hand-rolled back button.
- To hand a value back to the opener: child `goBack(result)` → opener's
  `resultCallback`. Examples: `AuthenticatorHomeScreen.kt:93` (open detail),
  `UiDemoTextInputEditorScreen.kt:39` (return typed string).

---

## 3. What the lint rules / build plugin forbid

Two enforcement layers. **Both run at build time**; violations fail
`./gradlew :tool:assembleDebug`.

### A. Gradle plugin static scan — `plugin/src/main/kotlin/com/thelightphone/plugin/LightSdkPlugin.kt`

Scans every `.kt` file in `tool/src` line-by-line
(`LightSdkPlugin.kt:370`, `findSourceLineViolations` `:188`).

**Blocked imports** (`LightSdkPlugin.kt:81`): `android.app.*`,
`android.content.Context`, `android.content.Intent`, `ComponentName`,
`BroadcastReceiver`, `ContentProvider`, `ServiceConnection`,
`androidx.compose.ui.platform.LocalContext` / `LocalView` /
`LocalLifecycleOwner`, `androidx.lifecycle.compose.LocalLifecycleOwner`,
`androidx.activity.*`, `androidx.appcompat.*`, `java.lang.reflect.*`,
`java.lang.invoke.*`, `kotlin.reflect.*`.

**Blocked code patterns** (`LightSdkPlugin.kt:100`): any use of
`LocalContext` / `LocalView` / `LocalActivity` / `LocalLifecycleOwner`;
casting to anything ending `Activity` or to
`Context/ContextWrapper/Application/Service/ContentProvider/BroadcastReceiver`;
`startActivity(` / `startService(` / `bindService(` / `registerReceiver(` /
`getSystemService(` / `contentResolver` / `getBaseContext(` /
`attachBaseContext(` / the `createXContext(` family; reflection —
`.javaClass`, `.java.<x>`, `Class.forName(`, `getDeclaredMethod`/`getMethod`/
`getDeclaredField`/`getField`, `MethodHandles`.
Message for `startActivity`: *"use `LightScreen.navigateTo()` instead"*.

**Dependencies — allow-list only** (`LightSdkPlugin.kt:17`, `ALLOWED_DEPENDENCIES`).
Relevant entries that ARE allowed: `androidx.compose*`,
`androidx.activity:activity-compose`, `androidx.annotation`,
`org.jetbrains.kotlinx:kotlinx-coroutines`, `androidx.lifecycle`,
`androidx.datastore`, `androidx.room`, `androidx.work`, `androidx.startup`,
`org.jetbrains.kotlinx:kotlinx-serialization`, `kotlinx-io`,
`kotlinx-datetime`, `com.squareup.okhttp3:okhttp`, `io.ktor`, plus keyboard /
media3 / zxing / sol4k / bouncycastle. Anything else → configuration fails,
and the error prints the whole allow-list. It also checks the **resolved**
graph for substitution (`:475`).

**Plugins — allow-list only** (`LightSdkPlugin.kt:47`): the six already in
`tool/build.gradle.kts` plus `com.android.library`. No `buildscript {}`, no
`resolutionStrategy`, no `apply(from=…)`, no custom `srcDirs`
(`UNIVERSAL_BUILD_SCRIPT_PATTERNS` `:133`).

**Build-script fields you may NOT set** in `tool/build.gradle.kts`
(`CONSUMER_BUILD_SCRIPT_PATTERNS` `:146`): `applicationId`, `versionCode`,
`versionName`, `namespace` — these come from `tool/lighttool.toml`
(`docs/tool_metadata/README.md`).

**KSP processors — allow-list** (`LightSdkPlugin.kt:77`): only
`androidx.room:room-compiler`.

**Other:** no hand-written `src/main/AndroidManifest.xml` (`:352`); no `.java`
files, Kotlin only (`:362` / `:214`).

### B. Android Lint custom rules — `lint-rules/src/main/kotlin/com/thelightphone/lint/`

Registered in `LightSdkIssueRegistry.kt:9`. All `Severity.ERROR`.

| Issue id | Where | What it blocks |
|---|---|---|
| `LightSdkLocalContext` | `ActivityAccessDetector.kt:20` | reading `LocalContext.current` |
| `LightSdkLocalView` | `ActivityAccessDetector.kt:34` | reading `LocalView.current` |
| `LightSdkActivityCast` | `ActivityAccessDetector.kt:48` | `as`/`as?` cast to `Activity` / `ComponentActivity` / `AppCompatActivity` / `LightActivity` |
| `LightSdkInvalidLightJob` | `LightJobDetector.kt:23` | `@LightJob` not on a **top-level `val` of type `LightJobHandler`** |
| `LightSdkLightJobEmptyKey` | `LightJobDetector.kt:38` | `@LightJob` with an empty/non-literal key |

`tool/build.gradle.kts:43` sets `warningsAsErrors = false` but promotes
`RestrictedApi` to an error.

### Practical effect for Prayer List

Everything the app needs is inside the allow-list. Don't reach for a
Context, don't add a JSON library (use `kotlinx-serialization`, already
allowed), don't add a dialog library (build from primitives), get grouping/
sorting done in Kotlin. If something seems to need a forbidden API, stop and
ask (per CLAUDE.md §7).

---

## 4. Options for saving data on the device

All offline, all on-device (CLAUDE.md §1). Handles come from
`SealedLightContext` — `sdk/client/.../LightActivity.kt:229`:

```
lightContext.dataStore : DataStore<Preferences>   // LightActivity.kt:230
lightContext.filesDir  : File                     // LightActivity.kt:231
lightContext.fileShare : LightFileShare           // LightActivity.kt:232
lightContext.readAsset(path): ByteArray           // LightActivity.kt:233
lightContext.buildDatabase(cls, name): RoomDatabase  // LightDb.kt:6 (extension)
```

### Option A — Room (SQLite) — recommended for the Prayer List model

- `SealedLightContext.buildDatabase(dbClass, dbName)` — `LightDb.kt:6`. Thin
  wrapper over `Room.databaseBuilder`.
- `androidx.room` is allow-listed (`LightSdkPlugin.kt:38`); `room-compiler` is
  the one allowed KSP processor (`:78`); `tool/build.gradle.kts:63` **already**
  wires `ksp(libs.androidx.room.compiler)`. Room runtime/ktx are re-exported
  `api` by `:sdk:client` (`sdk/client/build.gradle.kts:62-63`), so no new
  dependency is needed. Room version 2.7.0 (`gradle/libs.versions.toml:10`).
- Full worked example: `examples/authenticator/` —
  `TotpDatabase` (`@Database`, `TotpDatabase.kt:6`),
  `TotpAccountDao` (`@Dao` with `@Query`/`@Insert`/`@Update`, `TotpAccountDao.kt`),
  a repository holding a singleton DB (`TotpAccountRepository.kt:61`),
  DB built from the screen via `lightContext.buildDatabase(...)`
  (`AuthenticatorHomeScreen.kt:37`), VM reads it on `Dispatchers.IO`
  (`AuthenticatorViewModel.kt:37`).
- Fits Groups / People / Entries with foreign keys, `ORDER BY … COLLATE NOCASE`
  for the alphabetical lists, and soft-delete flags (`archived`) as columns.

### Option B — Preferences DataStore

- `lightContext.dataStore` — one shared store per tool, name `"DEFAULT_DATASTORE"`
  (`LightActivity.kt:241`). `androidx.datastore` allow-listed; re-exported `api`
  (`sdk/client/build.gradle.kts:55`).
- Key/value only. Pattern: `object … { val X = stringPreferencesKey("x") }`
  (`examples/weather/.../WeatherPreferences.kt`), read with
  `dataStore.data.first()` / observe `dataStore.data`, write with
  `dataStore.edit { }` (`examples/weather/.../WeatherViewModel.kt:187,288`).
- Good for small singletons: last-opened tab, "seed already imported" flag,
  theme choice. **Not** a good fit for the relational person/entry data.

### Option C — plain files under `filesDir`

- `lightContext.filesDir: File` — standard app-private dir. Example:
  `examples/audio-demo/.../AudioLibraryRepository.kt:90` keeps a
  `File(filesDir, "recordings")` subdir and manages files directly.
- Could hold a single serialized JSON blob (via `kotlinx-serialization`,
  allow-listed). Simple, but you lose queries/indexes and hand-roll all
  concurrency. Reasonable fallback if Room feels heavy; Room is still the
  cleaner match for this data.

### Option D — `LightFileShare` (shared dir) — NOT for app data

- `lightContext.fileShare` — `sdk/client/.../LightFileShare.kt:14`. `read`
  (`:18`), `write` (`:42`), `list` (`:32`), `exists` (`:28`), `delete` (`:24`),
  `getUri` (`:37`). Path traversal is blocked (`:50`).
- Purpose is files **LightOS** reads via a content provider (ringtones,
  wallpapers) — `sdk/client/README.md:72`, `tool/.../HomeScreen.kt:47`. Private
  prayer data does not belong here.

### Seed data (Build order phase 1 & 6)

- Phase 1 "small seed dataset": just insert rows into Room on first run if the
  DB is empty (guard with a DataStore boolean or a `SELECT COUNT(*)`).
- Phase 6 "JSON seed import from the app's external files directory": **note a
  gap** — `SealedLightContext` exposes `filesDir` (internal) and
  `readAsset(path)` (`LightActivity.kt:233`, reads from bundled `assets/`), but
  **no accessor for `getExternalFilesDir(...)`**, and `android.content.Context`
  is import-blocked (`LightSdkPlugin.kt:83`). Bundled `assets/seed.json` +
  `readAsset` works within the sandbox; a true external-files path may need an
  SDK addition. Flag this when we reach phase 6.

### Background work (only if ever needed)

`LightWork` + `@LightJob` — `sdk/client/README.md:362`. Not needed for Prayer
List (no sync, no notifications) and comes with the two lint rules in §3B.

---

## 5. Quick reference — building order alignment

| Phase | Key SDK pieces |
|---|---|
| 1 Data layer | Room via `buildDatabase` (`LightDb.kt:6`); repo singleton pattern (`TotpAccountRepository.kt:61`) |
| 2 Home + Group screens | `LightScreen` + `LightViewModel`; `LightTopBar`, `LightScrollView`, `LightText`, `lightClickable`; `navigateTo` |
| 3 PersonScreen tabs | no tab component exists — build tabs from a `Row` of `LightText`/`LightIcon` + selected-state `MutableStateFlow`; body is `LightScrollView` |
| 4 Entry create/edit + mark-answered | dedicated `LightScreen` with `LightTextInputEditor` (`LightTextInputEditor.kt:48`) + `rememberKeyboardOptions()`; a type selector built from `LightBarButton`s; mark-answered = set `answeredAt` column |
| 5 ManageScreen | forms of the same primitives; reorder = `sortOrder` int + `UP`/`DOWN` icons |
| 6 JSON seed | `readAsset("seed.json")` + `kotlinx-serialization` (see §4 gap note) |
| 7 Polish | `LightGrid` units, `LightText` empty-state messages, emulator at 1080×1240 (`docs/system_app/README.md`) |

## 6. Build

```bash
./gradlew :tool:assembleDebug     # must pass to finish a phase
./gradlew tasks                   # confirm task names
```

Needs `gpr.user` / `gpr.key` in `local.properties` (CLAUDE.md §2 — never read
or print that file). Metadata (id/label/version/permissions) lives in
`tool/lighttool.toml`, not the build script.
