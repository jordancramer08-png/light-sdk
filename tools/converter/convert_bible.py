#!/usr/bin/env python3
"""Converts Bible EPUBs into the app's verse-level plain-text format.

Runs on the PC — see CLAUDE.md section 6. The phone never parses EPUB; this
script does it once. Extends the reader project's convert.py
(../../../reader/tools/converter/convert.py), reusing its OPF/manifest/spine
parsing, and adds verse-level extraction specific to the NASB and ESV source
files documented in CLAUDE.md section 7.

Output — one file per chapter, one verse per line, "N|verse text":

    bible/<translation>/<book-slug>/<chapter>.txt

plus bible/<translation>/manifest.json (display name + per-book canonical
name/slug/chapter count, merged across runs so books can be converted and
verified one at a time per CLAUDE.md's build order).

Usage:
    python convert_bible.py BIBLE.epub [BIBLE2.epub ...] [--out bible]
                             [--plan path/to/reading_plan_2026.json]
                             [--book "Jude"]

--book restricts conversion to one canonical book (case-insensitive), for the
verify-one-book-at-a-time workflow. Omit it to convert everything in the file.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from dataclasses import dataclass, field
from html.parser import HTMLParser
from pathlib import Path
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).parent))
from convert import (  # noqa: E402
    ConversionError,
    ManifestItem,
    _decode_html_bytes,
    _find_opf_path,
    _read_manifest,
    _read_spine,
    _resolve,
    _zip_dirname,
)

NS_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
NS_OPF = "http://www.idpf.org/2007/opf"
NS_DC = "http://purl.org/dc/elements/1.1/"
NS_NCX = "http://www.daisy.org/z3986/2005/ncx/"

DEFAULT_PLAN_PATH = Path(r"C:\Users\bjc38\Documents\bible-source\reading_plan_2026.json")

TRANSLATIONS = {
    "New American Standard Bible": ("nasb", "New American Standard Bible (1995)"),
    "The Holy Bible English Standard Version": ("esv", "English Standard Version (2007)"),
}


class BibleConversionError(Exception):
    """Raised when a Bible EPUB can't be converted, or a book/chapter can't be
    placed confidently. Per CLAUDE.md: never guess at scripture text — surface
    uncertainty instead of filling the gap."""


@dataclass
class BookRegistry:
    canonical_order: list[str]
    alias_lookup: dict[str, str]  # normalized alias/name -> canonical name

    def resolve(self, raw_name: str) -> str | None:
        key = _normalize_book_name(raw_name)
        return self.alias_lookup.get(key)

    def slug(self, canonical_name: str) -> str:
        return re.sub(r"[^a-z0-9]+", "-", canonical_name.lower()).strip("-")


def _normalize_book_name(name: str) -> str:
    # Per CLAUDE.md section 8: match case-insensitively, ignoring periods and
    # extra whitespace.
    return re.sub(r"\s+", " ", name.replace(".", "")).strip().lower()


def load_book_registry(plan_path: Path) -> BookRegistry:
    with plan_path.open(encoding="utf-8") as f:
        plan = json.load(f)
    canonical_order: list[str] = plan["canonicalBookOrder"]
    alias_lookup: dict[str, str] = {}
    for canonical in canonical_order:
        alias_lookup[_normalize_book_name(canonical)] = canonical
    for canonical, aliases in plan["bookAliases"].items():
        for alias in aliases:
            alias_lookup[_normalize_book_name(alias)] = canonical
    return BookRegistry(canonical_order=canonical_order, alias_lookup=alias_lookup)


# --- shared verse/chapter accumulation --------------------------------------


@dataclass
class ChapterData:
    verses: dict[int, str] = field(default_factory=dict)
    verse_order: list[int] = field(default_factory=list)

    def set_verse(self, num: int, text: str) -> None:
        if num not in self.verses:
            self.verse_order.append(num)
        self.verses[num] = text


BookData = dict[int, ChapterData]  # chapter number -> ChapterData


def _clean_verse_text(raw: str) -> str:
    text = raw.replace("\xa0", " ")
    text = re.sub(r"\s+", " ", text).strip()
    # A trailing/leading " / " is a structural artifact of a poetic line-break
    # marker that closes out a verse with nothing following it (or precedes a
    # verse with nothing before it) — not part of the text itself.
    text = re.sub(r"^\s*/\s*", "", text)
    text = re.sub(r"\s*/\s*$", "", text)
    return text


# --- NASB extraction ---------------------------------------------------------
#
# One spine file per chapter. Chapter identity comes from the in-page
# <p class="head">BOOK NAME CHAPTER</p> heading, never the filename (CLAUDE.md
# section 7). Verses are plain prose (<p class="aleft"> + <br/>) or poetry
# (<div class="block">, one line per div, joined with " / "). A verse number
# is <span class="small1"><b class="calibre5">N</b></span> when it starts a
# fresh paragraph/stanza, or <small class="small1"><sup class="calibre11">N</
# sup></small> otherwise; the same wrapper with a letter instead of a digit is
# a translator's-note marker, not a verse. Translator's notes are introduced
# by a bare <a id="fNNNN"></a> immediately followed by "[" and run to the next
# "]" — stripped wholesale, never by blanket-bracket-stripping (disputed
# passages like John 7:53-8:11 also use literal brackets and must survive).

_HEAD_RE = re.compile(r'<p class="head">\s*([^<]+?)\s*(?:<|$)')


def nasb_peek_heading(html: str) -> tuple[str, int] | None:
    m = _HEAD_RE.search(html)
    if not m:
        return None
    text = re.sub(r"\s+", " ", m.group(1)).strip()
    m2 = re.match(r"^(.+?)\s+(\d+)$", text)
    if not m2:
        return None
    return m2.group(1), int(m2.group(2))


class NasbVerseExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.chapter = ChapterData()
        self._current_verse: int | None = None
        self._buffer: list[str] = []
        self._skip_depth = 0  # inside <p class="head"|"head2"> — never verse text
        self._p_skip_stack: list[bool] = []
        self._in_note = False
        self._awaiting_note_check = False
        self._span_awaiting_b = False
        self._in_bold_marker = False
        self._in_sup_marker = False
        self._marker_text = ""
        self._in_block = False  # <div class="block"> — one poetic line
        self._block_had_text = False
        self._p_aleft_stack: list[bool] = []
        self._in_prose_paragraph = False  # currently inside <p class="aleft">.
        # A single chapter can mix genres — e.g. Mark 7 is prose but quotes the OT
        # in a <div class="block"> stanza mid-chapter — so this must track the
        # CURRENT paragraph, never latch permanently once poetry is seen (that was
        # the bug: it made Mark 7:16's disputed-passage bracket, which comes later
        # in plain prose, silently vanish because an earlier quotation had flipped
        # a sticky "this chapter is poetry" flag). Poetry note markers have no
        # preceding <a id="fNNNN"></a> anchor (CLAUDE.md section 7), so a bare "["
        # starts a note wherever we're NOT inside a prose <p class="aleft"> — that
        # covers <div class="block"> lines and the odd displaced note that sits in
        # its own <p class="calibre1"> (Psalm 119:56). Inside a prose paragraph, a
        # bare "[" is left alone — only the anchor-triggered path strips a prose
        # note — because disputed passages (Mark 7:16, 16:9-20, John 7:53-8:11)
        # are bare "[...]" runs in prose with no anchor, and blindly stripping
        # them would delete scripture.

    def _start_new_verse(self, num: int) -> None:
        self._finish_verse()
        self._current_verse = num
        self._buffer = []

    def _finish_verse(self) -> None:
        if self._current_verse is not None:
            text = _clean_verse_text("".join(self._buffer))
            if text:
                self.chapter.set_verse(self._current_verse, text)

    def _emit(self, data: str) -> None:
        if self._skip_depth or self._current_verse is None:
            return
        self._buffer.append(data)

    def handle_starttag(self, tag: str, attrs) -> None:
        attrs_d = dict(attrs)
        cls = attrs_d.get("class")
        if tag == "p":
            is_skip = cls in ("head", "head2")
            self._p_skip_stack.append(is_skip)
            if is_skip:
                self._skip_depth += 1
            self._p_aleft_stack.append(cls == "aleft")
            if cls == "aleft":
                self._in_prose_paragraph = True
            return
        if self._in_note:
            return
        if tag == "a" and "id" in attrs_d and re.fullmatch(r"f\d+", attrs_d["id"]) and "href" not in attrs_d:
            self._awaiting_note_check = True
            return
        if tag == "span" and cls == "small1" and not self._in_bold_marker and not self._in_sup_marker:
            self._span_awaiting_b = True
            return
        if tag == "b" and cls == "calibre5" and self._span_awaiting_b:
            self._in_bold_marker = True
            self._marker_text = ""
            self._span_awaiting_b = False
            return
        self._span_awaiting_b = False
        if tag == "small" and cls == "small1" and not self._in_bold_marker:
            return  # neutral wrapper; <sup> inside decides digit vs letter
        if tag == "sup" and cls == "calibre11":
            self._in_sup_marker = True
            self._marker_text = ""
            return
        if tag == "div" and cls == "block":
            self._in_block = True
            self._block_had_text = False

    def handle_startendtag(self, tag: str, attrs) -> None:
        if tag == "br" and not self._in_note:
            # Prose paragraph break: natural whitespace around it already
            # collapses correctly. Poetry line breaks use <div class="block">
            # instead, handled on that tag's boundaries.
            self._emit(" ")

    def handle_endtag(self, tag: str) -> None:
        if tag == "p":
            if self._p_skip_stack:
                was_skip = self._p_skip_stack.pop()
                if was_skip:
                    self._skip_depth = max(0, self._skip_depth - 1)
            if self._p_aleft_stack:
                self._p_aleft_stack.pop()
            self._in_prose_paragraph = bool(self._p_aleft_stack) and self._p_aleft_stack[-1]
            return
        if self._in_note:
            return
        if tag == "b" and self._in_bold_marker:
            self._in_bold_marker = False
            num_text = self._marker_text.strip()
            self._marker_text = ""
            if num_text.isdigit():
                self._start_new_verse(int(num_text))
            return
        if tag == "sup" and self._in_sup_marker:
            self._in_sup_marker = False
            marker = self._marker_text.strip()
            self._marker_text = ""
            if marker.isdigit():
                self._start_new_verse(int(marker))
            # else: translator's-note letter marker — discarded, not emitted
            return
        if tag == "div" and self._in_block:
            self._in_block = False
            if self._block_had_text:
                self._emit(" / ")

    def handle_data(self, data: str) -> None:
        if self._awaiting_note_check:
            self._awaiting_note_check = False
            stripped = data.lstrip()
            if stripped.startswith("["):
                self._in_note = True
                self._consume_note_data(stripped)
                return
            # Anchor wasn't followed by a note; fall through to normal handling.
        if self._in_note:
            self._consume_note_data(data)
            return
        if self._in_bold_marker or self._in_sup_marker:
            self._marker_text += data
            return
        if (
            not self._in_prose_paragraph
            and "[" in data
            and not self._skip_depth
            and self._current_verse is not None
        ):
            before, _, after = data.partition("[")
            if after.strip() == "":
                if before:
                    if self._in_block and before.strip():
                        self._block_had_text = True
                    self._emit(before)
                self._in_note = True
                return
            # "[" not immediately followed by a tag — not a note marker; fall
            # through and emit the whole chunk literally.
        if self._in_block and data.strip():
            self._block_had_text = True
        self._emit(data)

    def _consume_note_data(self, data: str) -> None:
        idx = data.find("]")
        if idx == -1:
            return
        self._in_note = False
        remainder = data[idx + 1 :]
        if remainder:
            self.handle_data(remainder)

    def close(self) -> None:
        super().close()
        self._finish_verse()


def convert_nasb(
    zf: zipfile.ZipFile,
    registry: BookRegistry,
    book_filter: str | None,
    anomalies: list[str],
) -> dict[str, BookData]:
    opf_path = _find_opf_path(zf)
    opf_dir = _zip_dirname(opf_path)
    opf_root = ET.fromstring(zf.read(opf_path))
    manifest = _read_manifest(opf_root, opf_dir)
    spine_items, _ = _read_spine(opf_root)

    result: dict[str, BookData] = {}
    for idref, linear in spine_items:
        if not linear:
            continue
        item = manifest.get(idref)
        if item is None or item.media_type not in ("application/xhtml+xml", "text/html"):
            continue
        html = _decode_html_bytes(zf.read(item.href), item.href)
        heading = nasb_peek_heading(html)
        if heading is None:
            continue
        raw_book, chapter_num = heading
        canonical = registry.resolve(raw_book)
        if canonical is None:
            anomalies.append(
                f"NASB {item.href}: heading {raw_book!r} did not resolve to a canonical book name — skipped"
            )
            continue
        if book_filter is not None and canonical != book_filter:
            continue
        try:
            parser = NasbVerseExtractor()
            parser.feed(html)
            parser.close()
        except Exception as e:  # noqa: BLE001 — surface, don't abort the whole run
            anomalies.append(f"NASB {canonical} {chapter_num} ({item.href}): {e!r} — skipped")
            continue
        if not parser.chapter.verse_order:
            anomalies.append(f"NASB {canonical} {chapter_num} ({item.href}): produced zero verses")
            continue
        result.setdefault(canonical, {})[chapter_num] = parser.chapter
    for canonical, chapters in result.items():
        max_chapter = max(chapters)
        for n in range(1, max_chapter + 1):
            chapter = chapters.get(n)
            if chapter is None or not chapter.verse_order:
                anomalies.append(f"NASB {canonical} {n}: missing or zero verses")
    return result


# --- ESV extraction -----------------------------------------------------------
#
# Flat spine of small, arbitrarily-broken files — no one-file-per-chapter
# split, and the NCX only has book-level entries (CLAUDE.md section 7). A
# book's content runs from its NCX-listed start file up to (but not
# including) the next book's start file, and chapter/verse state must be
# carried across that whole run of files. Verse 1 of a NEW chapter fuses the
# chapter number in: <span class="bold"><big class="calibre18">119</big>:1
# </span>. Verse 1 of a book's first chapter is plain "1" with no fused
# chapter number — confirmed against Jude; CLAUDE.md's "every time" claim
# does not hold for a book's opening chapter, only for within-book chapter
# transitions. Both forms are handled generically here. The character right
# after every verse number is U+00A0 (NBSP), not a plain space.

_H2_RE = re.compile(r"<h2[^>]*>([^<]*)</h2>")


def esv_is_footnotes_page(html: str) -> bool:
    m = _H2_RE.search(html)
    return m is not None and m.group(1).strip() == "Footnotes"


class EsvVerseExtractor(HTMLParser):
    """Stateful across every file in one book's spine range."""

    def __init__(self, starting_chapter: int) -> None:
        super().__init__(convert_charrefs=True)
        self.chapters: dict[int, ChapterData] = {}
        self._current_chapter = starting_chapter
        self._current_verse: int | None = None
        self._buffer: list[str] = []
        self._skip_depth = 0  # inside <h2>/<h3> section heading text
        self._in_bold_span = False
        self._in_big = False
        self._bold_chapter_text = ""
        self._bold_verse_text = ""
        self._in_footnote_marker = False

    def _current_chapter_data(self) -> ChapterData:
        return self.chapters.setdefault(self._current_chapter, ChapterData())

    def _finish_verse(self) -> None:
        if self._current_verse is not None:
            text = _clean_verse_text("".join(self._buffer))
            if text:
                self._current_chapter_data().set_verse(self._current_verse, text)

    def _start_new_verse(self, chapter: int, verse: int) -> None:
        self._finish_verse()
        self._current_chapter = chapter
        self._current_verse = verse
        self._buffer = []

    def _emit(self, data: str) -> None:
        if self._skip_depth or self._current_verse is None or self._in_footnote_marker:
            return
        self._buffer.append(data)

    def handle_starttag(self, tag: str, attrs) -> None:
        attrs_d = dict(attrs)
        cls = attrs_d.get("class")
        if tag in ("h2", "h3", "head"):
            self._skip_depth += 1
            return
        if tag == "a" and cls == "calibre4":
            self._in_footnote_marker = True
            return
        if tag == "span" and cls == "bold":
            self._in_bold_span = True
            self._bold_chapter_text = ""
            self._bold_verse_text = ""
            return
        if tag == "big" and self._in_bold_span:
            self._in_big = True

    def handle_startendtag(self, tag: str, attrs) -> None:
        if tag == "br" and not self._in_bold_span and not self._in_footnote_marker:
            # ESV prose paragraphs never use <br/>; only poetic line breaks do.
            self._emit(" / ")

    def handle_endtag(self, tag: str) -> None:
        if tag in ("h2", "h3", "head"):
            self._skip_depth = max(0, self._skip_depth - 1)
            return
        if tag == "a" and self._in_footnote_marker:
            self._in_footnote_marker = False
            return
        if tag == "big" and self._in_big:
            self._in_big = False
            return
        if tag == "span" and self._in_bold_span:
            self._in_bold_span = False
            chapter_text = self._bold_chapter_text.strip()
            verse_text = self._bold_verse_text.replace("\xa0", " ").strip()
            verse_text = verse_text.lstrip(":").strip()
            if not verse_text.isdigit():
                raise BibleConversionError(f"unparseable ESV verse marker: {verse_text!r}")
            chapter = int(chapter_text) if chapter_text else self._current_chapter
            self._start_new_verse(chapter, int(verse_text))

    def handle_data(self, data: str) -> None:
        if self._in_bold_span:
            if self._in_big:
                self._bold_chapter_text += data
            else:
                self._bold_verse_text += data
            return
        self._emit(data)

    def close(self) -> None:
        super().close()
        self._finish_verse()


def _read_ncx_book_starts(zf: zipfile.ZipFile, opf_root: ET.Element, opf_dir: str) -> list[tuple[str, str]]:
    manifest = _read_manifest(opf_root, opf_dir)
    ncx_item = next((m for m in manifest.values() if m.media_type == "application/x-dtbncx+xml"), None)
    if ncx_item is None:
        raise BibleConversionError("no NCX found")
    root = ET.fromstring(zf.read(ncx_item.href))
    nav_map = root.find(f"{{{NS_NCX}}}navMap")
    if nav_map is None:
        raise BibleConversionError("NCX has no navMap")
    ncx_dir = _zip_dirname(ncx_item.href)
    out: list[tuple[str, str]] = []
    for nav_point in root.iter(f"{{{NS_NCX}}}navPoint"):
        label_el = nav_point.find(f"{{{NS_NCX}}}navLabel/{{{NS_NCX}}}text")
        content_el = nav_point.find(f"{{{NS_NCX}}}content")
        if label_el is None or content_el is None:
            continue
        text = (label_el.text or "").strip()
        src = content_el.attrib.get("src", "")
        if text and src:
            out.append((text, _resolve(ncx_dir, src)))
    return out


def convert_esv(
    zf: zipfile.ZipFile,
    registry: BookRegistry,
    book_filter: str | None,
    anomalies: list[str],
) -> dict[str, BookData]:
    opf_path = _find_opf_path(zf)
    opf_dir = _zip_dirname(opf_path)
    opf_root = ET.fromstring(zf.read(opf_path))
    manifest = _read_manifest(opf_root, opf_dir)
    spine_items, _ = _read_spine(opf_root)
    spine_hrefs = [manifest[idref].href for idref, linear in spine_items if linear and idref in manifest]
    spine_index = {href: i for i, href in enumerate(spine_hrefs)}

    ncx_entries = _read_ncx_book_starts(zf, opf_root, opf_dir)
    book_starts: list[tuple[str, int]] = []  # (canonical name, spine index)
    for raw_title, href in ncx_entries:
        canonical = registry.resolve(raw_title)
        if canonical is None:
            continue  # front matter etc. — not a book
        idx = spine_index.get(href)
        if idx is None:
            anomalies.append(f"ESV NCX entry {raw_title!r} -> {href} not found in spine — book skipped")
            continue
        book_starts.append((canonical, idx))

    result: dict[str, BookData] = {}
    for i, (canonical, start_idx) in enumerate(book_starts):
        if book_filter is not None and canonical != book_filter:
            continue
        end_idx = book_starts[i + 1][1] if i + 1 < len(book_starts) else len(spine_hrefs)
        try:
            parser = EsvVerseExtractor(starting_chapter=1)
            any_content = False
            for href in spine_hrefs[start_idx:end_idx]:
                html = _decode_html_bytes(zf.read(href), href)
                if esv_is_footnotes_page(html):
                    continue
                any_content = True
                parser.feed(html)
            parser.close()
        except Exception as e:  # noqa: BLE001 — surface, don't abort the whole run
            anomalies.append(f"ESV {canonical}: {e!r} — book skipped")
            continue
        if not any_content or not any(c.verse_order for c in parser.chapters.values()):
            anomalies.append(f"ESV {canonical}: produced zero verses — book skipped")
            continue
        if parser.chapters:
            max_chapter = max(parser.chapters)
            for n in range(1, max_chapter + 1):
                chapter = parser.chapters.get(n)
                if chapter is None or not chapter.verse_order:
                    anomalies.append(f"ESV {canonical} {n}: missing or zero verses")
        result[canonical] = parser.chapters
    return result


# --- output -------------------------------------------------------------------


def detect_translation(zf: zipfile.ZipFile) -> tuple[str, str]:
    opf_path = _find_opf_path(zf)
    opf_root = ET.fromstring(zf.read(opf_path))
    title_el = opf_root.find(f".//{{{NS_DC}}}title")
    title = (title_el.text or "").strip() if title_el is not None else ""
    for prefix, (slug, display) in TRANSLATIONS.items():
        if title.startswith(prefix):
            return slug, display
    raise BibleConversionError(f"unrecognized translation title: {title!r}")


def write_books(
    translation_slug: str,
    translation_display: str,
    books: dict[str, BookData],
    registry: BookRegistry,
    out_dir: Path,
) -> None:
    trans_dir = out_dir / translation_slug
    trans_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = trans_dir / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    else:
        manifest = {"displayName": translation_display, "books": {}}

    for canonical_name, chapters in books.items():
        slug = registry.slug(canonical_name)
        book_dir = trans_dir / slug
        book_dir.mkdir(parents=True, exist_ok=True)
        for chapter_num, chapter in chapters.items():
            lines = [f"{v}|{chapter.verses[v]}" for v in sorted(chapter.verse_order)]
            (book_dir / f"{chapter_num}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        manifest["books"][canonical_name] = {
            "name": canonical_name,
            "slug": slug,
            "chapterCount": max(chapters.keys()),
        }

    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


# --- CLI -----------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Convert Bible EPUB(s) into the app's verse-level format.")
    parser.add_argument("epub", nargs="+", type=Path, help="Bible EPUB file(s) to convert")
    parser.add_argument("--out", type=Path, default=Path("bible"), help="output directory (default: ./bible)")
    parser.add_argument("--plan", type=Path, default=DEFAULT_PLAN_PATH, help="path to reading_plan_2026.json")
    parser.add_argument("--book", type=str, default=None, help="restrict to one canonical book name")
    args = parser.parse_args(argv)

    registry = load_book_registry(args.plan)
    book_filter = registry.resolve(args.book) if args.book else None
    if args.book and book_filter is None:
        print(f"ERROR: --book {args.book!r} did not resolve to a canonical book name", file=sys.stderr)
        return 1

    exit_code = 0
    for epub_path in args.epub:
        anomalies: list[str] = []
        try:
            with zipfile.ZipFile(epub_path) as zf:
                slug, display = detect_translation(zf)
                if slug == "nasb":
                    books = convert_nasb(zf, registry, book_filter, anomalies)
                elif slug == "esv":
                    books = convert_esv(zf, registry, book_filter, anomalies)
                else:
                    raise BibleConversionError(f"no extractor registered for translation {slug!r}")
        except (ConversionError, BibleConversionError) as e:
            print(f"SKIPPED {epub_path.name}: {e}", file=sys.stderr)
            exit_code = 1
            continue
        write_books(slug, display, books, registry, args.out)
        chapter_count = sum(len(c) for c in books.values())
        print(f"{epub_path.name} -> {args.out / slug}/  ({len(books)} book(s), {chapter_count} chapter(s))")
        if anomalies:
            exit_code = 1
            print(f"  {len(anomalies)} anomaly(ies):", file=sys.stderr)
            for a in anomalies:
                print(f"    {a}", file=sys.stderr)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
