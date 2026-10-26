#!/usr/bin/env python3
"""Scans an exported tree for what must not reach the public mirror (called by tools/publish.sh).

    python3 -I tools/publish_scan.py <tree>      a tree
    python3 -I tools/publish_scan.py - < text    a text (a commit message)

Prints one line per hit, "HIT [rule] path[:line]", never the matched text. Exit 0: clean, 1: hits, 2: usage,
3: the scan itself failed (publish.sh treats that as a hit). Text files are read as UTF-8, UTF-16 or UTF-32; binary
files are scanned through their printable strings (ASCII, UTF-16LE and BE), PNG text chunks inflated, zip/jar and
gzip members opened; a symlink through its target.

Rule "owner-name": the owner is named only by his GitHub handle in anything public (board rule 2026-10-21). His
family name fails in any case and anywhere; his first name fails as a word, except as part of a place name
("Saint-François", "St-François", "St. Francois", which a Quebec cam may carry). Each line is matched as written and
with its JSON, HTML or URL escapes decoded, NFKC-normalised (decomposed "ç", fullwidth letters) and without format
characters (soft hyphen, zero-width space). (Character classes such as [s] keep this file from matching its own patterns.)
"""
import gzip
import html
import io
import os
import re
import sys
import unicodedata
import urllib.parse
import zipfile
import zlib

FAMILY = re.compile(r"lesag[e]", re.I)
# A letter on either side makes it another word ("Françoise"); "_", digits and "-" do not ("author_" + the name).
FIRST = re.compile(r"(?<![^\W\d_])fran[cç]oi[s](?![^\W\d_])", re.I)
CAMEL = re.compile(r"(?<=[a-z])Fran[cç]oi[s](?![a-z])")
PLACE = re.compile(r"(?<![^\W\d_])(saint|sainte|st|ste)\.?[- ]$", re.I)
ESCAPE = re.compile(r"\\u([0-9a-fA-F]{4})|\\x([0-9a-fA-F]{2})")

hits = 0
current = "?"


def hit(rule, where):
    global hits
    hits += 1
    print("  HIT [%s] %s" % (rule, where))


def clean(text):
    """NFKC (fullwidth letters, decomposed cedilla) without format characters (soft hyphen, zero-width space)."""
    text = unicodedata.normalize("NFKC", text)
    return "".join(c for c in text if unicodedata.category(c) != "Cf")


def variants(line):
    """The line as written, and as it reads once JSON/Kotlin, HTML/XML or URL escapes are decoded."""
    if line.isascii() and not any(c in line for c in "\\&%"):
        return {line}
    unescaped = ESCAPE.sub(lambda m: chr(int(m.group(1) or m.group(2), 16)), line)
    return {clean(v) for v in (line, unescaped, html.unescape(line), urllib.parse.unquote(line))}


def owner_name(line):
    for v in variants(line):
        if FAMILY.search(v) or CAMEL.search(v):
            return True
        if any(not PLACE.search(v[:m.start()]) for m in FIRST.finditer(v)):
            return True
    return False


def scan_text(rel, text, located=True):
    for n, line in enumerate(text.splitlines(), 1):
        if owner_name(line):
            hit("owner-name", "%s:%d" % (rel, n) if located else rel)


def strings(data):
    out = [m.group(0).decode("latin-1") for m in re.finditer(rb"[\x20-\x7e\xc0-\xff]{4,}", data)]
    out += [m.group(0).decode("utf-8", "replace") for m in re.finditer(rb"(?:[\x20-\x7e]|\xc3[\x80-\xbf]){4,}", data)]
    out += [m.group(0).decode("utf-16-le") for m in re.finditer(rb"(?:[\x20-\x7e\xc0-\xff]\x00){4,}", data)]
    out += [m.group(0).decode("utf-16-be") for m in re.finditer(rb"(?:\x00[\x20-\x7e\xc0-\xff]){4,}", data)]
    return "\n".join(out)


def png_text(data):
    """Text chunks of a PNG (tEXt, zTXt, iTXt), inflated: where an image editor writes an author."""
    out, pos = [], 8
    while pos + 8 <= len(data):
        size = int.from_bytes(data[pos:pos + 4], "big")
        kind, body = data[pos + 4:pos + 8], data[pos + 8:pos + 8 + size]
        pos += 12 + size
        if kind == b"tEXt":
            out.append(body.decode("latin-1"))
        elif kind == b"zTXt":
            key, _, rest = body.partition(b"\0")
            out.append(key.decode("latin-1") + " " + zlib.decompress(rest[1:]).decode("latin-1"))
        elif kind == b"iTXt":
            key, _, rest = body.partition(b"\0")
            compressed, rest = rest[0], rest[2:]
            _lang, _, rest = rest.partition(b"\0")
            _tkey, _, text = rest.partition(b"\0")
            text = zlib.decompress(text) if compressed else text
            out.append(key.decode("latin-1") + " " + text.decode("utf-8", "replace"))
    return "\n".join(out)


def decode(data):
    if data.startswith((b"\xff\xfe\0\0", b"\0\0\xfe\xff")):
        return data.decode("utf-32")
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16")
    if b"\0" not in data:
        try:
            return data.decode("utf-8")
        except UnicodeDecodeError:
            pass
    return None


def scan_bytes(rel, data, depth=0):
    text = decode(data)
    if text is not None:
        scan_text(rel, text)
        return
    scan_text(rel, strings(data), located=False)
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        scan_text(rel, png_text(data), located=False)
    if depth >= 3:  # an archive in an archive in an archive: deeper than anything this repo ships
        return
    if data.startswith(b"PK\x03\x04"):  # zip, jar, apk: the gradle wrapper's MANIFEST.MF carries a Built-By
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for member in z.namelist():
                scan_text(rel + "!" + member, member, located=False)
                scan_bytes(rel + "!" + member, z.read(member), depth + 1)
    elif data.startswith(b"\x1f\x8b"):
        scan_bytes(rel + "!gz", gzip.decompress(data), depth + 1)


def fail(err):
    raise err


def main(root):
    global current
    for dirpath, dirnames, filenames in os.walk(root, onerror=fail):
        dirnames[:] = [d for d in dirnames if d != ".git"]
        for name in sorted(dirnames + filenames):
            full = os.path.join(dirpath, name)
            rel = current = os.path.relpath(full, root)
            scan_text(rel, rel, located=False)
            if os.path.islink(full):  # git archive exports the target as the link's content
                scan_text(rel, os.readlink(full), located=False)
                continue
            if os.path.isdir(full):
                continue
            with open(full, "rb") as f:
                scan_bytes(rel, f.read())
    return 1 if hits else 0


if __name__ == "__main__":
    if sys.argv[1:] == ["-"]:  # a text on stdin (publish.sh's commit message)
        try:
            scan_text("<stdin>", sys.stdin.read())
            sys.exit(1 if hits else 0)
        except Exception as e:
            print("publish_scan: failed on stdin: %s" % type(e).__name__, file=sys.stderr)
            sys.exit(3)
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        print("usage: publish_scan.py <tree> | -", file=sys.stderr)
        sys.exit(2)
    try:
        code = main(sys.argv[1])
    except Exception as e:  # a scan that did not finish is not a clean scan
        print("publish_scan: failed on %s: %s" % (current, type(e).__name__), file=sys.stderr)
        code = 3
    sys.exit(code)
