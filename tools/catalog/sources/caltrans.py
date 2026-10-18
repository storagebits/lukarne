"""Caltrans CWWP2: California DOT cameras, public HLS + JPEG, documented open data, no key.
https://cwwp2.dot.ca.gov/documentation/cctv/cctv.htm
"""
import re
from concurrent.futures import ThreadPoolExecutor

from .common import fetch_json, hls_alive, log, thin

# Freeway cams are plentiful; prefer the ones that show a place worth visiting.
SCENIC = ["bridge", "golden gate", "bay", "lake", "tahoe", "donner", "summit", "pass", "beach", "coast", "pier",
          "harbor", "yosemite", "big sur", "sr-1 ", "hwy 1 ", "pch", "mt ", "mount", "river", "vista", "ocean",
          "shasta", "kingsbury", "echo", "sierra", "ridge", "canyon", "grapevine", "cajon", "palm springs",
          "bodega", "mendocino", "eureka", "crescent city", "monterey", "santa cruz", "half moon", "malibu",
          "santa barbara", "san luis", "mammoth", "bishop", "tioga", "sonora", "ebbetts", "carson"]
MAX_CAMS = 300
MIN_KM = 4


CODES = re.compile(r"\b([NSEW]B|[NSEW]O|JWO|JEO|JNO|JSO|OC|UC|Onr|Offr|IC|[A-Z]{2,3}\d{1,3}|TV\d+|C \d+)\b")


def clean_name(name):
    """'(C 348) SR-163 : Friars N/E_B' → 'SR-163 Friars'; 'Bass_Lake_ED50_WB' → 'Bass Lake'."""
    n = re.sub(r"\(.*?\)", " ", name).replace("_", " ").replace(" : ", " ").replace("--", " ")
    n = CODES.sub(" ", n)
    n = re.sub(r"\b[NSEW]/[NSEW]\s*B\b|\b[NSEW]/[NSEW]\b", " ", n)
    return re.sub(r"\s+", " ", n).strip(" -,:")


def score(c):
    text = (c["place"] + " " + c["locality"]).lower()
    return 0 if any(k in text for k in SCENIC) else 1


def collect(verify=True):
    cams = []
    for d in range(1, 13):
        try:
            data = fetch_json(f"https://cwwp2.dot.ca.gov/data/d{d}/cctv/cctvStatusD{d:02d}.json")["data"]
        except Exception as e:  # one district down must not sink the run
            log(f"  caltrans d{d}: {e}")
            continue
        for row in data:
            c = row["cctv"]
            img, loc = c["imageData"], c["location"]
            url = img.get("streamingVideoURL", "")
            if c.get("inService") != "true" or not url.startswith("https://"):
                continue
            try:
                lat, lon = float(loc["latitude"]), float(loc["longitude"])
            except (TypeError, ValueError):
                continue
            name = clean_name(loc["locationName"].split(" -- ")[-1])
            locality = (loc.get("nearbyPlace") or loc.get("county") or "").strip()
            cams.append({
                # Stable across runs: the stream path, e.g. caltrans:D3/5_Pocket_Rd_OC_SAC5_SB
                "id": "caltrans:" + url.split("wzmedia.dot.ca.gov/", 1)[-1].removesuffix("/playlist.m3u8")
                .removesuffix(".stream"),
                "source": "caltrans", "lat": lat, "lon": lon,
                "place": f"{name}, {locality}" if locality and locality.lower() not in name.lower() else name,
                "locality": locality, "country": "US", "tz": "America/Los_Angeles",
                "kind": "hls", "stream": url,
                "thumb": img.get("static", {}).get("currentImageURL") or None,
            })
    log(f"  caltrans: {len(cams)} streaming cams in service")
    picked = thin(cams, MIN_KM, key=score)
    picked.sort(key=score)
    picked = picked[: MAX_CAMS * 2]
    if verify:
        with ThreadPoolExecutor(16) as ex:
            ok = list(ex.map(lambda c: hls_alive(c["stream"]), picked))
        log(f"  caltrans: {sum(ok)}/{len(picked)} streams answer")
        picked = [c for c, alive in zip(picked, ok) if alive]
    return picked[:MAX_CAMS]
