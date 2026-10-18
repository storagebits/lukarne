"""YouTube lives, geolocated by hand-curated places (youtube_places.json) and channels (youtube_channels.json).

Channels: 24/7 webcam channels list their recent uploads (1 unit per 50, no search quota); each live video is
placed by a title rule, and titles no rule knows are left out.

With a Data API key: each place's query runs one live search (100 units; results cached for a week so a rebuild
costs ~nothing), then videos.list (1 unit per 50 ids) confirms every id is live, public and embeddable.
Without a key: ids come only from the curated file and are checked with oEmbed (public + embeddable) and the
live thumbnail. The app never calls the Data API.
"""
import collections
import json
import pathlib
import re
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

from .common import HttpError, fetch, fetch_json, log

API = "https://www.googleapis.com/youtube/v3"
CACHE = pathlib.Path(__file__).resolve().parent.parent / "cache" / "youtube_search.json"
SEARCH_TTL_S = 7 * 86400
PER_PLACE = 3


def _ids(place):
    for x in place.get("ids", []):
        yield (x, None) if isinstance(x, str) else (x["id"], x.get("place"))


class QuotaSpent(Exception):
    pass


def _api_message(body):
    try:
        return json.loads(body)["error"]["message"][:200]
    except (ValueError, KeyError, TypeError):
        return body[:200]


def _search(place, key, cache):
    q = place.get("q")
    if not q:
        return []
    hit = cache.get(q)
    if hit and time.time() - hit["at"] < SEARCH_TTL_S:
        return hit["ids"]
    # No safeSearch/videoEmbeddable filters: with them the API answered 200 with zero live results (2026-10-09).
    # Embeddability is checked by videos.list below.
    params = {"part": "snippet", "type": "video", "eventType": "live", "maxResults": 10, "q": q, "key": key}
    items = None
    for attempt in range(4):
        time.sleep(1.0 if attempt == 0 else 5.0 * attempt)  # a fresh key answers 429 to bursts
        try:
            items = fetch_json(f"{API}/search?{urllib.parse.urlencode(params)}")["items"]
            break
        except HttpError as e:
            # A spent daily quota comes back as 403 *or* 429 ("Quota exceeded for quota metric 'Search Queries'"):
            # stop searching at once instead of backing off on every place.
            if e.status == 403 or "quota" in e.body.lower():
                raise QuotaSpent(_api_message(e.body)) from e
            if e.status != 429:
                log(f"  youtube search '{q}': {e}")
                return []
    if items is None:
        log(f"  youtube search '{q}': still rate-limited, skipped")
        return []
    ids = [it["id"]["videoId"] for it in items
           if _mentions(_tokens(place), it["snippet"]["title"] + " " + it["snippet"]["description"])]
    if items:  # an empty answer is suspicious (filters, outage): retry next run instead of caching it
        cache[q] = {"at": time.time(), "ids": ids}
    return ids


def _tokens(place):
    return place.get("match", [place["locality"]])


def _mentions(tokens, text):
    """True when [text] names one of [tokens] as a whole word: "como" is not in "Comodoro"."""
    t = text.lower()
    return any(re.search(rf"(?<!\w){re.escape(w.lower())}(?!\w)", t) for w in tokens)


def _live_with_api(ids, key):
    """{id: title + description} of the [ids] that are live, public and embeddable."""
    alive = {}
    for i in range(0, len(ids), 50):
        chunk = ids[i:i + 50]
        params = {"part": "snippet,status", "id": ",".join(chunk), "key": key, "maxResults": 50}
        for it in fetch_json(f"{API}/videos?{urllib.parse.urlencode(params)}").get("items", []):
            sn, st = it["snippet"], it["status"]
            if sn.get("liveBroadcastContent") == "live" and st.get("embeddable") and st.get("privacyStatus") == "public":
                alive[it["id"]] = sn["title"] + "\n" + sn.get("description", "")
    return alive


def _live_without_api(vid):
    o, _ = fetch(f"https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v={vid}")
    if o != 200:
        return False
    t, body = fetch(f"https://i.ytimg.com/vi/{vid}/hqdefault_live.jpg")
    return t == 200 and len(body) > 2000


GENERIC = {"live", "webcam", "cam", "camera", "crossing", "beach", "city", "street", "square", "harbour", "harbor",
           "port", "airport", "view", "the", "and", "from", "with", "plaza", "park", "bridge", "river", "lake", "bay"}


def _label(place, title):
    """A searched video gets the spot's name only when its title names that spot; otherwise the town's.

    The query is often the town ("London live webcam" for Abbey Road Crossing): it finds other London streams
    (Heathrow, Piccadilly) that must not be labelled Abbey Road.
    """
    if title is None:
        return place["place"]
    words = [w for w in place["place"].lower().replace(",", " ").split() if len(w) >= 4 and w not in GENERIC]
    t = title.lower()
    if not words or any(w in t for w in words):
        return place["place"]
    return place["locality"]


def _channel_lives(channel, key):
    """[(video id, title)] of the live, public, embeddable videos among a channel's recent uploads (no search)."""
    uploads = "UU" + channel["id"][2:]
    ids, token = [], None
    for _ in range(channel.get("pages", 2)):
        params = {"part": "contentDetails", "playlistId": uploads, "maxResults": 50, "key": key}
        if token:
            params["pageToken"] = token
        r = fetch_json(f"{API}/playlistItems?{urllib.parse.urlencode(params)}")
        ids += [i["contentDetails"]["videoId"] for i in r.get("items", [])]
        token = r.get("nextPageToken")
        if not token:
            break
    return list(_live_with_api(ids, key).items())


def _channel_candidates(channels_file, key):
    """Live videos of curated 24/7 webcam channels, placed by the title rules in youtube_channels.json."""
    if not key or not pathlib.Path(channels_file).exists():
        return []
    out = []
    for ch in json.loads(pathlib.Path(channels_file).read_text())["channels"]:
        try:
            lives = _channel_lives(ch, key)
        except HttpError as e:
            log(f"  youtube channel {ch['channel']}: {e}")
            continue
        used = collections.Counter()
        placed = 0
        for vid, title in lives:
            t = title.lower()
            if _mentions(ch.get("skip", []), t):
                continue
            for i, rule in enumerate(ch["cams"]):
                if _mentions(rule["match"], t):
                    if used[i] < rule.get("max", 1):
                        used[i] += 1
                        placed += 1
                        out.append((rule, vid, rule["place"]))
                    break
        log(f"  youtube channel {ch['channel']}: {placed} placed / {len(lives)} live")
    return out


def collect(places_file, key, verify=True, channels_file=None, search=True, previous=()):
    """search=False (the daily CI run): no search.list at all. Searched spots then come from the [previous]
    catalogue's YouTube cams instead, re-checked like every other id (1 unit per 50)."""
    places = json.loads(pathlib.Path(places_file).read_text())
    cache = json.loads(CACHE.read_text()) if CACHE.exists() else {}
    # Channel lives first: their place comes from a curated title rule, more precise than a town search.
    candidates = _channel_candidates(channels_file, key) if channels_file else []  # (place, vid, label)
    quota_left = True
    for p in places:
        found = [(v, lbl) for v, lbl in _ids(p)]
        if key and search and quota_left:
            try:
                found += [(v, None) for v in _search(p, key, cache) if v not in {f[0] for f in found}]
            except QuotaSpent as e:
                quota_left = False
                log(f"  youtube: search quota spent ({e}); the rest comes from the cache on the next run")
        elif key and search:
            found += [(v, None) for v in cache.get(p.get("q"), {}).get("ids", []) if v not in {f[0] for f in found}]
        candidates += [(p, v, lbl) for v, lbl in found[:PER_PLACE + len(p.get("ids", []))]]
    by_spot = {(p["locality"], p["country"]): p for p in places}
    if not search:
        # A searched cam kept from the previous catalogue is checked again against its spot's words below.
        candidates += [(c, c["stream"], c["place"]) for c in previous]
    if key and search:
        CACHE.parent.mkdir(exist_ok=True)
        CACHE.write_text(json.dumps(cache, indent=1))

    ids = list(dict.fromkeys(v for _, v, _ in candidates))
    titles = {}
    if not verify:
        alive = set(ids)
    elif key:
        titles = _live_with_api(ids, key)
        alive = set(titles)
    else:
        with ThreadPoolExecutor(12) as ex:
            ok = list(ex.map(_live_without_api, ids))
        alive = {v for v, a in zip(ids, ok) if a}
    log(f"  youtube: {len(alive)}/{len(ids)} candidate ids are live and embeddable"
        + ("" if key else " (no API key: oEmbed + live thumbnail check)"))

    cams, seen = [], set()
    for p, vid, label in candidates:
        if vid not in alive or vid in seen:
            continue
        # A searched video (no curated label) must name its spot as a whole word, and so must a searched cam kept
        # from the previous catalogue (daily run): pinned ids are trusted.
        kept = p.get("source") == "youtube"
        spot = by_spot.get((p["locality"], p["country"])) if kept else p
        text = titles.get(vid)
        if ((label is None or kept) and spot is not None and text is not None
                and vid not in {v for v, _ in _ids(spot)} and not _mentions(_tokens(spot), text)):
            log(f"  youtube: {vid} dropped, its title doesn't name {spot['place']}")
            continue
        seen.add(vid)
        cams.append({
            "id": f"yt:{vid}", "source": "youtube",
            "lat": p["lat"], "lon": p["lon"],
            "place": label or _label(p, text and text.split("\n", 1)[0]), "locality": p["locality"], "country": p["country"],
            "kind": "youtube", "stream": vid,
            "thumb": f"https://i.ytimg.com/vi/{vid}/hqdefault_live.jpg",
        })
    return cams
