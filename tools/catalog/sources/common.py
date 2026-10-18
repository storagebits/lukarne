import gzip
import json
import math
import re
import sys
import time
import urllib.error
import urllib.request

UA = "lukarne-catalog/1.0 (personal Android TV app; contact via GitHub)"


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def fetch(url, headers=None, timeout=10, retries=1):
    """GET → (status, bytes). Never raises on HTTP errors; returns (0, b"") on network failure."""
    h = {"User-Agent": UA, "Accept-Encoding": "gzip"}
    h.update(headers or {})
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout) as r:
                body = r.read()
                if r.headers.get("Content-Encoding") == "gzip":
                    body = gzip.decompress(body)
                return r.status, body
        except urllib.error.HTTPError as e:
            try:
                body = e.read()
                if e.headers.get("Content-Encoding") == "gzip":
                    body = gzip.decompress(body)
                return e.code, body
            except Exception:
                return e.code, b""
        except Exception:
            if attempt < retries:
                time.sleep(1)
    return 0, b""


class HttpError(RuntimeError):
    def __init__(self, url, status, body=b""):
        super().__init__(f"{redact(url)} → HTTP {status}")
        self.status = status
        self.body = body.decode("utf-8", "replace")


def redact(url):
    """Never let an API key reach a log line."""
    return re.sub(r"(key=)[^&]+", r"\1…", url)


def fetch_json(url, headers=None, timeout=15):
    status, body = fetch(url, headers, timeout)
    if status != 200:
        raise HttpError(url, status, body)
    return json.loads(body)


def haversine_km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6371 * 2 * math.asin(math.sqrt(a))


def thin(cams, km, key=lambda c: 0):
    """Keeps the best-scoring cams (lowest key) at least `km` apart. Grid-bucketed: tens of thousands of cams."""
    cell = max(km / 111.0, 0.05)   # degrees; a cell is at least `km` tall
    cols = math.ceil(360 / cell)
    grid = {}
    kept = []
    for c in sorted(cams, key=key):
        gy, gx = int((c["lat"] + 90) // cell), int((c["lon"] + 180) // cell) % cols
        # A degree of longitude shrinks towards the poles: look as many cells sideways as `km` spans there.
        cos = math.cos(math.radians(min(abs(c["lat"]) + cell, 90)))
        span = cols if cos < 1e-3 else min(cols, int(1 / cos) + 1)
        cells = {(gy + dy, (gx + dx) % cols) for dy in (-1, 0, 1) for dx in range(-span, span + 1)}
        if all(haversine_km(c["lat"], c["lon"], k["lat"], k["lon"]) >= km for g in cells for k in grid.get(g, ())):
            kept.append(c)
            grid.setdefault((gy, gx), []).append(c)
    return kept


def hls_alive(url):
    """A live HLS stream answers its master playlist and a media playlist with at least one segment."""
    status, body = fetch(url, timeout=8)
    if status != 200 or not body.startswith(b"#EXTM3U"):
        return False
    text = body.decode("utf-8", "replace")
    if "#EXTINF" in text:
        return True
    variant = next((l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")), None)
    if not variant:
        return False
    vurl = variant if variant.startswith("http") else url.rsplit("/", 1)[0] + "/" + variant
    status, body = fetch(vurl, timeout=8)
    return status == 200 and b"#EXTINF" in body


def image_alive(url, headers=None, min_bytes=3000):
    status, body = fetch(url, headers=headers, timeout=10)
    return status == 200 and len(body) >= min_bytes
