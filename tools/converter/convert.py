#!/usr/bin/env python3
"""Converts EPUB files into the reader app's plain-text book format.

Runs on the PC — see CLAUDE.md section 5. The phone never parses EPUB; this
script does it once and writes books/<slug>/{meta.json,001.txt,002.txt,...}.
Each chapter file is plain text: paragraphs separated by a blank line, no
markup, no HTML entities, no image references.

Usage:
    python convert.py BOOK.epub [BOOK2.epub ...] [--out books]
"""

from __future__ import annotations

import argparse
import json
import posixpath
import re
import sys
import zipfile
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path
from xml.etree import ElementTree as ET

CONTAINER_PATH = "META-INF/container.xml"

NS_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
NS_OPF = "http://www.idpf.org/2007/opf"
NS_DC = "http://purl.org/dc/elements/1.1/"
NS_NCX = "http://www.daisy.org/z3986/2005/ncx/"
NS_XHTML = "http://www.w3.org/1999/xhtml"
NS_EPUB = "http://www.idpf.org/2007/ops"

CONTENT_MEDIA_TYPES = {"application/xhtml+xml", "text/html"}

# Tags whose start/end marks a paragraph boundary when flattening HTML to text.
BLOCK_BREAK_TAGS = {
    "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "section", "article", "header", "footer", "tr",
}
SKIPPED_CONTENT_TAGS = {"script", "style"}


class ConversionError(Exception):
    """Raised when an EPUB can't be converted (e.g. it's DRM-protected)."""


@dataclass
class ManifestItem:
    href: str  # zip-relative path, fragment stripped, normalized
    media_type: str
    properties: set[str]


@dataclass
class Chapter:
    title: str
    text: str


@dataclass
class Book:
    title: str
    author: str
    slug: str
    chapters: list[Chapter]


def convert_epub(epub_path: Path) -> Book:
    with zipfile.ZipFile(epub_path) as zf:
        if "META-INF/encryption.xml" in set(zf.namelist()):
            raise ConversionError("DRM-protected (META-INF/encryption.xml present)")

        opf_path = _find_opf_path(zf)
        opf_dir = _zip_dirname(opf_path)
        try:
            opf_root = ET.fromstring(zf.read(opf_path))
        except ET.ParseError as e:
            raise ConversionError(f"{opf_path} is not valid XML: {e}") from e

        title, author = _read_metadata(opf_root)
        manifest = _read_manifest(opf_root, opf_dir)
        spine_items, toc_id = _read_spine(opf_root)
        titles = _load_titles(zf, manifest, toc_id)

        chapters: list[Chapter] = []
        for idref, linear in spine_items:
            if not linear:
                continue
            item = manifest.get(idref)
            if item is None or "nav" in item.properties:
                continue
            if item.media_type not in CONTENT_MEDIA_TYPES:
                continue
            html = _decode_html_bytes(zf.read(item.href), item.href)
            text = _extract_text(html)
            if not text:
                continue
            chapter_title = titles.get(item.href) or f"Chapter {len(chapters) + 1}"
            chapters.append(Chapter(title=chapter_title, text=text))

    if not chapters:
        raise ConversionError("no readable chapters found")

    return Book(title=title, author=author, slug=_slugify(title), chapters=chapters)


def write_book(book: Book, out_dir: Path) -> Path:
    book_dir = out_dir / book.slug
    book_dir.mkdir(parents=True, exist_ok=True)
    chapters_meta = []
    for i, chapter in enumerate(book.chapters, start=1):
        filename = f"{i:03d}.txt"
        (book_dir / filename).write_text(chapter.text + "\n", encoding="utf-8")
        chapters_meta.append(
            {"index": i, "title": chapter.title, "file": filename, "chars": len(chapter.text)}
        )
    meta = {
        "slug": book.slug,
        "title": book.title,
        "author": book.author,
        "chapters": chapters_meta,
    }
    (book_dir / "meta.json").write_text(
        json.dumps(meta, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return book_dir


# --- OPF / NCX / nav parsing -------------------------------------------------


def _find_opf_path(zf: zipfile.ZipFile) -> str:
    try:
        container = ET.fromstring(zf.read(CONTAINER_PATH))
    except KeyError:
        raise ConversionError(f"missing {CONTAINER_PATH}") from None
    except ET.ParseError as e:
        raise ConversionError(f"{CONTAINER_PATH} is not valid XML: {e}") from e
    rootfile = container.find(f".//{{{NS_CONTAINER}}}rootfile")
    if rootfile is None or "full-path" not in rootfile.attrib:
        raise ConversionError(f"{CONTAINER_PATH} has no <rootfile full-path=...>")
    return rootfile.attrib["full-path"]


def _read_metadata(opf_root: ET.Element) -> tuple[str, str]:
    title_el = opf_root.find(f".//{{{NS_DC}}}title")
    creator_el = opf_root.find(f".//{{{NS_DC}}}creator")
    title = (title_el.text or "").strip() if title_el is not None else ""
    author = (creator_el.text or "").strip() if creator_el is not None else ""
    return title or "Untitled", author or "Unknown"


def _read_manifest(opf_root: ET.Element, opf_dir: str) -> dict[str, ManifestItem]:
    items: dict[str, ManifestItem] = {}
    for item in opf_root.findall(f".//{{{NS_OPF}}}manifest/{{{NS_OPF}}}item"):
        item_id = item.attrib.get("id")
        href = item.attrib.get("href")
        if item_id is None or href is None:
            continue
        items[item_id] = ManifestItem(
            href=_resolve(opf_dir, href),
            media_type=item.attrib.get("media-type", ""),
            properties=set(item.attrib.get("properties", "").split()),
        )
    return items


def _read_spine(opf_root: ET.Element) -> tuple[list[tuple[str, bool]], str | None]:
    spine_el = opf_root.find(f".//{{{NS_OPF}}}spine")
    if spine_el is None:
        raise ConversionError("OPF has no <spine>")
    items = [
        (itemref.attrib["idref"], itemref.attrib.get("linear", "yes") != "no")
        for itemref in spine_el.findall(f"{{{NS_OPF}}}itemref")
        if "idref" in itemref.attrib
    ]
    return items, spine_el.attrib.get("toc")


def _load_titles(
    zf: zipfile.ZipFile, manifest: dict[str, ManifestItem], toc_id: str | None
) -> dict[str, str]:
    """Chapter titles keyed by resolved content href, per CLAUDE.md: NCX first, nav.xhtml fallback."""
    ncx_item = manifest.get(toc_id) if toc_id else None
    if ncx_item is None:
        ncx_item = next(
            (m for m in manifest.values() if m.media_type == "application/x-dtbncx+xml"), None
        )
    if ncx_item is not None:
        try:
            return {h: _repair_title(t) for h, t in _read_ncx_titles(zf, ncx_item.href).items()}
        except ET.ParseError:
            pass

    nav_item = next((m for m in manifest.values() if "nav" in m.properties), None)
    if nav_item is not None:
        try:
            return {h: _repair_title(t) for h, t in _read_nav_doc_titles(zf, nav_item.href).items()}
        except ET.ParseError:
            pass

    return {}


# Some publishers' NCX/nav titles are generated by flattening styled text runs
# (e.g. a word plus an adjacent italic word) and drop the space at the seam,
# producing things like "theOlympic" or "PARTIII:". Only the title string is
# affected — the chapter body keeps its own, unmangled text nodes — so this
# repair is applied to titles only, never to extracted chapter text.
_ROMAN_NUMERALS = (  # longest first, so a greedy search finds the full suffix
    "XX", "XIX", "XVIII", "XVII", "XVI", "XV", "XIV", "XIII", "XII", "XI",
    "X", "IX", "VIII", "VII", "VI", "V", "IV", "III", "II", "I",
)
_ROMAN_LETTERS = set("IVXLCDM")
_CAPS_WORD_RE = re.compile(r"[A-Z]{2,}")


def _repair_title(title: str) -> str:
    # A lowercase run directly followed by an uppercase run: "theOlympic" -> "the Olympic".
    title = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", title)
    # An all-caps word fused to a trailing roman numeral: "PARTIII" -> "PART III".
    return _CAPS_WORD_RE.sub(_split_word_and_numeral, title)


def _split_word_and_numeral(match: re.Match[str]) -> str:
    word = match.group(0)
    for numeral in _ROMAN_NUMERALS:
        if word.endswith(numeral):
            prefix = word[: -len(numeral)]
            # Require a 2+ letter prefix with no roman-numeral letters in it, so a
            # standalone numeral ("XVI") or a short word ("SIX", prefix "S") isn't
            # torn in two. Verified against every title in the actual library below.
            if len(prefix) >= 2 and not (set(prefix) & _ROMAN_LETTERS):
                return f"{prefix} {numeral}"
    return word


def _read_ncx_titles(zf: zipfile.ZipFile, ncx_path: str) -> dict[str, str]:
    ncx_dir = _zip_dirname(ncx_path)
    root = ET.fromstring(zf.read(ncx_path))
    nav_map = root.find(f"{{{NS_NCX}}}navMap")
    titles: dict[str, str] = {}
    if nav_map is None:
        return titles

    def walk(el: ET.Element) -> None:
        for nav_point in el.findall(f"{{{NS_NCX}}}navPoint"):
            label_el = nav_point.find(f"{{{NS_NCX}}}navLabel/{{{NS_NCX}}}text")
            content_el = nav_point.find(f"{{{NS_NCX}}}content")
            if label_el is not None and content_el is not None:
                text = (label_el.text or "").strip()
                src = content_el.attrib.get("src", "")
                if text and src:
                    # First occurrence wins, so a whole-chapter navPoint's title
                    # is kept even when later navPoints point into the same
                    # file at a finer (e.g. scene-level) granularity.
                    titles.setdefault(_resolve(ncx_dir, src), text)
            walk(nav_point)

    walk(nav_map)
    return titles


def _read_nav_doc_titles(zf: zipfile.ZipFile, nav_path: str) -> dict[str, str]:
    nav_dir = _zip_dirname(nav_path)
    root = ET.fromstring(zf.read(nav_path))
    toc_nav = next(
        (
            el
            for el in root.findall(f".//{{{NS_XHTML}}}nav")
            if el.attrib.get(f"{{{NS_EPUB}}}type") == "toc"
        ),
        None,
    )
    titles: dict[str, str] = {}
    if toc_nav is None:
        return titles
    for a in toc_nav.findall(f".//{{{NS_XHTML}}}a"):
        href = a.attrib.get("href")
        if not href:
            continue
        text = re.sub(r"\s+", " ", "".join(a.itertext())).strip()
        if text:
            titles.setdefault(_resolve(nav_dir, href), text)
    return titles


# --- HTML -> plain text -------------------------------------------------------


class _TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._parts: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag in SKIPPED_CONTENT_TAGS:
            self._skip_depth += 1
        elif tag in BLOCK_BREAK_TAGS:
            self._parts.append("\n\n")

    def handle_startendtag(self, tag: str, attrs) -> None:
        if tag == "br":
            self._parts.append("\n\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in SKIPPED_CONTENT_TAGS:
            self._skip_depth = max(0, self._skip_depth - 1)
        elif tag in BLOCK_BREAK_TAGS:
            self._parts.append("\n\n")

    def handle_data(self, data: str) -> None:
        if self._skip_depth == 0:
            self._parts.append(data)

    def get_text(self) -> str:
        raw = "".join(self._parts)
        paragraphs = (re.sub(r"\s+", " ", chunk).strip() for chunk in re.split(r"\n{2,}", raw))
        return "\n\n".join(p for p in paragraphs if p)


def _extract_text(html: str) -> str:
    parser = _TextExtractor()
    parser.feed(html)
    parser.close()
    return parser.get_text()


def _decode_html_bytes(data: bytes, path: str) -> str:
    declared = re.search(rb'encoding=["\']([\w-]+)["\']', data[:500])
    for enc in filter(None, [declared and declared.group(1).decode("ascii"), "utf-8", "cp1252"]):
        try:
            return data.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    raise ConversionError(f"could not decode {path} in any known encoding")


# --- helpers -------------------------------------------------------------


def _zip_dirname(path: str) -> str:
    return path.rsplit("/", 1)[0] + "/" if "/" in path else ""


def _resolve(base_dir: str, href: str) -> str:
    return posixpath.normpath(posixpath.join(base_dir, href.split("#", 1)[0]))


def _slugify(title: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-") or "untitled"


# --- CLI -------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Convert EPUB(s) into the reader app's book format.")
    parser.add_argument("epub", nargs="+", type=Path, help="EPUB file(s) to convert")
    parser.add_argument("--out", type=Path, default=Path("books"), help="output directory (default: ./books)")
    args = parser.parse_args(argv)

    exit_code = 0
    for epub_path in args.epub:
        try:
            book = convert_epub(epub_path)
        except ConversionError as e:
            print(f"SKIPPED {epub_path.name}: {e}", file=sys.stderr)
            exit_code = 1
            continue
        book_dir = write_book(book, args.out)
        print(f"{epub_path.name} -> {book_dir}/  ({len(book.chapters)} chapters)")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
