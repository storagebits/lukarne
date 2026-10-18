#!/usr/bin/env python3
"""Builds the globe's geometry from Natural Earth (public domain), into app/src/main/assets:

  land_dots.bin  float32 LE triples (x, y, z): unit vectors of a Fibonacci sphere, kept where there is land
  coast.bin      int32 chunk count, then per chunk: int32 n, float32 (cx, cy, cz, cos_radius), n × (x, y, z)
                 Chunks of ≤ 48 vertices with a bounding cap, so the app culls whole chunks with one dot product.

    tools/catalog/.venv/bin/python -I tools/catalog/geo.py
Natural Earth files are downloaded once into tools/catalog/cache/ne/.
"""
import json
import math
import pathlib
import struct
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from sources.common import fetch, log  # noqa: E402

ROOT = HERE.parent.parent
ASSETS = ROOT / "app/src/main/assets"
NE = HERE / "cache" / "ne"
NE_URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/{}.geojson"
SPHERE_POINTS = 64_000     # ~18k land dots: ~6 px apart on the whole-globe view
CHUNK = 48


def load(name):
    f = NE / f"{name}.geojson"
    if not f.exists():
        NE.mkdir(parents=True, exist_ok=True)
        status, body = fetch(NE_URL.format(name), timeout=60)
        if status != 200:
            raise SystemExit(f"cannot download {name}: HTTP {status}")
        f.write_bytes(body)
    return json.loads(f.read_text())


def xyz(lon, lat):
    la, lo = math.radians(lat), math.radians(lon)
    return math.cos(la) * math.cos(lo), math.cos(la) * math.sin(lo), math.sin(la)


def polygons(geo):
    for feat in geo["features"]:
        g = feat["geometry"]
        polys = [g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"]
        for rings in polys:
            outer = rings[0]
            xs = [p[0] for p in outer]; ys = [p[1] for p in outer]
            yield (min(xs), min(ys), max(xs), max(ys)), rings


def inside(lon, lat, ring):
    hit = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i][:2]; xj, yj = ring[j][:2]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


def land_dots():
    polys = list(polygons(load("ne_50m_land")))
    # Bucket polygons by 10° latitude band for a quick prefilter.
    bands = {}
    for bbox, rings in polys:
        for b in range(int(math.floor(bbox[1] / 10)), int(math.floor(bbox[3] / 10)) + 1):
            bands.setdefault(b, []).append((bbox, rings))
    out = []
    golden = math.pi * (3 - math.sqrt(5))
    for i in range(SPHERE_POINTS):
        z = 1 - 2 * (i + 0.5) / SPHERE_POINTS
        r = math.sqrt(1 - z * z)
        th = golden * i
        x, y = r * math.cos(th), r * math.sin(th)
        lat = math.degrees(math.asin(z)); lon = math.degrees(math.atan2(y, x))
        for bbox, rings in bands.get(int(math.floor(lat / 10)), ()):
            if bbox[0] <= lon <= bbox[2] and bbox[1] <= lat <= bbox[3] and inside(lon, lat, rings[0]) \
                    and not any(inside(lon, lat, hole) for hole in rings[1:]):
                out.append((x, y, z))
                break
    return out


def coast_chunks():
    geo = load("ne_50m_coastline")
    chunks = []
    for feat in geo["features"]:
        g = feat["geometry"]
        lines = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"]
        for line in lines:
            pts = [xyz(p[0], p[1]) for p in line]
            for s in range(0, len(pts) - 1, CHUNK - 1):
                c = pts[s:s + CHUNK]
                if len(c) < 2:
                    continue
                cx, cy, cz = (sum(p[k] for p in c) for k in range(3))
                n = math.sqrt(cx * cx + cy * cy + cz * cz) or 1
                cx, cy, cz = cx / n, cy / n, cz / n
                cos_r = min(px * cx + py * cy + pz * cz for px, py, pz in c)
                chunks.append(((cx, cy, cz, cos_r), c))
    return chunks


def main():
    dots = land_dots()
    with open(ASSETS / "land_dots.bin", "wb") as f:
        for p in dots:
            f.write(struct.pack("<3f", *p))
    chunks = coast_chunks()
    with open(ASSETS / "coast.bin", "wb") as f:
        f.write(struct.pack("<i", len(chunks)))
        for cap, pts in chunks:
            f.write(struct.pack("<i4f", len(pts), *cap))
            for p in pts:
                f.write(struct.pack("<3f", *p))
    verts = sum(len(c[1]) for c in chunks)
    log(f"land_dots.bin: {len(dots)} dots; coast.bin: {len(chunks)} chunks, {verts} vertices")


if __name__ == "__main__":
    main()
