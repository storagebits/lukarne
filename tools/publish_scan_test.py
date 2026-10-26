#!/usr/bin/env python3
"""Planted cases for tools/publish_scan.py: each must fail the scan, each allowed form must pass.

    python3 -I tools/publish_scan_test.py
"""
import gzip
import io
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib

SCAN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "publish_scan.py")
# Built from pieces so this file does not hold the names it plants.
FAMILY = "Le" + "sage"
FIRST = "Fran" + "çois"
FIRST_ASCII = "Fran" + "cois"


def scan(files):
    """Writes {path: bytes|str} into a fresh tree and runs the scan: (exit code, output)."""
    with tempfile.TemporaryDirectory() as root:
        for rel, body in files.items():
            full = os.path.join(root, rel)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "wb") as f:
                f.write(body.encode("utf-8") if isinstance(body, str) else body)
        r = subprocess.run([sys.executable, "-I", SCAN, root], capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr


def png_with_text(key, text):
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 0, 0, 0, 0)
    idat = zlib.compress(b"\0\0")
    ztxt = key.encode("latin-1") + b"\0\0" + zlib.compress(text.encode("latin-1"))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"zTXt", ztxt) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


class Planted(unittest.TestCase):
    def assertHit(self, files, quiet=True):
        code, out = scan(files)
        self.assertEqual(code, 1, out)
        self.assertIn("HIT [owner-name]", out)
        if quiet:  # a hit in a path has to print that path to be found
            for name in (FAMILY, FIRST, FIRST_ASCII):
                self.assertNotIn(name.lower(), out.lower(), "the scan must not print the matched name")

    def assertClean(self, files):
        code, out = scan(files)
        self.assertEqual(code, 0, out)

    def test_family_name_any_case(self):
        for form in (FAMILY, FAMILY.upper(), FAMILY.lower(), "f" + FAMILY.lower()):
            self.assertHit({"LICENSE": "Copyright (c) 2026 %s\n" % form})

    def test_first_name_as_a_person(self):
        for line in ("Copyright %s\n" % FIRST, "asked by %s, 2026\n" % FIRST_ASCII, "%s's card\n" % FIRST,
                     "%s\n" % FIRST.upper(), "author: jean-%s\n" % FIRST_ASCII.lower()):
            self.assertHit({"DECISIONS.md": line})

    def test_decomposed_cedilla(self):
        self.assertHit({"README.md": "by Fran" + "c\u0327ois\n"})

    def test_utf16_text(self):
        self.assertHit({"app/notes.txt": ("by %s\n" % FIRST).encode("utf-16")})

    def test_in_a_path(self):
        self.assertHit({"docs/%s/readme.txt" % FAMILY.lower(): "nothing\n"}, quiet=False)

    def test_in_a_binary(self):
        self.assertHit({"app/blob.bin": b"\0\1\2" + ("author=%s" % FAMILY).encode() + b"\0\3"})

    def test_in_png_metadata(self):
        self.assertHit({"docs/screens/x.png": png_with_text("Author", "%s %s" % (FIRST_ASCII, FAMILY))})

    def test_place_exception_needs_a_word(self):
        for line in ("the first %s card\n" % FIRST, "test-%s\n" % FIRST_ASCII, "best %s\n" % FIRST):
            self.assertHit({"a.md": line})

    def test_escaped_forms(self):
        for tail in ("\\u00e7ois", "&ccedil;ois", "&#231;ois", "%C3%A7ois", "\\xe7ois"):
            line = '{"author": "Fran' + tail + '"}\n'
            self.assertHit({"app/x.json": line})

    def test_invisible_and_lookalike_characters(self):
        for word in ("L" + "e\u00adsage", "L" + "e\u200bsage", "\uff2c" + "\uff45\uff53\uff41\uff47\uff45"):
            self.assertHit({"a.md": "by %s\n" % word})

    def test_in_identifiers(self):
        for line in ("val author_%s = 1\n" % FIRST_ASCII.lower(), "%s2026\n" % FIRST_ASCII.lower(),
                     "class Author%s\n" % FIRST_ASCII):
            self.assertHit({"a.kt": line})

    def test_in_archives(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("META-INF/MANIFEST.MF", "Built-By: %s\n" % FAMILY)
        self.assertHit({"gradle/wrapper/x.jar": buf.getvalue()})
        self.assertHit({"x.gz": gzip.compress(("by %s\n" % FIRST).encode())})

    def test_other_encodings(self):
        self.assertHit({"a.bin": b"\0\1" + ("by %s" % FAMILY).encode("utf-16-be") + b"\0\2"})
        self.assertHit({"a.txt": ("by %s\n" % FIRST).encode("utf-32")})

    def test_symlink_target(self):
        with tempfile.TemporaryDirectory() as root:
            os.symlink("../%s/x" % FAMILY.lower(), os.path.join(root, "link"))
            r = subprocess.run([sys.executable, "-I", SCAN, root], capture_output=True, text=True)
            self.assertEqual(r.returncode, 1, r.stdout)

    def test_stdin_text(self):
        r = subprocess.run([sys.executable, "-I", SCAN, "-"], input="fix for %s\n" % FIRST, capture_output=True,
                           text=True)
        self.assertEqual(r.returncode, 1, r.stdout)
        r = subprocess.run([sys.executable, "-I", SCAN, "-"], input="v1.0.2\n", capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout)

    def test_place_names_pass(self):
        self.assertClean({"catalog.json": '{"name": "Saint-%s", "b": "St-%s", "c": "St. %s", "d": "Sainte %s"}\n'
                          % (FIRST, FIRST, FIRST_ASCII, FIRST)})

    def test_other_names_pass(self):
        self.assertClean({"a.md": "San Francisco, Françoise, francoisx, storagebits\n"})

    def test_empty_tree_passes(self):
        self.assertClean({"README.md": "Lukarne\n"})


if __name__ == "__main__":
    unittest.main()
