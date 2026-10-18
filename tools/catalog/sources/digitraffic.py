"""Digitraffic (Fintraffic): Finnish road-weather cameras, JPEG every ~10 min, CC BY 4.0, no key.
https://www.digitraffic.fi/en/road-traffic/
"""
from concurrent.futures import ThreadPoolExecutor

from .common import fetch_json, image_alive, log, thin

HDR = {"Digitraffic-User": "lukarne"}
MAX_CAMS = 150
MIN_KM = 12


def collect(verify=True):
    data = fetch_json("https://tie.digitraffic.fi/api/weathercam/v1/stations", HDR)
    cams = []
    for f in data["features"]:
        p = f["properties"]
        presets = [x for x in p.get("presets", []) if x.get("inCollection")]
        if p.get("collectionStatus") != "GATHERING" or not presets:
            continue
        lon, lat = f["geometry"]["coordinates"][:2]
        locality = p["name"].split("_", 1)[-1].replace("_", " ").strip()
        cams.append({
            "id": f"digitraffic:{presets[0]['id']}", "source": "digitraffic", "lat": lat, "lon": lon,
            "place": locality, "locality": locality, "country": "FI", "tz": "Europe/Helsinki",
            "kind": "image", "stream": f"https://weathercam.digitraffic.fi/{presets[0]['id']}.jpg",
        })
    picked = thin(cams, MIN_KM)[: MAX_CAMS * 2]
    if verify:
        with ThreadPoolExecutor(12) as ex:
            ok = list(ex.map(lambda c: image_alive(c["stream"], HDR), picked))
        picked = [c for c, alive in zip(picked, ok) if alive]
    return picked[:MAX_CAMS]
