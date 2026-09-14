# CLAUDE.md — EPUB Reader for Light Phone III

Read this at the start of every session. If anything here conflicts with what you remember
about Android development, **this file wins** — this is not a standard Android environment.

---

## 1. Platform constraints (non-negotiable)

- **Target:** Light Phone III on LightOS. **1080 × 1240, 3.92", black and white.** API 34.
  **No Google Play Services.**
- **Monochrome screen — color never carries meaning.**
- **All app code in the `tool` module**, using primitives from `sdk/client`.
- **Kotlin, Compose, Coroutines, MVVM.** Screens extend `LightScreen`; view models extend
  `LightViewModel` (not `LightScreenViewModel` — that class doesn't exist).
- **Navigate with `navigateTo` only.** Back stack is automatic — do NOT override
  `onBackPressed()`.
- **The SDK does not render a back bar.** Every non-root screen places its own:
  ```kotlin
  LightTopBar(
      leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
  )
  ```
  Root (`@InitialScreen`) screens omit it. `sdk/client/README.md:64` says otherwise and is
  wrong.
- **Third-party libraries are restricted and lint-enforced.** Check `lint-rules/` first.
- **`Context` is import-blocked.** There is no `getExternalFilesDir`. Use
  `lightContext.fileShare` for any file the user drops onto the device.
- **Offline only.** No network, no sync, no accounts. `permissions` in `lighttool.toml`
  stays empty.
- **Confirm SDK components exist** in `sdk/client/` or `NOTES.md` before using them.

## 2. Package identity

⚠️ Two other tools are already installed on this phone: `com.thelightphone.app` (prayer
list) and `com.thelightphone.smallgroup`. **This tool's `id` in `lighttool.toml` must
differ from both**, or installing it would overwrite one of them and destroy its data.
Suggested: `com.thelightphone.reader`.

`serverPackage` is `com.lightos` for the physical LP3.

## 3. Build commands

```bash
./gradlew :tool:assembleDebug
```

Requires `gpr.user` / `gpr.key` in `local.properties`. **Never read, print, or commit it.**

---

## 4. What the app is

A personal ebook reader. Jordan converts EPUBs on his laptop, pushes them to the phone, and
reads them here. A library of books, a table of contents per book, and a reading screen
that remembers exactly where he stopped.

Design values: quiet, readable, and it opens exactly where he left off. No library
management, no metadata editing, no notifications.

---

## 5. Architecture — two pieces

### Piece A: the converter (runs on the PC, NOT on the phone)

A standalone script in `tools/converter/`, **not** part of the Android build.

The phone never parses EPUB. EPUB is a zip of HTML with wildly variable structure across
publishers; doing that on this hardware is slow and fragile. The laptop does it once.

**Input:** any `.epub` file.
**Output:** one folder per book.

```
books/<book-slug>/
  meta.json
  001.txt
  002.txt
  ...
```

`meta.json`:
```json
{
  "slug": "the-hobbit",
  "title": "The Hobbit",
  "author": "J.R.R. Tolkien",
  "chapters": [
    { "index": 1, "title": "An Unexpected Party", "file": "001.txt", "chars": 28451 }
  ]
}
```

Each `NNN.txt` is one chapter as plain text. Paragraphs separated by a blank line. No
markup, no HTML entities, no image references. Preserve paragraph breaks; discard
everything else.

The converter reads the EPUB's OPF spine for chapter order and the NCX or nav document for
chapter titles, falling back to "Chapter N" when a title is missing.

### Piece B: the app (on the phone)

Reads only `meta.json` and the `.txt` files from `lightContext.fileShare`. Knows nothing
about EPUB, zip, or HTML.

---

## 6. Pagination finding (Session 0)

Kindle-style pagination is achievable and is the approach `ReaderScreen` will use — not a
scrolling reader.

- `TextMeasurer` / `rememberTextMeasurer()` (`androidx.compose.ui.text`) is reachable from
  the `tool` module. It reaches `tool` transitively as an `api` dependency of `sdk/client`
  (via `sdk/ui`'s `api(libs.compose.ui)`), so no new dependency is needed.
- It is **not** on the plugin's blocked-import list or the lint rules' blocked-property
  list — those only block `androidx.compose.ui.platform.LocalContext` / `LocalView` /
  `LocalLifecycleOwner`, a different package.
- Plan: fix the body `TextStyle`, measure a chapter's text against the content box
  (screen height minus top bar minus margins) with `textMeasurer.measure(...)` — not
  `@Composable`, so it can run once per chapter on a background coroutine — then walk the
  resulting line boundaries, accumulating lines until the available height would be
  exceeded, to find each page's text range. Cache the resulting page-range table per
  chapter.

---

## 7. Reading position

The single most important behavior in this app. Getting this wrong makes it useless.

- Position is stored per book: which chapter, and how far into it.
- Saved continuously while reading and flushed when leaving the screen, the same autosave
  contract the Small Group app uses for answers.
- Opening a book goes straight back to that position — not to the start, not to the
  chapter's beginning.
- Stored in Room, independent of the book files. `adb install -r` must never lose it.

---

## 8. Screens

**LibraryScreen** (`@InitialScreen`) — every book found on the device. Each row: title,
author, and how far through it Jordan is. Tapping opens the book at its saved position.

**ContentsScreen** — the book's chapters. Reachable from the reading screen. Tapping a
chapter jumps there and resets position to its start.

**ReaderScreen** — the text. Chapter title at the top, body text filling the screen.
Navigation forward and backward through the book. Access to Contents.

---

## 9. Build order

One phase per session. Do not start the next until the current compiles and Jordan has
confirmed it looks right.

0. **Recon and pagination spike** — the make-or-break question. Read `docs/`,
   `sdk/client/`, `examples/`, `lint-rules/`. Then determine whether Kindle-style
   pagination is achievable: can text be measured to find where a screenful ends (a
   `TextMeasurer` or equivalent), or is scrolling the only viable model? **Report the
   answer before writing any reader code.** Everything downstream depends on it.
1. **Converter** — the PC-side script. Convert one book, verify the output by eye.
2. **Data layer** — read `meta.json` and chapter files; position storage. No UI.
3. **LibraryScreen and ContentsScreen** — navigation only, no reading yet.
4. **ReaderScreen** — the text, page or scroll per the Session 0 answer, with position
   saving.
5. **Polish** — typography, margins, and line spacing for sustained reading.

---

## 10. Working agreement

- If an SDK component doesn't exist, say so and propose building it from primitives.
- If a lint rule blocks an approach, name what it blocked and give two alternatives.
- Keep functions short and the layout obvious. Jordan reads this code himself and is not an
  Android developer.
- Don't add features that aren't in this spec. Suggest, don't build.
- **Never alter book text.** Display exactly what the converter produced. If extraction
  looks wrong, surface it rather than patching it in the app.
