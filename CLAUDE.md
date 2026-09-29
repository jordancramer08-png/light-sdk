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

**Removing books (and comics).** `scripts\Remove Books from Phone.cmd` (→ `remove-books.ps1`)
lists the `*.epub` files in `shared/books/` and every `*.cbz` and `*.txt` under
`shared/comics/` (§12) in a searchable, multi-select picker with columns Type (Book, Comic,
Note), Folder, Name; shows the chosen ones, asks "Remove these 3 books, 12 comics and 1
note? (Y/N)", deletes them with `run-as … rm`, removes comics folders left empty
(`rmdir`, deepest first), then lists the books left and counts the comics. It deletes only
those files: the app drops the book's `library/<slug>/` cache on its next scan (§6) and a
comic's `comic-library/<id>/` on the next launch (§12), and saved places, reading lists and
reading status (keyed by slug) are kept, so anything sent again opens where Jordan stopped
and is back in its lists.

## 6. Architecture

```
reader/
  epub/        pure Kotlin, NO Android imports — unit-testable on the PC
    EpubParser.kt       zip → Book(title, author, chapters[title, text, styles, parents])
    HtmlText.kt         XHTML → plain text (paragraphs split by one blank line) + StyleRanges
                        (italic, bold, quote, scene break) + note markers (NoteRef)
    Footnotes.kt        NoteFinder: a note marker's link → the note's text, in any file of the book
    ContentFilter.kt    drops covers, title pages, ads, embedded TOCs, praise pages
    Series.kt           series + number: OPF metadata (calibre, then EPUB 3), else file name
    Cover.kt            findCover: the cover image's bytes (no decoding here)
    WordCount.kt        countWords (runs of non-space holding a letter or digit)
  comics/      pure Kotlin, NO Android imports — unit-testable on the PC (§12)
    ComicNames.kt       cleanComicTitle, NaturalOrder / naturalCompare, comicSlug / comicPathOf
    ComicFolderListing.kt  ComicFolderItem, folderItems (folders first, then comics + notes),
                        folderSummaryText, parentPath
    ComicPages.kt       comicPages (pictures in natural order, no __MACOSX / hidden), readComicPages,
                        readComicEntry, decodeNoteText
    ComicView.kt        the viewer's arithmetic: pageTap (tap zones), fitScale, PageZoom, PageGeometry
                        (zoom at a spot, pan limits, double-tap, visibleRegion, regionSampleSize),
                        fittedSize, openingPageIndex, pagesToKeep, sliderPage / sliderFraction
    PanelDetector.kt    panel detection (§12 part 3): GrayImage, grayFromArgb, shrinkToLongSide,
                        gutterLevel, searchPanels / detectPanels (XY-cut), PanelTuning (thresholds)
    PanelReading.kt     Panels mode (§12 part 4): ComicReadingMode, panelStep (where a tap goes),
                        enteringPanel, openingPanelIndex / savedPanelNumber, panelZoom (a panel
                        filling the screen), between (the animated move between two zooms)
  data/
    LibraryStore.kt     scans shared/books/*.epub, caches parsed books (and their covers)
    ComicStore.kt       lists shared/comics/ folders, caches each comic's page list + small cover,
                        and each page's panels once looked for (panels)
    ComicMeta.kt        cached comic meta.json model (kotlinx-serialization)
    ComicPanels.kt      cached panels.json model: ComicPanels, PagePanels, PanelBox
    ComicPageImages.kt  a page's bytes from the CBZ → a screen-fitted Bitmap (inSampleSize, then
                        scaled), BitmapRegionDecoder pieces for sharp zoom, and findPanels (a small
                        gray copy → detectPanels) (phone only)
    CoverImages.kt      CoverSize, CoverImages.save: cover bytes → cover-small.png + cover-large.png
                        (or only the sizes asked for: saveSmall for comics)
                        (BitmapFactory with inSampleSize; phone only), sampleSize (unit-tested)
    BookMeta.kt         cached meta.json model (kotlinx-serialization)
    ReaderDatabase.kt   the one Room database (version 4) + readerDatabase() shared instance,
                        and MigrationToVersion3 (the 2 -> 3 backfill)
    ComicPosition*.kt   Room entity / dao / repository for saved places in comics
    LibrarySectionPreference.kt  remembered Library section, Books or Comics (DataStore)
    ComicReadingModePreference.kt  remembered comic reading mode, Full page or Panels (DataStore)
    ReadingPosition*.kt Room entity / dao / repository for saved places
    ReadingList*.kt     Room entities / dao / repository for reading lists
    BookStatus*.kt, ReadingStatusRepository.kt  Room entity / dao / repository for reading status
    DatabaseQueue.kt    runs list and status reads + writes one at a time, in order
    LibrarySortPreference.kt  remembered library sort (DataStore)
    LibraryFilterPreference.kt  remembered library Show filter (DataStore)
    LibraryGroupSeriesPreference.kt  remembered Group series On/Off (DataStore)
    LibraryShowCoversPreference.kt   remembered Show covers On/Off (DataStore)
    ReaderSettingsPreference.kt  remembered size, typeface, line spacing, margins, alignment,
                                 progress line On/Off (DataStore)
    ReadingSpeedPreference.kt    remembered reading speed, characters a minute (DataStore)
    ReaderThemePreference.kt     remembered reading theme (DataStore)
  ReaderTextSize.kt     the five text sizes (pure Kotlin, unit-tested)
  ReaderSettings.kt     ReaderSettings (size + typeface + line spacing + margins + alignment + progress
                        line) and the ReaderTypeface / ReaderLineSpacing / ReaderMargins /
                        ReaderAlignment choices (pure Kotlin, unit-tested)
  ReadingProgress.kt    the progress line: ReadingProgress, reading-speed estimate (pageSpeed,
                        updatedSpeed), time left and its wording (pure Kotlin, unit-tested)
  ReaderTypography.kt   reader body + heading TextStyles for a ReaderSettings (shared with ReadingSettingsScreen),
                        styledChapterText (chapter text + StyleRanges → AnnotatedString), withNoteColor
  NoteMarkers.kt        PageNote, pageNotes, nearestNote: which note marker a tap hit (unit-tested)
  ReaderTheme.kt        the four themes: LightColors + accent each
  ReaderThemeController.kt  the app-wide current theme; ThemedScreen frame every screen uses
  ReadingLists.kt       list logic: LibraryView, listRows, neighbourSlug (pure Kotlin, unit-tested)
  ReadingStatus.kt      ReadingStatus, LibraryFilter, statusAfterOpening (pure Kotlin, unit-tested)
  SeriesGroups.kt       LibraryEntry (a book or a series row), libraryEntries (pure Kotlin, unit-tested)
  BookDetails.kt        the Book Details rows: reading time, time left, sizes, dates (pure Kotlin, unit-tested)
  ChapterTree.kt        contentsRows (Contents with headings), readerBarTitle (pure Kotlin, unit-tested)
  LibraryScreen.kt, SortFilterScreen.kt, ContentsScreen.kt, ReaderScreen.kt, ReadingSettingsScreen.kt,
  ThemeScreen.kt, ListsScreen.kt, ListNameScreen.kt, DeleteListScreen.kt, ListBooksScreen.kt,
  AddToListScreen.kt, BookDetailsScreen.kt, SeriesScreen.kt, NoteScreen.kt, Divider.kt, RowIconButton.kt
  LibraryEntryList.kt   the book / series rows list (covers, Continue reading row), shared by
                        LibraryScreen and SeriesScreen
  BookCover.kt          CoverCache (covers read off the main thread, kept in memory) and BookCover
                        (a row's cover, or the placeholder block with the title's first letter);
                        CachedCover (any cached cover by key), IconPlaceholder (folder / note blocks)
  UniformRow.kt         a fixed-height row (divider included) for LightLazyScrollView lists
  ComicRows.kt          LibrarySection, ComicEntry (folder / comic / note row), comicEntries,
                        comicPagesText, comicProgressText, lastOpenedComicPath, comicDetailRows
                        (pure Kotlin, unit-tested)
  ComicFolderLoader.kt  loads a comics folder (or a list's comics) and reads new comics one by one
  ComicEntryList.kt     the comics rows list (covers, Continue reading row)
  ComicViewModel.kt     the viewer: which page and panel, pictures kept, panels looked for, zoom,
                        sharp piece, saving the place
  ComicFolderScreen.kt, ComicScreen.kt (the page viewer), ComicNoteScreen.kt, ComicDetailsScreen.kt
tool/schemas/           Room's saved schema for each database version (checked in)
```

**Parse once, then cache.** The first time a book is seen (or its file size / modified time
changes, or `PARSER_VERSION` is bumped), parse it and write
`<filesDir>/library/<slug>/meta.json` + `001.txt`, `002.txt`, … Every later open reads the
cache. meta.json also holds the series (`series`, `seriesNumber`, when there is one) and each
chapter's word count (`words`), counted once at this step (`PARSER_VERSION` 2), and each
chapter's place in the contents: `depth` and `parents` (the titles of the NCX / nav entries
above it, outermost first) (`PARSER_VERSION` 3). Delete cache folders whose EPUB is gone.
Parsing runs on `Dispatchers.IO`, one book at a time; the library shows "Preparing N books…"
meanwhile.

**Covers (`PARSER_VERSION` 5).** While a book is prepared, `findCover` (epub/, pure Kotlin)
returns the cover's bytes, trying in order: the OPF `<meta name="cover" content="id">` item (or
a path there instead of an id), an EPUB 3 item with `properties="cover-image"`, an image item
whose id or file name holds "cover", then the first `<img src>` / SVG `<image xlink:href>` in
the first spine file (even `linear="no"`). A candidate whose file is missing, empty or over
20 MB is skipped. `LibraryStore` hands the bytes to `CoverImages.save` (data/, Android), which
decodes them with `BitmapFactory` at a power-of-2 `inSampleSize` that keeps them at least
480 × 720, and writes `cover-small.png` (fills 150 × 225, for Library rows) and
`cover-large.png` (fits 480 × 720, for Book Details) into `library/<slug>/`, never scaled up.
A cover Android can't read (e.g. SVG) or a failed save leaves no cover files and the book
readable. `saveCover` is a `LibraryStore` parameter so its PC tests use a stand-in. meta.json
doesn't change: a missing `cover-small.png` means no cover. (Checked on the PC library: every
readable book names its cover by `meta name="cover"` or `cover-image`.)

**Formatting (`PARSER_VERSION` 3).** `NNN.txt` stays plain text, and saved places are still
character offsets into it (a scene break adds a "⁂" paragraph, so a place saved before
version 3 can land a page early in a book that has them). A chapter with any formatting also gets
`NNN.styles.json`: a list of `{style, start, end}` ranges by character offset in `NNN.txt`
(`StyleRange`; no file = no formatting). Kept: italic (`i`, `em`, `cite`), bold (`b`,
`strong`), block quotes (`blockquote`, one QUOTE range over its paragraphs, drawn indented
1.5 em on every line) and scene breaks (`hr`, and paragraphs that are only asterisks or
ornaments: "* * *", "***", "⁂", "#", "~" …). A scene break becomes its own "⁂" paragraph,
drawn centered; repeated ones merge, and none opens or ends a chapter. Italic or bold set by
CSS classes (`<span class="italic">`) is not detected.

**Notes (`PARSER_VERSION` 4).** A note marker is an `<a>` with `epub:type="noteref"` or
`role="doc-noteref"`, or a link of at most 6 characters (`MAX_MARKER_CHARS`) inside `<sup>` or
`<small>` (or holding one) that points at an id (`#id`, `file.xhtml#id`; not `doc-backlink`). Its
text is kept in `NNN.txt` as the number alone ("[12]" → "12"), and `NNN.styles.json` gets a
`NOTE` range over it whose `note` field is the note's plain text (paragraphs split by a blank
line). The note is the element with that id in any file of the zip — also an endnotes file
dropped as non-content, or one outside the spine (`NoteFinder`, each file read once). When the
id is on an inline element (the backlink number, an empty anchor), the note is its enclosing
paragraph / block; the backlink number and the punctuation after it are dropped; a block that
held only the number (`<dt>12</dt><dd>…</dd>`) takes the next block. A note over 10,000 chars
(`MAX_NOTE_CHARS`) or empty is skipped. A `<sup>`/`<small>` marker must point forward (later
file, or later in the same file); pointing back means it's a note's link back to the text.
A marker whose note isn't found stays plain text. Footnotes printed beside the text — an
`<aside>` typed as a footnote / endnote / note, or any element typed `footnote(s)` /
`doc-footnote` — are left out of the chapter; endnote lists (e.g. `<section
epub:type="endnotes">` in a Notes chapter) are kept. Not detected: markers raised only by a
CSS class (`<span class="sup">`), and notes keyed to page numbers with no link from the text.

**Nested contents.** The NCX `navPoint`s (or nav `<ol>`s) are read as a tree; the first
entry pointing at a file gives it its title and its `parents`. A heading page too short to be
a chapter ("Book One: Pale") is dropped as before, but still shows in Contents, rebuilt from
the chapters' `parents` (`contentsRows`). A file the contents doesn't list sits under the
entry before it if that entry has children, else beside it; if that entry was a dropped leaf
divider page (e.g. a one-word "Prologue" page, and not a front-matter title) and this is the
very next file, it takes the divider's title instead of "Chapter N". Chapter anchors inside
one file (`ch.html#ch05`) don't split it: one spine file is one chapter.

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
  starts as `READING`. Books are keyed by slug, like saved places. 4 = adds
  `comic_position(slug PK, page, panel, updatedAt)` (§12); the 3 -> 4 step only creates it.
  Comics use `reading_status` and `reading_list_book` too, keyed "comic:<path>". **Never use a destructive
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
  the chapter heading. With formatting (§6) the chapter is one `AnnotatedString`
  (`styledChapterText`, its ranges shifted for the extra paragraph spacing). The whole
  chapter is laid out once with it (`layOutChapter`, a `TextLayoutResult`), pages are cut
  from that layout's lines, and **each page is drawn from that same layout** (`drawText`,
  moved up to the page's first line and clipped below its last), never re-laid-out as a
  piece of text. That is what keeps measured = drawn with justified text: whole-paragraph
  line breaking and hyphens would wrap a page-sized piece differently, and a page ending
  mid-paragraph would lose the justification of its last line. The last 3 chapters' layouts
  are kept; a theme change lays the current one out again in the new colors, same pages.

## 8. Library order and display

Jordan's PC library is `D:\Reading\Digital Books`: ~660 EPUBs in author folders, named
like `Cemetery of Forgotten Books 01. The Shadow of the Wind - Carlos Ruiz Zafón.epub`
(`Series NN. Title - Author.epub`, or `Title - Author.epub` for standalones). He sends a
handful at a time, not the whole library. On the phone the files sit flat in
`shared/books/`, with names flattened to plain ASCII by the send script.

- **Six sort orders and a Show filter**, chosen from a sort icon (`LightIcons.REVERSE_ORDER`
  — the SDK has no dedicated sort icon) in the right slot of the Library top bar. It opens
  `SortFilterScreen`, titled "Sort & Filter": "Sort by" Author A–Z, Author Z–A, Title A–Z,
  Title Z–A, Recently read, Recently added, then "Show" All, Want to Read, Reading, Finished
  (reading status, §9), then "Group series" On, Off, then "Show covers" On, Off. The current
  choice in each section is marked with a filled circle (`SELECT_ON`, others `SELECT_OFF`).
  Tapping any row returns all four choices (`SortAndFilter`) and redraws the list; back
  leaves them unchanged. The filter and grouping apply to all books only, not to a list;
  Show covers applies to every Library list and to SeriesScreen.
- **Recently read**: last opened first — by the saved place's `updatedAt`, which the reader
  writes when a book opens, on every turn and on leaving it — then books never opened, by
  title (as Title A–Z). **Recently added**: newest EPUB first, by its modified time on the
  phone (the day the send script copied it; Book Details' "Added" date).
- All comparisons are case- and accent-insensitive ("le Carré" and "Le Carre" group
  together). Author comes from the EPUB metadata; title from the EPUB metadata with a
  leading "The", "A" or "An" ignored ("The Shadow of the Wind" sorts under S).
- Ties always fall back to **file name, A to Z** — even in Author Z–A — so an author's
  series stays in order (01, 02, …).
- **Group series** (default On): books of one series (`series` in meta.json, §6; names
  matched case- and accent-insensitively) show as one row — series name, the first book's
  author, "N books · M finished" (lighter) — which opens SeriesScreen. The filter runs
  first, so a series row counts only the books the filter shows, and a series with only one
  book shown is a normal book row. A series row sorts by its name (title orders), its
  first book's author, or its most recent book (Recently read: the latest `updatedAt` of its
  books, a never-opened series by name; Recently added: its newest EPUB), ties by its first
  book's file name. Off: every book is its own row. One comparator orders book and series
  rows alike (`libraryOrder`).
- All four are remembered in `lightContext.dataStore`: key `library_sort` (the enum name;
  missing or unknown = Author A–Z), key `library_filter` (the `LibraryFilter` name;
  missing or unknown = All), key `library_group_series` (a boolean; missing = On) and key
  `library_show_covers` (a boolean; missing = On).
  Logic: `LibraryList.kt` (`LibrarySort`, `libraryRows`, `libraryOrder`,
  `continueReadingRow`, `coverLetter`), `ReadingStatus.kt` (`LibraryFilter`) and
  `SeriesGroups.kt` (`libraryEntries`); storage: `data/LibrarySortPreference.kt`,
  `data/LibraryFilterPreference.kt`, `data/LibraryGroupSeriesPreference.kt`,
  `data/LibraryShowCoversPreference.kt`.
- Row: title (from the EPUB metadata, one line), author (lighter), progress ("Not started" or
  "NN% read", computed from chapter char counts as in the September `LibraryScreen`; a
  Finished book shows "Finished" instead, in the accent like "NN% read"). With Show covers
  On, the book's small cover sits at the left (3.6 × 5.4 grid units, cropped to fill; a
  series row shows its first book's cover). A book with no cover gets a plain block tinted
  with the text color, holding the title's first letter or digit (lighter); the block is
  blank while the picture loads. Covers are read on `Dispatchers.IO` and kept in
  `CoverCache` (in memory, oldest dropped past 16 MB, keyed by slug + size + the book's
  source stamp, so a re-prepared book never shows an old picture).
- **Continue reading** (all books only, never in a list): the first row, tinted with the
  accent (12%) and labelled "Continue reading" in the accent, then the title and its progress
  (lighter), with its cover when covers are on. It is the book with the latest saved place
  among the books on the phone that can open (`continueReadingRow`); tapping it opens the
  book at that place. Hidden when no book has been opened, or when the Show filter hides
  that book (the next most recent isn't used instead). The book keeps its normal row too.
- Empty library: "No books on this device yet." centered, lighter text. When a Show filter
  leaves nothing: "No books marked Finished." (the filter's name).
- The list is a `LightLazyScrollView` (only rows on screen are drawn). That view needs every
  row the same height, so each row (the Continue reading row too) is a `UniformRow` of 7
  grid units: title, author and progress (or series name, author, count) one line each,
  ellipsized, beside the cover.

**Long lists and huge books.** Some books have hundreds of chapters (C.S. Lewis Complete
Works: 746). Any list that can grow long uses `LightLazyScrollView` + `UniformRow` with
one-line, ellipsized text — never a plain `LightScrollView` with a row per item. Nothing
slow (parsing, reading chapter files, paging) runs on the main thread; if it takes longer
than a blink the screen says "Preparing…" instead of freezing or showing a stale page.

## 9. Screens

**LibraryScreen** (`@InitialScreen`) — two sections, BOOKS and COMICS, switched by a bar at
the bottom (two text buttons; the one showing is underlined; the last one is remembered in
`lightContext.dataStore`, key `library_section`, missing/unknown = Books). Switching goes back
to all books / all comics. The Comics section is described in §12; the rest of this entry is
the Books section. Top bar: the lists icon (`LightIcons.LARGE_LIST`) on
the left (→ ListsScreen), "Books" or the shown list's name, and on the right the sort icon
(→ Sort & Filter, §8) for all books, or a pencil (→ ListBooksScreen) when a list is shown. Tap a
book → ReaderScreen. All books opens with the Continue reading row (§8) when there is one. A list shows only its books, in the list's own order (sorting doesn't
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
that opens Contents. When the chapter sits under a heading, the title reads
"<top-level heading> · <chapter title>" ("BOOK 1: GARDENS OF THE MOON · Chapter One";
`readerBarTitle`, one line, ellipsized by the SDK). The heading on a chapter's first page is
always just the chapter title. Paged text below (§7), with italic, bold, indented block
quotes, centered "⁂" scene breaks and note markers (§6): small (0.7 em), raised, in the
theme's accent (added by `withNoteColor` when the chapter is laid out, not measured, so a
theme change never re-pages). A tap on or within 1.5 grid units of a marker
(`NOTE_REACH_GRID_UNITS`; measured on the chapter layout's character boxes, shifted to the
page, nearest marker wins) opens NoteScreen instead of turning
the page, even in the left 30%; every other tap turns pages as before. Opening a book makes a Want to Read book
Reading (Reading and Finished stay as they are). A page turn or Contents jump that lands on
the last page of the last chapter makes it Finished; reopening the book on that page, or
re-paging, does not (so a hand-set status isn't undone just by opening the book).
**Progress line** (Reading Settings, default On): one line under the page, Detail size,
lighter: "Ch 5 of 63 · 34% · 12 min left in chapter" (chapter's place in the list, the
library's percent, `percentRead`). Tapping it switches the time to "3 hr 20 min left in
book" and back (not remembered: each book opens on the chapter). It sits below the page
area, so its height is taken out of every page; while a page loads it stays, blank, so the
page's height never changes. Time left = characters from the page's first character to the
chapter's (or book's) end ÷ reading speed, rounded, at least 1 min. Reading speed: each
forward turn times the page just left (characters ÷ minutes it was up); turns under 2 s or
over 5 min are ignored, and the timer stops while the screen is hidden or the app paused.
Each timed page moves a rolling average a tenth of the way (`updatedSpeed`), kept in
`lightContext.dataStore` (key `reading_chars_per_minute`, a float; missing = 1,400).
If a chapter takes more than 300 ms to read and page (a huge chapter, or the library still
busy), the page area shows "Preparing…" (lighter, centered) and taps are ignored until the
page is ready. Tapping the chapter title opens ReadingSettingsScreen.

**ReadingSettingsScreen** — top bar: back, "Reading Settings". Opened by tapping the
chapter title in the reader. Stepper rows, each "Label   −  <value>  +" (drawn as text:
the SDK has no minus icon; Typeface uses "‹ ›" since fonts aren't a quantity), then a
"Theme  <name>" row that opens ThemeScreen, then a sample paragraph drawn with exactly the
reader's body style and margins, so every change shows live. Between them, after Margins:
an "Alignment" stepper (‹ ›) and a "Progress line  On/Off" row (the word plus the
`TOGGLE_STATE_ON/OFF` icon; tap the row to switch). At either end of a range the
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
- **Alignment** (`ReaderAlignment`): **Left (default)** — `TextAlign.Start`,
  `LineBreak.Simple`, `Hyphens.None`, the page the reader always had — or Justified —
  `TextAlign.Justify`, `LineBreak.Paragraph`, `Hyphens.Auto`. Only the body text: headings
  keep their own style, scene breaks stay centered, block quotes keep their indent.
- **Progress line**: On (default) / Off (above, under ReaderScreen).

Back returns all six as one `ReaderSettings` via `goBack(settings)`; ReaderScreen saves
them and re-paginates. One set for all books, remembered in `lightContext.dataStore` by
`ReaderSettingsPreference` (keys `reader_text_size`, `reader_typeface`,
`reader_line_spacing`, `reader_margins`, `reader_alignment`, each the enum name, and
`reader_progress_line`, a boolean; missing/unknown = that setting's default). Any change of
style, page width or page height (the progress line on or off) re-paginates anchored at the reader's
place — the first character of the page last turned or jumped to (`anchorOffset`), which
re-paging does not move — so switching back and forth never drifts off the passage.

**NoteScreen** — top bar: back, "Note 12" (the marker). The note's text drawn with exactly the
reader's body style and margins (paragraphs spaced as on a page), in a `LightScrollView` so a
long note scrolls. Back returns to the same page.

**ThemeScreen** — top bar: back, "Theme". Four rows, each drawn in its own colors as a
preview, the current one marked `SELECT_ON`: **Dark** (default, the SDK dark look), Light,
Sepia, Night (dark gray, warm text). Each is a `LightColors` plus an accent color
(`ReaderTheme`); the accent colors the chapter heading on a chapter's first page and the
"NN% read" text in the library. Tapping a row applies it to every screen at once
(`ReaderThemeController`), remembers it in `lightContext.dataStore` (key `reader_theme`,
the enum name; missing/unknown = Dark), and goes back. A theme change never re-pages the
book. Every screen draws inside `ThemedScreen { }`.

**ContentsScreen** — the book's chapters, with their headings (`contentsRows`, §6); tapping
one returns its index to ReaderScreen via `goBack(index)`, which jumps to that chapter's
start in place. Tapping a heading jumps to the first readable chapter under it. Headings (and
chapters with others nested under them) are drawn in the theme's accent; every row is
indented 1.25 grid units per level (up to 4 levels), so the nesting shows by position too.
A `LightLazyScrollView` of 4-grid-unit `UniformRow`s (titles one line, ellipsized); it opens
with the chapter being read as the top row. Bottom bar: "ADD TO LIST" (→ AddToListScreen), "DETAILS"
(→ BookDetailsScreen).

**Reading status** — each book is Want to Read (default, no row saved), Reading or Finished
(`ReadingStatus`, stored in the `reading_status` table, §7). Set automatically by the
reader (above) or by hand on Book Details. Status reads and writes go through
`DatabaseQueue`, so a change made just before leaving a screen is always saved.

**BookDetailsScreen** — top bar: back, "Details". The large cover (`cover-large.png`, 14
grid units tall, fitted and centered; read before the page shows, so nothing jumps; left
out when the book has none, whatever Show covers says), then the title (Subheading) and
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
- Never commit `.epub`, `.cbz` or `.cbr` files, converted text, or `local.properties`.

## 12. Comics (CBZ)

Built in parts: 1 = PC send script, phone storage, the Comics section that lists comics
(done); 2 = the full-page viewer (done); 3 = the panel detection engine (done, below);
4 = panel-by-panel reading in the viewer (done). Part 5 comes next.

**PC side.** `scripts\Send Comics to Phone.cmd` (→ `send-comics.ps1`). Jordan's comics are in
`D:\Comics` (~7,700 files, reading-order folders up to 5 deep, e.g. `DC Comics\01. Book I -
Earth-One (1938-1985)\00001. Action Comics #1 (1938).cbz`). Double-click: an Out-GridView
picker of every `.cbz` / `.cbr` (columns Folder, Name, Size MB; filter on a folder name, then
Ctrl+A picks it all). Drop files or folders (subfolders included) to send those; anything
under `D:\Comics` keeps its path from there, a folder from elsewhere keeps its own name, a
loose file from elsewhere goes at the top. Every `.txt` note in the same folders as the
picked comics goes too. Then:
- "Shrink pages to phone size? (Y/N)" (Enter = yes). Yes: each page whose longer side is over
  2000 px is resized (System.Drawing, high-quality bicubic) and saved as JPEG quality 85;
  smaller pages, and pictures System.Drawing can't read (WebP), stay as they are.
- `.cbr` is repacked to `.cbz` with its pictures unchanged: 7-Zip (`C:\Program
  Files\7-Zip\7z.exe` or `7z` on PATH), else Windows `tar.exe`; if neither opens it, that
  comic is skipped and the reason printed. A `.cbz` that needs shrinking is unpacked with .NET
  (7-Zip / tar if that fails). Repacked CBZs store entries uncompressed.
- All work is on copies in `%TEMP%\reader-comics-*`, deleted at the end. **Nothing in
  `D:\Comics` is ever changed.**
- Before sending: "Picked N comic(s) and M note(s): X MB. After shrinking: Y MB." then
  "Send these? (Y/N)". At the end, the before / after sizes again.
- Phone path: `files/shared/comics/<relative folders>/<name>.cbz`, every folder and file name
  flattened to plain ASCII as the book script does (same `adb push` + `run-as cp` approach).

**Phone storage.** `ComicStore` reads `File(filesDir, "shared/comics")`. A comic's path is
relative to it with "/" between parts, and its key everywhere (status, lists, saved place) is
`"comic:" + path` (`comicSlug`), which can never clash with a book slug. Each comic is read
once — its page list and its first page as a small cover (`CoverImages.saveSmall`, 150 × 225,
cropped to fill) — into `<filesDir>/comic-library/<id>/` (`meta.json` = `ComicMeta`: path,
size + modified time + `COMIC_CACHE_VERSION`, pages; `cover-small.png`). `<id>` = the first 16
hex digits of the path's SHA-1. It's read again when the size or modified time changes or
`COMIC_CACHE_VERSION` is bumped. A CBZ that isn't a readable zip, or has no pictures, is cached
as a problem ("Can't open", not tappable). Cache folders whose comic is gone are deleted once
per launch (`removeOrphans`). Preparing and the orphan sweep share one lock across every
`ComicStore`. Pages (`comicPages`): entries ending .jpg .jpeg .png .gif .webp .bmp, not in
`__MACOSX`, not hidden (`._x.jpg`, `.DS_Store`), in natural order ("2.jpg" before "10.jpg",
folders included). Zips whose names aren't UTF-8 are opened as code page 437.

**Comics section** (the Library's COMICS). A folder browser mirroring `shared/comics/`:
subfolders first, then comics and note files mixed, all by file name in natural order, so
numbered names keep reading order (`folderItems`; other files, hidden files and `__MACOSX`
are left out). Rows are the Library's 7-unit `UniformRow`s in a `LightLazyScrollView`:
- Folder: a tinted block with an arrow, the cleaned name, "3 folders · 12 comics" (lighter)
  → ComicFolderScreen (back, the folder's name, the same list, no bottom bar).
- Comic: its cover, the cleaned title, "30 pages" (lighter), then "Not started" (lighter),
  "Page 12 of 30" or "Finished" (accent). A comic not read yet shows a blank cover and
  "Preparing…" at once; comics are read one by one in the order shown and each row fills in
  (nothing else waits). → ComicScreen.
- Note: a tinted block with a pencil, the cleaned title, "Note" → ComicNoteScreen (back, the
  title, the text in Paragraph size in a `LightScrollView`; UTF-8, else Windows-1252).
- Title cleaning (`cleanComicTitle`): drop `.cbz` / `.cbr` / `.zip` / `.txt` and a leading
  reading-order number with its separator ("00001. ", "00102a. ", "01 - ", "7) ", "12_");
  a number with no separator ("2000 AD", "1984") stays; so does a name that is only a number.
- The top of the section starts with the Continue reading row (accent tint, "Continue
  reading", title, progress): the comic with the latest `comic_position.updatedAt` still on
  the phone (`lastOpenedComicPath`). Empty: "No comics on this device yet."; an empty
  subfolder: "This folder is empty."
- Top bar: lists icon (ListsScreen, first row "All comics"), "Comics" or the list's name,
  and a pencil when a list is shown (→ ListBooksScreen, each comic with its folder). A list
  in the Comics section shows only its comics, in list order; in the Books section only its
  books. No Sort & Filter for comics.
- Reloaded every time it comes to the front; reading comics in the background stops while
  it's hidden.

**ComicScreen** — the viewer. Black background, no bars. Two reading modes, **Panels
(default)** and **Full page** (`ComicReadingMode`), switched by the FULL PAGE / PANELS bar in
the overlay (the one in use underlined, as the Library's section bar) or by a long press
anywhere on the page; remembered for every comic in `lightContext.dataStore` (key
`comic_reading_mode`, the enum name; missing/unknown = Panels). Taps (`pageTap`): the top
15% shows the overlay; below it the left 30% goes back and the rest goes on (as in the book
reader; nothing past either end). A single tap waits a moment to be sure it isn't a double-tap.
- **Full page mode**: the page fitted whole to the screen (`fitScale`, centered); taps turn pages.
- **Panels mode**: the page zoomed so the current panel fills the screen less a margin of 3%
  of its shorter side (`panelZoom`, `PANEL_MARGIN_FRACTION`), centered even at the page's edge
  (the page may sit past its edge here, unlike pinching), at most 4×. A tap on the right moves
  to the next panel in reading order, on the left to the previous one, sliding there over
  250 ms (`PANEL_MOVE_MS`; `between` moves the part on screen evenly; drawn in ComicScreen with
  an `Animatable`, from wherever the page was last drawn, so a tap mid-move carries on smoothly).
  After the last panel the page zooms out whole, stays 0.7 s (`WHOLE_PAGE_PAUSE_MS`), then the
  next page opens on its first panel (a tap during the pause goes on at once; a left tap
  returns to the last panel). On the comic's last page it stays whole until the next tap.
  Going back from a page's first panel opens the previous page on its last panel; the slider
  opens a page on its first. A page with no panels (or one that can't be read) is shown
  whole, and the next tap turns the page. Double-tap shows the whole page until the next tap,
  which returns to the panel. Pinch and drag do nothing in Panels mode. Switching to Full
  page zooms out to the whole page; switching to Panels goes to the page's first panel.
- **Panels off the main thread**: `ComicStore.panels` (panels.json, else detected) runs on its
  own one-at-a-time dispatcher, beside the picture decoding: the page shown first, then the
  next page in the reading direction, then the one before, so turning either way doesn't wait.
  Page changes cancel what's queued. Only in Panels mode. If the page shown is still waiting
  for its panels, it shows whole, taps on it are ignored, and it slides into its panel as soon
  as they're known.
- **Zoom** (Full page mode): pinch zooms around the fingers, 1× (fitted) to 4× (`MAX_ZOOM`); double-tap on a
  fitted page zooms 2.5× (`DOUBLE_TAP_ZOOM`) at that spot, double-tap again fits it. A zoomed
  page is dragged to move it, only until its edge meets the screen's (`PageGeometry.clamp`;
  a side narrower than the screen stays centered). While zoomed, taps don't turn pages (the
  top 15% still shows the overlay); only double-tap, pinch and drag work. Turning a page
  always shows the next one fitted. The sharp piece (below) works in Panels mode too.
- **Overlay** (top 15% tap; any tap on the page hides it): at the top, the theme's colors,
  back, the title, "Page 12 of 30", a slider (`LightTouchableProgressBar`, not drawn for a
  one-page comic) that jumps to pages as it's dragged, and the FULL PAGE / PANELS bar; at the bottom, ADD TO LIST
  (→ AddToListScreen) and DETAILS (→ ComicDetailsScreen: title, Status choices as Book
  Details, then Pages, Progress, Folder, File, File size, Added, Last read).
- **Memory**: a page's bytes are read from the CBZ (`ComicPageImages`) and decoded with a
  power-of-2 `inSampleSize` that keeps it at least the fitted size, then scaled to exactly
  that. Kept: the page shown and the next one in the reading direction (`pagesToKeep`,
  read ahead), nothing else. While zoomed, once the fingers rest 150 ms, the part on screen
  is decoded by `BitmapRegionDecoder` at the detail the zoom needs (`visibleRegion`,
  `regionSampleSize`) and drawn over the enlarged fitted picture; one piece at a time, one
  page open for pieces at a time. Decoding runs one picture at a time off the main thread; a
  picture no longer wanted is skipped or thrown away. GIF / BMP pages zoom without the sharp
  piece. A page slower than 300 ms shows "Preparing…"; one that can't be read shows
  "Can't show this page." (gray on black, any theme).
- **Place and status**: it opens on the saved page (`openingPageIndex`, page 1 the first
  time), and in Panels mode on the saved panel (`openingPanelIndex`; 0 or none = the first).
  Opening counts as opening the comic: Want to Read → Reading, and its
  `comic_position` is stamped now. Every page turn, panel move or slider jump saves the page
  and panel (0 in Full page mode or for a page shown whole; through
  `DatabaseQueue`); leaving the screen or pausing the app saves it and waits
  (`DatabaseQueue.writeNow`). Reaching the last page by a turn or the slider makes it
  Finished; reopening it there does not (as books).

**Panel detection** (part 3; used by the viewer's Panels mode, above). `comics/PanelDetector.kt`,
plain Kotlin. A page is decoded small (`ComicPageImages.findPanels`: `inSampleSize` keeping at
least 800 px on the long side), made gray (`grayFromArgb`: 0.299 R + 0.587 G + 0.114 B) and
shrunk by averaging to 800 px on its long side (`shrinkToLongSide`, `PANEL_GRID_LONG_SIDE`).
- **Gutter color** (`gutterLevel`): the outer 2% of the page all round. Pixels ≥ 160 are
  light, ≤ 80 dark; whichever group is bigger, if it fills at least half the border
  (`minBorderShare`), gives the gutter gray (its median). Otherwise (panels running off the
  page) white is tried, then black, and the first reliable split is kept (`searchPanels`).
- **Gutter pixel**: within 64 (`gutterTolerance`) of the gutter gray toward the ink (a light
  gutter takes anything lighter too). 64, not 48, because old scans have white page edges but
  yellowed gutters (~185).
- **XY-cut**: trim gutter rows / columns off the edges, then cut into rows at every run of
  gutter rows (a row is gutter when ≥ 95% of its pixels are, `gutterShare`: skewed scans clip a
  panel corner) at least 2 px thick (0.25% of 800, `minGutterShare`: old scans' gutters are
  that thin); if no rows, into columns; each piece again (8 levels at most). Rows top to
  bottom, left to right inside a row = Western reading order.
- **Slivers**: a piece thinner than 6% of the page across the cut (`minPanelShare`) is dropped
  when under 2% of it is ink (`speckInkShare`: page numbers, specks), else joined to the
  neighbour across the narrower gutter (caption strips).
- **No panels** (the viewer will show the page whole) unless: 2–16 pieces (`maxPanels`),
  none under 1% of the page (`minPanelArea`), none over 70% (`maxPanelArea`: a splash with a
  strip), together covering ≥ 60% (`minCoverage`).
- Panels come back in the page's own pixels (`PixelRect`, rounded outward).
- **Cache**: `ComicStore.panels(meta, page)` reads `comic-library/<id>/panels.json`
  (`ComicPanels`: `version` = `PANEL_DETECTOR_VERSION`, `pages` keyed by the page's name in the
  CBZ → `PagePanels(pageWidth, pageHeight, panels)`, an empty list = whole page), else looks
  and adds the page (written to a temp file, then moved over). A page that can't be read isn't
  cached. Bump `PANEL_DETECTOR_VERSION` when the detector changes; the file goes with the
  comic's folder when the comic changes.
- **Known misses**: panels whose rows overlap by a few pixels or aren't straight (no clean
  gutter across), slanted panels, and side-by-side panels with no full-height gutter (kept as
  one box). Rarely, caption boxes on a splash or bright art are taken for panels.
- **Report test** `PanelReportTest` (skipped unless `READER_TEST_COMICS` is set;
  `READER_TEST_COMICS_LIMIT` = first N comics; `READER_TEST_PANELS` = other thresholds, e.g.
  `gutterShare=0.97,gutterTolerance=48`): 10 pages spread through each CBZ / CBR under the
  folder, overlays with numbered boxes (a fallback page gets red bands, a "0" and the cut's
  pieces in thin gray) in `tool/build/panel-report/<folder>/` with `index.html` and
  `summary.txt` (pages, panels, fallbacks and why). The Android test classpath has no
  `javax.imageio`, so pages are decoded by the PC helper `scripts/panel-report/PagePictures.java`
  (run with the JDK's `java`; outside `tool/src`, where `.java` files are banned); CBRs are
  unpacked with 7-Zip. Run one Gradle command at a time (8 GB laptop). Last run (defaults
  above): Earth-One, first 15 comics, panels on 102 of 150 pages; Rebirth, first 10, 44 of
  100. Most fallbacks there are covers, splashes and slanted layouts.

**`comic_position`** (database version 4): `slug` (PK, "comic:<path>"), `page` (1-based),
`panel` (0 = whole page, else the panel, 1-based, in Panels mode), `updatedAt`. Reads and writes go
through `DatabaseQueue`.
