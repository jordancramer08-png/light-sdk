# CLAUDE.md — Bible Reader for Light Phone III

Read this at the start of every session. If anything here conflicts with what you remember
about Android development, **this file wins** — this is not a standard Android environment.

---

## 1. Platform constraints (non-negotiable)

- **Target:** Light Phone III on LightOS. **1080 × 1240, 3.92", black and white.** API 34.
  **No Google Play Services.**
- **Monochrome screen — color never carries meaning.**
- **All app code in the `tool` module**, using primitives from `sdk/client`.
- **Kotlin, Compose, Coroutines, MVVM.** Screens extend `LightScreen`; view models extend
  **`LightViewModel`** (there is no `LightScreenViewModel` — that name is wrong).
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
- **`Context` is import-blocked.** No `getExternalFilesDir`. Use `lightContext.fileShare`
  for anything pushed onto the device.
- **Offline only.** No network, no sync, no accounts. `permissions` stays empty.

## 2. Package identity ⚠️

Three tools are already on this phone: `com.thelightphone.app` (prayer list),
`com.thelightphone.smallgroup`, and `com.thelightphone.reader`. **This tool's `id` must
differ from all three**, or installing it overwrites one and destroys its data. Suggested:
`com.thelightphone.bible`.

`serverPackage` is `com.lightos` for the physical LP3.

## 3. Build commands

```bash
./gradlew :tool:assembleDebug
```

Requires `gpr.user` / `gpr.key` in `local.properties`. **Never read, print, or commit it.**

After installing on physical hardware, **reboot the phone.** The LightOS launcher caches
its menu and won't show a freshly installed/updated tool until restarted.

---

## 4. What the app is

A personal Bible reader with two independent sections reachable from the home screen:

**Read** — browse by book, then chapter, then read.
**Plan** — a day-by-day reading plan; tapping a date opens that day's full passages.

Quiet, fast, readable. No streaks, no progress gamification, no notifications.

---

## 5. Reuse from the reader project

Do not build these from scratch. `Documents\reader` contains working implementations that
were verified on real hardware:

- **`tools/converter/convert.py`** — already parses EPUB containers, walks the OPF spine,
  reads NCX/nav titles, and strips HTML to clean plain text. The Bible converter is an
  **extension** of this, adding verse-level extraction. Start from that file.
- **`ReaderScreen.kt`** — `TextMeasurer`-based pagination, tap zones for page turns,
  chapter headings sized into the pagination budget, position stored as a character offset
  rather than a page number. The same approach applies here directly.
- **`BookRepository.kt` / position storage** — the `fileShare` read pattern and the Room
  position model.

`TextMeasurer` (from `androidx.compose.ui.text`) is reachable from the `tool` module and is
not on the blocked-import list. This was verified in the reader project.

---

## 6. Architecture — two pieces

### Piece A: the converter (PC only)

Extends the reader's `convert.py`. **The phone never parses EPUB.**

**Output** — one file per chapter, one verse per line:

```
bible/<translation>/<book-slug>/<chapter>.txt
```

```
33|Teach me, O LORD, the way of Your statutes, / And I shall observe it to the end.
34|Give me understanding, that I may observe Your law / And keep it with all my heart.
```

Verse number, pipe, verse text. Poetic line breaks within a verse become ` / `. This makes
verse-range extraction (needed for Psalm 119) a plain line filter on the phone — no
parsing, no index files.

Also emit `bible/<translation>/manifest.json`: display name, and per book its canonical
name, slug, and chapter count.

### Piece B: the app

Reads only the converted `.txt` files, `manifest.json`, and `reading_plan_2026.json` from
`lightContext.fileShare`. Knows nothing about EPUB.

---

## 7. Source files and extraction gotchas

Sources live at `C:\Users\bjc38\Documents\bible-source`, **outside the repo**. No scripture
text or converted output is ever committed. Gitignore the output directory.

- **NASB 1995** — Calibre EPUB, "Includes Translators' Notes." (The filename says 2013; the
  content is 1995.)
- **ESV** — **plain 2007 ESV text edition, not the Study Bible.** No study apparatus at all.

**Re-verified 2026-09-15 against the current files in `bible-source` (Jordan replaced the
source files; the notes below reflect what's actually in them now, not the earlier
download).**

**NASB:**
- Filenames are opaque Calibre split-part names (`text/part0018_split_009.html`) — **not**
  named by book/chapter. Each split file happens to hold one chapter's worth of content,
  but chapter identity must come from the in-page `<p class="head">Psalm 119</p>`-style
  heading, never the filename.
- Verse numbers use two different wrappers depending on position:
  - First verse after a paragraph/stanza break: `<span class="small1"><b class="calibre5">33</b></span>`
    (bold, no `<sup>`).
  - Every other verse: `<small class="small1"><sup class="calibre11">34</sup></small>`.
- Translator's-note markers use the **same markup as a mid-paragraph verse number**
  (`<small class="small1"><sup class="calibre11">a</sup></small>`) — digit vs. letter is
  the only discriminator there. The extra `<a href="...#fNNN">` wrapper on the note marker
  is **prose-only** — confirmed present in Genesis 38, Genesis 43, Exodus 16. In poetry
  (e.g. Psalm 119) note markers carry **no `<a href>`** at all, so don't rely on the href
  as a universal test — check digit-vs-letter first, in every genre.
- Translator's notes are fenced in literal `[ ... ]` — **but so are disputed passages**
  (John 7:53–8:11, Mark 16:9–20 both confirmed still bracket-wrapped this way). A rule
  stripping all bracketed text would delete scripture. Handle these explicitly.
- One known displaced note: Psalm 119 v56's note sits after the *next* section heading
  ("Heth.") — confirmed still present, byte-identical to the earlier file.
- `LORD` is small-caps split markup and needs recombining: `L<span class="small1">ORD</span>`,
  and for the possessive, `L<span class="small1">ORD</span>’<span class="small1">S</span>`.
- NASB's `*` before some verbs is part of the text — keep it. (Confirmed in the Gospels,
  e.g. Matthew 2, John 8 — historic-present marker. Not expected in OT poetry.)
- Book nav (`toc.ncx`) uses "Song of Solomon."

**ESV (plain 2007 text edition):**
- **No separate stream files exist.** There is no `*.text.xhtml` / `*.studynotes.xhtml` /
  `*.crossrefs.xhtml` / `*.footnotes.xhtml` / `*.intros.xhtml` / `*.main.xhtml`, and no
  `b19.00`…`b19.06`-style book-part split. Everything is flat, opaque Calibre split files
  (`The_Holy_Bib-rd_Version_ESV_split_1083.html`) — same shape as the NASB file, not the
  Study Bible layout the old notes described.
- Verse numbers are `<span class="bold">33 </span>` — plain bold, no `id`, no
  `verse-num` class, no verse-id scheme at all. **The character right after the number is
  U+00A0 (non-breaking space), not a regular space** — same for the mid-line indent before
  poetic second lines (`   and I will keep it...`). A plain `.strip()`/split
  on `" "` will not catch it; normalize NBSP to a regular space (or strip it) explicitly, or
  verse text will start with a stray character.
- **Verse 1 is fused with the chapter number only at a within-book chapter transition** —
  `<span class="bold" id="filepos...."><big class="calibre18">119</big>:1 </span>`, confirmed
  at Psalm 119. **Corrected 2026-09-15:** this does NOT apply to a book's own opening
  chapter — confirmed against Jude 1:1, which is plain `<span class="bold">1 </span>` with
  no `<big>` chapter prefix. The fusion exists only to disambiguate a chapter change inside
  a book's flowing text; a book's first chapter needs no such marker since the book heading
  already establishes "chapter 1." Handle both forms: don't assume every verse 1 is fused,
  and don't assume every verse 1 is bare.
- Footnotes: the inline marker is
  `<a id="..." title="the note text" href="...split_NNNN.html#..." class="calibre4">[253]</a>`.
  The note text is duplicated in the `title` attribute — read it from there; there's no
  need to follow the `href` to the separate endnotes file it points to.
- No cross-references and no smallcap wrappers exist in this edition — `LORD` is plain
  uppercase text (verified: this edition's stylesheet has no smallcap class at all).
- Book nav (`toc.ncx`) uses "Song of Solomon" — matches NASB, so no cross-translation
  aliasing gap there.

**If bible-source is replaced again, re-verify this section before trusting it — both
files changed once already without the extraction notes being updated to match.**

---

## 8. The reading plan file

`reading_plan_2026.json`. Each day:

```json
{
  "day": 120, "date": "2026-04-30", "weekday": "Thursday",
  "displayDate": "Apr 30", "complete": false,
  "label": "Isaiah 45-48 • Psalms 119:33-64",
  "passages": [
    { "book": "Isaiah", "startChapter": 45, "endChapter": 48 },
    { "book": "Psalms", "startChapter": 119, "endChapter": 119,
      "startVerse": 33, "endVerse": 64 }
  ]
}
```

- 358 reading days (Day 1 = Jan 1 2026, Day 358 = Dec 24) plus 7 days with
  `"complete": true` and label `PLAN COMPLETE` for Dec 25–31.
- `label` is display-ready. `passages` is what you look up.
- `startVerse`/`endVerse` appear only on the twelve Psalm 119 days. When present, show
  **only those verses**.
- The day's psalm is shown together with the rest of that day's reading, not as a separate
  section.

**Book name resolution — the most likely source of silent bugs.** The file carries a
`bookAliases` map for all 66 books, plus `canonicalBookOrder`. Resolve every book name
through it before lookup — case-insensitively, ignoring periods and extra whitespace.
Never compare book names directly. The translation files and the plan do not use the same
spellings ("Song of Solomon" vs "Song of Songs", "Psalm" vs "Psalms").

---

## 9. Screens

**HomeScreen** (`@InitialScreen`) — two entries, **Read** and **Plan**, plus access to
translation selection.

**BooksScreen** — all 66 books in canonical order under two headings: **Old Testament**
(Genesis–Malachi) and **New Testament** (Matthew–Revelation).

**ChaptersScreen** — the chapter numbers for the selected book.

**ChapterScreen** — the chapter text, paginated using the reader project's approach. Book
and chapter at the top. Verse numbers inline, lighter than the text.

**PlanScreen** — the plan by day, each row showing date and label. Opens on today's date if
today falls in the plan year; otherwise Day 1.

**PlanDayScreen** — that day's full text, all passages in the order given in `passages`,
each with its own heading, paginated. Psalm 119 days show only the assigned verses.
`PLAN COMPLETE` days show a short message and nothing else.

**TranslationScreen** — pick the active translation. Persists, and applies to both sections.

---

## 10. Build order

One phase per session. Do not start the next until the current compiles and Jordan has
confirmed it looks right.

0. **Recon** — verify the copied `NOTES.md` against the current SDK.
1. **Converter** — extend the reader's `convert.py` for verse-level extraction. Verify one
   book, then the hard cases, then run both translations in full.
2. **Data layer** — read converted chapters, manifest, and the plan file. No UI.
3. **Read section** — Home, Books, Chapters, Chapter text.
4. **Plan section** — the day list and the day view.
5. **Translation switching.**
6. **Polish** — typography and spacing for sustained reading.

---

## 11. Working agreement

- If an SDK component doesn't exist, say so and propose building it from primitives.
- If a lint rule blocks an approach, name what it blocked and give two alternatives.
- Keep functions short and the layout obvious. Jordan reads this code himself and is not an
  Android developer.
- Don't add features that aren't in this spec. Suggest, don't build.
- **Never guess at scripture text.** If extraction produces something uncertain, surface it
  rather than filling the gap.
