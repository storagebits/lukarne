"""DelDOT (Delaware DOT) traffic cameras: public 720p HLS, keyless JSON catalogue used by deldot.gov's own map.
https://tmc.deldot.gov/json/videocamera.json
"""
from concurrent.futures import ThreadPoolExecutor

from .common import fetch_json, haversine_km, hls_alive, log, thin

# The feed has no town: the nearest of these names the cam (and so the place on the globe).
TOWNS = [
    ("Wilmington", 39.7391, -75.5398), ("Newark", 39.6837, -75.7497), ("New Castle", 39.6620, -75.5663),
    ("Middletown", 39.4496, -75.7163), ("Smyrna", 39.2998, -75.6046), ("Dover", 39.1582, -75.5244),
    ("Harrington", 38.9237, -75.5777), ("Milford", 38.9126, -75.4277), ("Lewes", 38.7746, -75.1393),
    ("Rehoboth Beach", 38.7209, -75.0760), ("Georgetown", 38.6901, -75.3855), ("Seaford", 38.6412, -75.6110),
    ("Millsboro", 38.5915, -75.2913), ("Laurel", 38.5565, -75.5713), ("Bethany Beach", 38.5396, -75.0552),
    ("Fenwick Island", 38.4626, -75.0513), ("Selbyville", 38.4604, -75.2205),
]
# Mostly intersections: prefer the coast, bridges and water.
SCENIC = ["beach", "rehoboth", "lewes", "inlet", "bridge", "bethany", "dewey", "fenwick", "canal", "river", "ferry",
          "bay", "the green", "riverfront", "market st", "kings hwy", "coastal"]
MAX_CAMS = 100
MIN_KM = 1.5


def _town(lat, lon):
    return min(TOWNS, key=lambda t: haversine_km(lat, lon, t[1], t[2]))[0]


def _name(title):
    """'US 9 (KINGS HWY) @ GILLS NECK' → 'US 9 (Kings Hwy) @ Gills Neck'."""
    words = []
    for w in title.split():
        words.append(w if any(ch.isdigit() for ch in w) or w in ("US", "DE", "I", "SR", "@", "NB", "SB") else w.capitalize())
    return " ".join(words).replace("(kings", "(Kings")


def score(c):
    return 0 if any(k in c["_title"].lower() for k in SCENIC) or c["lon"] > -75.2 else 1


def collect(verify=True):
    try:
        rows = fetch_json("https://tmc.deldot.gov/json/videocamera.json")["videoCameras"]
    except Exception as e:
        log(f"  deldot: {e}")
        return []
    cams = []
    for r in rows:
        url = (r.get("urls") or {}).get("m3u8s", "")
        if not r.get("enabled") or r.get("status") != "Active" or not url.startswith("https://"):
            continue
        if r["title"].lower().startswith("portable camera"):
            continue
        lat, lon = float(r["lat"]), float(r["lon"])
        town = _town(lat, lon)
        cams.append({
            "id": f"deldot:{r['id']}", "source": "deldot", "lat": lat, "lon": lon,
            "place": f"{_name(r['title'])}, {town}", "locality": town, "country": "US", "tz": "America/New_York",
            "kind": "hls", "stream": url.replace(":443/", "/"), "thumb": None, "_title": r["title"],
        })
    log(f"  deldot: {len(cams)} active cams")
    picked = sorted(thin(cams, MIN_KM, key=score), key=score)[: MAX_CAMS * 2]
    if verify:
        with ThreadPoolExecutor(16) as ex:
            ok = list(ex.map(lambda c: hls_alive(c["stream"]), picked))
        log(f"  deldot: {sum(ok)}/{len(picked)} streams answer")
        picked = [c for c, alive in zip(picked, ok) if alive]
    for c in picked:
        del c["_title"]
    return picked[:MAX_CAMS]
