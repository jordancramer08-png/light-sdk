# CLAUDE.md — Reader (EPUB) for Light Phone III

Read this at the start of every session. If anything here conflicts with what you remember
about Android development, **this file wins** — this is not a standard Android environment.
`NOTES.md` is the verified SDK reference (components, lint rules, storage options, with file
and line pointers). Read it before writing UI or storage code.

Jordan is not an Android developer. He reads this code himself: keep functions short, names
plain, and explain what you changed in plain words at the end of each phase.

---

## 1. Platform constraints (non-negotiable)

- **Target:** Light Phone III on LightOS. **1080 × 1240, 3.92".** API 34. **No Google Play
  Services.** Jordan's phone has grayscale turned off and shows full color, so this app may
  use color (the reading themes and their accent, §9). Keep it quiet, and never let color be
  the only way something is shown — a label, icon or position must say it too.
- **All app code in the `tool` module**, package `com.thelightphone.reader`, using primitives
  from `sdk/client` and `sdk/ui`.
- **Kotlin, Compose, Coroutines, MVVM.** Screens extend `LightScreen` (or `SimpleLightScreen`);
  view models extend **`LightViewModel`** (there is no `LightScreenViewModel`).
- **Navigate with `navigateTo` only.** Back stack is automatic — do NOT override
  `onBackPressed()`.
- **The SDK does not render a back bar.** Every non-root screen places its own:
  ```kotlin
  LightTopBar(leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }))
  ```
  The root (`@InitialScreen`) screen omits it.
- **Libraries and imports are restricted and enforced at build time** (NOTES.md §3). No
  `android.content.Context`, no `LocalContext`, no reflection, no new dependencies outside
  the allow-list. `java.io.File` and `java.util.zip` are fine.
- **Offline only.** No network. `permissions = []` stays empty.
- **No emulator.** Jordan's laptop has ~8 GB RAM; the emulator + Android Studio + Gradle
  crashes it. Verification = unit tests + building + installing on the real phone.

## 2. Package identity ⚠️

Other tools on this phone: `com.thelightphone.app` (Prayer), `com.thelightphone.smallgroup`,
`com.thelightphone.bible`. **This tool is `com.thelightphone.reader`** — the same id as the
September reader, on purpose, so this build *replaces* it. Never change the id in
`tool/lighttool.toml`.

## 3. Build, install, verify

```powershell
.\gradlew.bat :tool:testDebugUnitTest      # unit tests (phase 1 onward)
.\gradlew.bat :tool:assembleDebug          # must pass to finish any phase
```

Jordan installs with `scripts\Build and Install Reader.cmd` (builds, `adb install -r`,
offers to reboot). **After installing, the phone must reboot** — the LightOS launcher caches
its menu. Books go onto the phone with `scripts\Send Books to Phone.cmd` and come off it
with `scripts\Remove Books from Phone.cmd` (§5).

`local.properties` may contain private keys. **Never read, print, or commit it.**

## 4. Branches

This app lives on the **`reader`** branch of `jordancramer08-png/light-sdk`. The `main`
branch holds the Bible tool. **Never merge, rebase onto, or push to `main` from here.**
Commit at the end of each phase with a plain message and `git push origin reader`.

## 5. How books get onto (and off) the phone

`scripts\Send Books to Phone.cmd` copies `.epub` files into the app's private storage:

```
<app filesDir>/shared/books/<file name>.epub
```

(`adb push` to `/data/local/tmp`, then `run-as com.thelightphone.reader cp …` — works because
debug builds are debuggable.) The app reads that folder with
`File(lightContext.filesDir, "shared/books")`.

**Do not use `lightContext.fileShare.read` for EPUBs** — it only hands back a text
`InputStreamReader`, which corrupts binary zip data. Use `java.io.File` + `java.util.zip.ZipFile`.

The September app left converted-text folders in `shared/books/<slug>/`. **Ignore
subdirectories there** — only `*.epub` files are books.

**Removing books.** `scripts\Remove Books from Phone.cmd` (→ `remove-books.ps1`) lists the
`*.epub` files in `shared/books/` in a searchable, multi-select picker, shows the chosen
ones, asks "Remove these N books? (Y/N)", deletes them with `run-as … rm`, then lists the
books left. It deletes only the `.epub` files: the app drops the book's `library/<slug>/`
cache on its next scan (§6), and saved places, reading lists and reading status (keyed by
slug) are kept, so a book sent again opens where Jordan stopped and is back in its lists.

## 6. Architecture

```
reader/
  epub/        pure Kotlin, NO Android imports — unit-testable on the PC
    EpubParser.kt       zip → Book(title, author, chapters[title, text])
    HtmlText.kt         XHTML → plain text (paragraphs split by one blank line)
    ContentFilter.kt    drops covers, title pages, ads, embedded TOCs, praise pages
    Series.kt           series + number: OPF metadata (calibre, then EPUB 3), else file name
    WordCount.kt        countWords (runs of non-space holding a letter or digit)
  data/
    LibraryStore.kt     scans shared/books/*.epub, caches parsed books
    BookMeta.kt         cached meta.json model (kotlinx-serialization)
    ReaderDatabase.kt   the one Room database (version 3) + readerDatabase() shared instance,
                        and MigrationToVersion3 (the 2 -> 3 backfill)
    ReadingPosition*.kt Room entity / dao / repository for saved places
    ReadingList*.kt     Room entities / dao / repository for reading lists
    BookStatus*.kt, ReadingStatusRepository.kt  Room entity / dao / repository for reading status
    DatabaseQueue.kt    runs list and status reads + writes one at a time, in order
    LibrarySortPreference.kt  remembered library sort (DataStore)
    LibraryFilterPreference.kt  remembered library Show filter (DataStore)
    LibraryGroupSeriesPreference.kt  remembered Group series On/Off (DataStore)
    ReaderSettingsPreference.kt  remembered size, typeface, line spacing, margins (DataStore)
    ReaderThemePreference.kt     remembered reading theme (DataStore)
  ReaderTextSize.kt     the five text sizes (pure Kotlin, unit-tested)
  ReaderSettings.kt     ReaderSettings (size + typeface + line spacing + margins) and the
                        ReaderTypeface / ReaderLineSpacing / ReaderMargins choices (pure Kotlin, unit-tested)
  ReaderTypography.kt   reader body + heading TextStyles for a ReaderSettings (shared with ReadingSettingsScreen)
  ReaderTheme.kt        the four themes: LightColors + accent each
  ReaderThemeController.kt  the app-wide current theme; ThemedScreen frame every screen uses
  ReadingLists.kt       list logic: LibraryView, listRows, neighbourSlug (pure Kotlin, unit-tested)
  ReadingStatus.kt      ReadingStatus, LibraryFilter, statusAfterOpening (pure Kotlin, unit-tested)
  SeriesGroups.kt       LibraryEntry (a book or a series row), libraryEntries (pure Kotlin, unit-tested)
  BookDetails.kt        the Book Details rows: reading time, time left, sizes, dates (pure Kotlin, unit-tested)
  LibraryScreen.kt, SortFilterScreen.kt, ContentsScreen.kt, ReaderScreen.kt, ReadingSettingsScreen.kt,
  ThemeScreen.kt, ListsScreen.kt, ListNameScreen.kt, DeleteListScreen.kt, ListBooksScreen.kt,
  AddToListScreen.kt, BookDetailsScreen.kt, SeriesScreen.kt, Divider.kt, RowIconButton.kt
  LibraryEntryList.kt   the book / series rows list, shared by LibraryScreen and SeriesScreen
  UniformRow.kt         a fixed-height row (divider included) for LightLazyScrollView lists
tool/schemas/           Room's saved schema for each database version (checked in)
```

**Parse once, then cache.** The first time a book is seen (or its file size / modified time
changes, or `PARSER_VERSION` is bumped), parse it and write
`<filesDir>/library/<slug>/meta.json` + `001.txt`, `002.txt`, … Every later open reads the
cache. meta.json also holds the series (`series`, `seriesNumber`, when there is one) and each
chapter's word count (`words`), counted once at this step (`PARSER_VERSION` 2). Delete cache
folders whose EPUB is gone. Parsing runs on `Dispatchers.IO`, one book at a time; the library
shows "Preparing N books…" meanwhile.

**Parse with plain Kotlin string handling, not `XmlPullParser` / `android.util.Xml`.** EPUB
XHTML in the wild is often not valid XML (HTML entities like `&nbsp;`, stray tags), and the
parser must run in JVM unit tests where Android classes are stubs. A small tolerant tag
tokenizer + an entity table (named entities incl. `&nbsp;` `&mdash;` `&rsquo;`, plus
`&#NNN;` / `&#xHH;`) is enough.

## 7. Reuse the September app — don't reinvent it

The September reader was verified on the phone. Its code is in this repo's history:

```
git show f59672f:tools/converter/convert.py
git show f59672f:tool/src/main/kotlin/com/thelightphone/reader/ReaderScreen.kt
git ls-tree -r --name-only f59672f -- tool      # the full list
```

- **`convert.py` is the spec for the EPUB parser.** Port it to Kotlin faithfully: container.xml
  → OPF → metadata (dc:title, dc:creator; "Untitled" / "Unknown" fallbacks) → manifest →
  spine (skip `linear="no"`, skip the `nav` item, only xhtml/html media types) → titles from
  NCX first (first occurrence per file wins), nav.xhtml fallback → `_repair_title` → HTML to
  text (block tags and `<br>` break paragraphs, skip script/style, collapse whitespace) →
  non-content filter (all of it: `MIN_CONTENT_CHARS = 300`, the title list, "also by", ad-slug
  domains, listing pages, praise pages) → untitled chapters become "Chapter N" → DRM check
  (`META-INF/encryption.xml` = skip the book, show it as "Can't open (DRM)") → encoding
  (declared, then UTF-8, then cp1252).
- **Keep the slug rule identical** (`slugify(title)`: lowercase, non-alphanumerics → `-`) and
  keep chapter numbering 1-based after filtering. With the `reading_position` table below
  unchanged, a phone that still has the September app's database keeps its saved places.
  Two books with the same slug: append `-2`, `-3` in file-name order.
- **Saved places table unchanged:** database `reading_position.db`, table
  `reading_position(bookSlug PK, chapterIndex, charOffset, updatedAt)`, exactly as the
  September app made it. Opened once with `lightContext.readerDatabase()` (wraps
  `buildDatabase`) and shared by every repository.
- **Database versions.** 1 = saved places. 2 = adds `reading_list(id PK auto, name,
  createdAt)` and `reading_list_book(listId, bookSlug, sortOrder, addedAt; PK listId+bookSlug)`.
  3 = adds `reading_status(bookSlug PK, status, updatedAt)` (`status` = the `ReadingStatus`
  enum name as text; no row = Want to Read), and fills it: every book with a saved place
  starts as `READING`. Books are keyed by slug, like saved places. **Never use a destructive
  migration**: every version bump needs a migration that keeps saved places and reading
  lists, plus a test. The SDK's `buildDatabase` can't take hand-written `Migration`s (it is
  a bare `Room.databaseBuilder(...).build()`, and `Context` is blocked), so migrations are
  Room `@AutoMigration`s declared on `ReaderDatabase`, generated from the schema files in
  `tool/schemas/` (`ksp` arg `room.schemaLocation`). **Commit the new `N.json` with each
  bump.** Hand-written SQL (a data step like the 2 -> 3 backfill, or a change auto-migration
  can't infer, like renaming or dropping a column) goes in an `AutoMigrationSpec`:
  `onPostMigrate(connection)` runs right after Room's generated statements, inside the same
  migration. `ReaderDatabaseMigrationTest` runs each generated migration against a
  recording stand-in (no SQLite engine is allowed in PC tests) and checks the exact
  statements, that every version step has a migration, and that `reading_position` and the
  list tables are unchanged and never written.
- **ReaderScreen:** keep its approach — `TextMeasurer` pagination per chapter, position as
  (chapter, char offset of the page's first character) never a page number, left 30% tap =
  back, rest = forward, crossing chapter boundaries, the chapter heading on a chapter's first
  page, `withExtraParagraphSpacing` (`"\n\n"` → `"\n\n\n"`), autosave on every turn + a
  blocking flush in `onScreenHide` / `onAppPause`, re-paginate on size change anchored at the
  current offset.
- **Fix one known flaw:** the September screen measured pages with a custom style (1.45 line
  height) but drew them with `LightText(variant = Paragraph)`, a different style — pages could
  under- or over-fill. **Draw with exactly the `TextStyle` you measure with** (the Bible
  tool's `ChapterScreen` on `main` does this with `Text(text, style = bodyStyle)`). Same for
  the chapter heading.

## 8. Library order and display

Jordan's PC library is `D:\Reading\Digital Books`: ~660 EPUBs in author folders, named
like `Cemetery of Forgotten Books 01. The Shadow of the Wind - Carlos Ruiz Zafón.epub`
(`Series NN. Title - Author.epub`, or `Title - Author.epub` for standalones). He sends a
handful at a time, not the whole library. On the phone the files sit flat in
`shared/books/`, with names flattened to plain ASCII by the send script.

- **Four sort orders and a Show filter**, chosen from a sort icon (`LightIcons.REVERSE_ORDER`
  — the SDK has no dedicated sort icon) in the right slot of the Library top bar. It opens
  `SortFilterScreen`, titled "Sort & Filter": "Sort by" Author A–Z, Author Z–A, Title A–Z,
  Title Z–A, then "Show" All, Want to Read, Reading, Finished (reading status, §9), then
  "Group series" On, Off. The current choice in each section is marked with a filled circle
  (`SELECT_ON`, others `SELECT_OFF`). Tapping any row returns all three choices
  (`SortAndFilter`) and redraws the list; back leaves them unchanged. The filter and
  grouping apply to all books only, not to a list.
- All comparisons are case- and accent-insensitive ("le Carré" and "Le Carre" group
  together). Author comes from the EPUB metadata; title from the EPUB metadata with a
  leading "The", "A" or "An" ignored ("The Shadow of the Wind" sorts under S).
- Ties always fall back to **file name, A to Z** — even in Author Z–A — so an author's
  series stays in order (01, 02, …).
- **Group series** (default On): books of one series (`series` in meta.json, §6; names
  matched case- and accent-insensitively) show as one row — series name, the first book's
  author, "N books · M finished" (lighter) — which opens SeriesScreen. The filter runs
  first, so a series row counts only the books the filter shows, and a series with only one
  book shown is a normal book row. A series row sorts by its name (title orders) or its
  first book's author, ties by that book's file name. Off: every book is its own row.
- All three are remembered in `lightContext.dataStore`: key `library_sort` (the enum name;
  missing or unknown = Author A–Z), key `library_filter` (the `LibraryFilter` name;
  missing or unknown = All) and key `library_group_series` (a boolean; missing = On).
  Logic: `LibraryList.kt` (`LibrarySort`, `libraryRows`), `ReadingStatus.kt`
  (`LibraryFilter`) and `SeriesGroups.kt` (`libraryEntries`); storage:
  `data/LibrarySortPreference.kt`, `data/LibraryFilterPreference.kt`,
  `data/LibraryGroupSeriesPreference.kt`.
- Row: title (from the EPUB metadata, one line), author (lighter), progress ("Not started" or
  "NN% read", computed from chapter char counts as in the September `LibraryScreen`; a
  Finished book shows "Finished" instead, in the accent like "NN% read").
- Empty library: "No books on this device yet." centered, lighter text. When a Show filter
  leaves nothing: "No books marked Finished." (the filter's name).
- The list is a `LightLazyScrollView` (only rows on screen are drawn). That view needs every
  row the same height, so each row is a `UniformRow` of 7 grid units: title, author and
  progress (or series name, author, count) one line each, ellipsized.

**Long lists and huge books.** Some books have hundreds of chapters (C.S. Lewis Complete
Works: 746). Any list that can grow long uses `LightLazyScrollView` + `UniformRow` with
one-line, ellipsized text — never a plain `LightScrollView` with a row per item. Nothing
slow (parsing, reading chapter files, paging) runs on the main thread; if it takes longer
than a blink the screen says "Preparing…" instead of freezing or showing a stale page.

## 9. Screens

**LibraryScreen** (`@InitialScreen`) — top bar: the lists icon (`LightIcons.LARGE_LIST`) on
the left (→ ListsScreen), "Library" or the shown list's name, and on the right the sort icon
(→ Sort & Filter, §8) for all books, or a pencil (→ ListBooksScreen) when a list is shown. Tap a
book → ReaderScreen. A list shows only its books, in the list's own order (sorting doesn't
apply); a book no longer on the phone is skipped but stays in the list. A list always shows
individual books, never series rows. The Library opens on all books each launch (the chosen
list isn't remembered). Empty list: "No books in this list yet." centered, lighter.

**SeriesScreen** — top bar: back, the series name. The series' books (those in the Library's
series row) in number order (1, 2, 2.5, 10; no number last; ties by file name), drawn with
the Library's own book rows; tapping one opens it in ReaderScreen. Progress and status are
re-read each time it comes to the front.

**ListsScreen** — top bar: back, "Lists", + (→ ListNameScreen, new list). "All books", then
the lists A–Z; the one shown now is marked `SELECT_ON`. Tapping a row hands it back to the
Library (`goBack(LibraryView)`). Each list has a pencil (rename, via ListNameScreen) and a
bin (→ DeleteListScreen: "Delete this list? The books stay on your phone." + DELETE).

**ListNameScreen** — `LightTextInputEditor`, one line, SAVE. Hands back the tidied name; an
empty name or back hands back nothing.

**ListBooksScreen** — rearranges one list: each book has up / down arrows (none at the top /
bottom) and × to take it out of the list (the book stays on the phone). Saved at once.

**AddToListScreen** — from Contents' "ADD TO LIST" bar button: every list with an on/off
switch (`TOGGLE_STATE_ON/OFF`); tapping a row puts the book at the end of that list or takes
it out. Any number of lists.

**ReaderScreen** — top bar: back, the current chapter title, and a `LightIcons.LIST` button
that opens Contents. Paged text below (§7). Opening a book makes a Want to Read book
Reading (Reading and Finished stay as they are). A page turn or Contents jump that lands on
the last page of the last chapter makes it Finished; reopening the book on that page, or
re-paging, does not (so a hand-set status isn't undone just by opening the book).
If a chapter takes more than 300 ms to read and page (a huge chapter, or the library still
busy), the page area shows "Preparing…" (lighter, centered) and taps are ignored until the
page is ready. Tapping the chapter title opens ReadingSettingsScreen.

**ReadingSettingsScreen** — top bar: back, "Reading Settings". Opened by tapping the
chapter title in the reader. Four stepper rows, each "Label   −  <value>  +" (drawn as text:
the SDK has no minus icon; Typeface uses "‹ ›" since fonts aren't a quantity), then a
"Theme  <name>" row that opens ThemeScreen, then a sample paragraph drawn with exactly the
reader's body style and margins, so every change shows live. At either end of a range the
button is lighter and does nothing (the value's name, not the tone, says where you are).

- **Text size** (`ReaderTextSize`): Small 0.85×, **Medium 1.0× (default)**, Large 1.15×,
  Larger 1.3×, Extra large 1.5× the SDK Paragraph size; the chapter heading scales too.
- **Typeface** (`ReaderTypeface`): **Light (the SDK font, default)**, Serif
  (`FontFamily.Serif`), Sans (`FontFamily.SansSerif`). Built-in families only, no font
  files. Body and chapter heading both use it.
- **Line spacing** (`ReaderLineSpacing`): Compact 1.25, **Normal 1.45 (default)**, Relaxed
  1.7 × the text size.
- **Margins** (`ReaderMargins`): left and right margin of the page, Narrow 0.75, **Normal
  1.5 (default)**, Wide 2.5 grid units.

Back returns all four as one `ReaderSettings` via `goBack(settings)`; ReaderScreen saves
them and re-paginates. One set for all books, remembered in `lightContext.dataStore` by
`ReaderSettingsPreference` (keys `reader_text_size`, `reader_typeface`,
`reader_line_spacing`, `reader_margins`, each the enum name; missing/unknown = that
setting's default). Any change of style or page width re-paginates anchored at the reader's
place — the first character of the page last turned or jumped to (`anchorOffset`), which
re-paging does not move — so switching back and forth never drifts off the passage.

**ThemeScreen** — top bar: back, "Theme". Four rows, each drawn in its own colors as a
preview, the current one marked `SELECT_ON`: **Dark** (default, the SDK dark look), Light,
Sepia, Night (dark gray, warm text). Each is a `LightColors` plus an accent color
(`ReaderTheme`); the accent colors the chapter heading on a chapter's first page and the
"NN% read" text in the library. Tapping a row applies it to every screen at once
(`ReaderThemeController`), remembers it in `lightContext.dataStore` (key `reader_theme`,
the enum name; missing/unknown = Dark), and goes back. A theme change never re-pages the
book. Every screen draws inside `ThemedScreen { }`.

**ContentsScreen** — the book's chapters; tapping one returns its index to ReaderScreen via
`goBack(index)`, which jumps to that chapter's start in place. A `LightLazyScrollView` of
4-grid-unit `UniformRow`s (titles one line, ellipsized); it opens with the chapter being
read as the top row. Bottom bar: "ADD TO LIST" (→ AddToListScreen), "DETAILS"
(→ BookDetailsScreen).

**Reading status** — each book is Want to Read (default, no row saved), Reading or Finished
(`ReadingStatus`, stored in the `reading_status` table, §7). Set automatically by the
reader (above) or by hand on Book Details. Status reads and writes go through
`DatabaseQueue`, so a change made just before leaving a screen is always saved.

**BookDetailsScreen** — top bar: back, "Details". The title (Subheading) and
author (lighter) at the top, then "Status" with three rows (Want to Read, Reading,
Finished), the current one `SELECT_ON`; tapping one saves it at once. That is the only
thing that can be changed here. Then "Label   value" rows (label lighter, value right-aligned,
long values wrap) in a `LightScrollView`: Series ("Name, book N"; the row is left out when
there's none), Chapters, Words, Reading time and Time left (at 250 words a minute: "About
6 h 20 min"; time left counts later chapters' words plus the unread share of the current
chapter, by characters; no saved place = the whole book), Progress (the library's "NN% read"),
File (name), File size, Added (the EPUB's modified time on the phone — the send script's `cp`
sets it to the day it was sent), Last read (the saved place's `updatedAt`, or "Not yet").
Dates use the phone's medium date format. Logic: `BookDetails.kt` (`bookDetailRows`).
Series: calibre `calibre:series` / `calibre:series_index`, then EPUB 3
`belongs-to-collection` + `group-position`, then the file name `Series NN. Title - Author.epub`.

Quiet, fast, readable. No streaks, no stats, no notifications.

## 10. Build order

One phase per session. Don't start the next until the current one builds, its tests pass,
and Jordan has confirmed it on the phone (phases 3–5).

0. **Setup (done)** — branch, placeholder screen, scripts, this file.
1. **EPUB engine** — `epub/` package ported from `convert.py`, pure Kotlin. Unit tests build
   small EPUBs in memory (never commit real books). Also a test that, when the env var
   `READER_TEST_EPUBS` points to a folder, parses every EPUB under it (recursively) and writes
   a report to `build/epub-report.txt`: one line per book (file, title, author, chapter
   count, or the error / DRM), then full chapter titles + char counts for each book. It is
   skipped when the var is unset. Run it on a few author folders first, then the whole
   library, and summarize the failures and anything suspicious for Jordan.
2. **Data layer** — `LibraryStore` (scan, parse-once cache, stale/orphan handling) and the
   Room position model. No UI.
3. **Library + Contents screens.**
4. **Reader screen** — pagination, tap zones, chapter crossing, saved position.
5. **Polish** — typography and spacing for sustained reading, checked on the phone.

## 11. Working agreement

- If an SDK component doesn't exist, say so and propose building it from primitives.
- If a build rule blocks an approach, name what it blocked and give two alternatives.
- Don't add features that aren't in this spec. Suggest, don't build.
- Never commit `.epub` files, converted text, or `local.properties`.
