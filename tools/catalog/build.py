#!/usr/bin/env python3
"""Builds app/src/main/assets/catalog.json: every webcam lukarne can show, verified, geolocated, clustered.

    tools/catalog/build.sh                       # all sources whose keys are present
    tools/catalog/build.sh --only caltrans       # one source (others are kept from the previous catalogue)
    tools/catalog/build.sh --dead dead.json      # also drop cams the Shield reported dead (adb pull, see README)
    tools/catalog/build.sh --only none           # no network: re-cluster the previous catalogue (regions edited)
    tools/catalog/build.sh --only youtube --no-search   # the daily CI refresh (.github/workflows/catalog.yml)

The key comes from local.properties (youtube.apiKey) or YOUTUBE_API_KEY.
Sources without a key are skipped, never faked. Output schema: see app/…/data/Catalog.kt.
"""
import argparse
import collections
import datetime as dt
import json
import math
import os
import pathlib
import sys
from concurrent.futures import ThreadPoolExecutor

from timezonefinder import TimezoneFinder

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))

import regions  # noqa: E402
from sources import caltrans, deldot, digitraffic, youtube  # noqa: E402
from sources.common import haversine_km, log  # noqa: E402

OUT = ROOT / "app/src/main/assets/catalog.json"
PLACE_KM = 40          # cams closer than this to a place's seed join that place
DUP_KM = 0.5          # a still this close to a live cam is assumed to be the same camera
MIN_REGION_CAMS = 3    # thinner regions are merged into their neighbours


def local_props():
    props = {}
    f = ROOT / "local.properties"
    if f.exists():
        for line in f.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    return props


def assign_regions(cams):
    active = list(regions.REGIONS)
    while True:
        counts = collections.Counter()
        kept = []
        for c in cams:
            best = min(active, key=lambda r: haversine_km(c["lat"], c["lon"], r[3], r[4]))
            if haversine_km(c["lat"], c["lon"], best[3], best[4]) > regions.MAX_KM:
                continue
            c["_region"] = best[0]
            counts[best[0]] += 1
            kept.append(c)
        live = collections.Counter(c["_region"] for c in kept if c["kind"] != "image")
        # A region with a live cam always stays: those are the reason to visit it.
        thin = [r for r in active if 0 < counts[r[0]] < MIN_REGION_CAMS and not live[r[0]]]
        active = [r for r in active if counts[r[0]] >= MIN_REGION_CAMS or live[r[0]]]
        if not thin:
            return kept, active


def spherical_mean(points):
    x = y = z = 0.0
    for lat, lon in points:
        la, lo = math.radians(lat), math.radians(lon)
        x += math.cos(la) * math.cos(lo); y += math.cos(la) * math.sin(lo); z += math.sin(la)
    n = len(points)
    x, y, z = x / n, y / n, z / n
    return round(math.degrees(math.atan2(z, math.hypot(x, y))), 4), round(math.degrees(math.atan2(y, x)), 4)


def slug(s):
    out = "".join(ch.lower() if ch.isalnum() else "-" for ch in s)
    return "-".join(p for p in out.split("-") if p)[:40] or "x"


def cluster_places(cams):
    """Greedy clustering inside each region. Live cams seed places first so a place is named after them."""
    places = []
    by_region = collections.defaultdict(list)
    for c in cams:
        by_region[c["_region"]].append(c)
    for region, rc in by_region.items():
        rc.sort(key=lambda c: (c["kind"] == "image", c["source"]))
        seeds = []  # (lat, lon, [cams])
        for c in rc:
            near = [s for s in seeds if haversine_km(c["lat"], c["lon"], s[0], s[1]) < PLACE_KM]
            if near:
                min(near, key=lambda s: haversine_km(c["lat"], c["lon"], s[0], s[1]))[2].append(c)
            else:
                seeds.append((c["lat"], c["lon"], [c]))
        used = set()
        for _, _, members in seeds:
            # Named after its live cams when it has some, and never "unknown" (a placeholder locality).
            named = [m for m in members if (m.get("locality") or "").strip().lower() not in ("", "unknown")]
            live_named = [m for m in named if m["kind"] != "image"]
            name = collections.Counter(m["locality"] for m in (live_named or named)).most_common(1)
            name = name[0][0] if name else members[0]["place"]
            pid = f"{region}/{slug(name)}"
            n = 2
            while pid in used:
                pid = f"{region}/{slug(name)}-{n}"; n += 1
            used.add(pid)
            lat, lon = spherical_mean([(m["lat"], m["lon"]) for m in members])
            tz = collections.Counter(m["tz"] for m in members).most_common(1)[0][0]
            places.append({"id": pid, "region": region, "name": name, "lat": lat, "lon": lon, "tz": tz,
                           "cams": len(members), "live": sum(1 for m in members if m["kind"] != "image")})
            for m in members:
                m["cluster"] = pid
    return places


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", action="append", help="rebuild only these sources")
    ap.add_argument("--dead", help="dead.json pulled from the device: drop cams dead there")
    ap.add_argument("--no-verify", action="store_true", help="skip liveness checks (offline dev)")
    ap.add_argument("--no-search", action="store_true",
                    help="YouTube: no search.list; re-check the previous catalogue's ids instead (daily CI run)")
    args = ap.parse_args()

    props = local_props()
    keys = {
        "youtube": os.environ.get("YOUTUBE_API_KEY") or props.get("youtube.apiKey", ""),
    }
    adapters = {
        "youtube": lambda: youtube.collect(HERE / "youtube_places.json", keys["youtube"], verify=not args.no_verify,
                                           channels_file=HERE / "youtube_channels.json", search=not args.no_search,
                                           previous=[c for c in previous if c["source"] == "youtube"]),
        "caltrans": lambda: caltrans.collect(verify=not args.no_verify),
        "deldot": lambda: deldot.collect(verify=not args.no_verify),
        "digitraffic": lambda: digitraffic.collect(verify=not args.no_verify),
    }

    previous = []
    if OUT.exists():
        previous = json.loads(OUT.read_text())["cams"]

    cams = []
    wanted = set(args.only or adapters)
    for name, fn in adapters.items():
        if name not in wanted:
            kept = [c for c in previous if c["source"] == name]
            log(f"{name}: kept {len(kept)} from the previous catalogue")
            cams += kept
            continue
        got = fn()
        if got is None:
            kept = [c for c in previous if c["source"] == name]
            log(f"{name}: no API key, kept {len(kept)} from the previous catalogue")
            cams += kept
            continue
        kept = [c for c in previous if c["source"] == name]
        if len(got) < len(kept) // 2:
            # A rate-limited or half-down source must not wipe what worked last time.
            log(f"{name}: only {len(got)} cams vs {len(kept)} before — keeping the previous ones")
            cams += kept
            continue
        log(f"{name}: {len(got)} cams")
        cams += got

    if args.dead:
        dead = json.loads(pathlib.Path(args.dead).read_text())
        before = len(cams)
        cams = [c for c in cams if c["id"] not in dead and f"yt:{c['stream']}" not in dead]
        log(f"dropped {before - len(cams)} cams reported dead by the device")

    # De-duplicate (the same YouTube id can come from two places' searches).
    seen, uniq = set(), []
    for c in cams:
        if c["id"] not in seen:
            seen.add(c["id"]); uniq.append(c)
    cams = uniq

    # A still next to a live cam is the same camera (Caltrans publishes both): noise.
    live_pts = [(c["lat"], c["lon"]) for c in cams if c["kind"] != "image"]
    before = len(cams)
    cams = [c for c in cams if c["kind"] != "image"
            or all(haversine_km(c["lat"], c["lon"], la, lo) > DUP_KM for la, lo in live_pts)]
    log(f"dropped {before - len(cams)} stills duplicating a live cam")

    tf = TimezoneFinder()
    for c in cams:
        c["tz"] = c.get("tz") or tf.timezone_at(lat=c["lat"], lng=c["lon"]) or "UTC"

    cams, active = assign_regions(cams)
    places = cluster_places(cams)
    region_rows = []
    for rid, en, fr, _, _ in active:
        members = [(c["lat"], c["lon"]) for c in cams if c["_region"] == rid]
        lat, lon = spherical_mean(members)
        tz = collections.Counter(c["tz"] for c in cams if c["_region"] == rid).most_common(1)[0][0]
        region_rows.append({"id": rid, "en": en, "fr": fr, "es": regions.ES.get(rid, en), "lat": lat, "lon": lon, "tz": tz,
                            "cams": len(members),
                            "live": sum(1 for c in cams if c["_region"] == rid and c["kind"] != "image")})

    fields = ["id", "source", "lat", "lon", "place", "country", "tz", "kind", "stream", "thumb", "cluster",
              "locality"]
    generated = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    def write(path, members):
        # Places and regions keep the geometry clustered over every cam; their counts are those of [members].
        at = collections.Counter(c["cluster"] for c in members)
        live = collections.Counter(c["cluster"] for c in members if c["kind"] != "image")
        ps = [{**p, "cams": at[p["id"]], "live": live[p["id"]]} for p in places if at[p["id"]]]
        rc = collections.Counter(); rl = collections.Counter()
        for p in ps:
            rc[p["region"]] += p["cams"]; rl[p["region"]] += p["live"]
        rs = [{**r, "cams": rc[r["id"]], "live": rl[r["id"]]} for r in region_rows if rc[r["id"]]]
        out = {
            "version": 1,
            "generated": generated,
            "regions": rs,
            "places": ps,
            "cams": [{k: c[k] for k in fields if c.get(k) not in (None, "")} for c in members],
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")))
        return len(rs), len(ps)

    n_regions, n_places = write(OUT, cams)

    by_kind = collections.Counter(c["kind"] for c in cams)
    by_src = collections.Counter(c["source"] for c in cams)
    log(f"\nwrote {OUT.relative_to(ROOT)}: {len(cams)} cams {dict(by_kind)} {dict(by_src)}, {n_regions} regions, "
        f"{n_places} places")
    for r in sorted(region_rows, key=lambda r: -r["cams"]):
        log(f"  {r['en']:<32} {r['cams']:>4} cams  {r['live']:>3} live")


if __name__ == "__main__":
    main()
