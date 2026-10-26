# lukarne-catalog

The webcam catalogue of **Lukarne**, an Android TV app that shows live webcams on a globe: one JSON file listing every cam the app can play, rebuilt every day by GitHub Actions.

## What it does

`catalog.json` is what Lukarne downloads at launch (at most once an hour) from
`https://raw.githubusercontent.com/storagebits/lukarne-catalog/main/catalog.json`.
The app keeps whichever is newer, this file or the copy built into the app, and keeps the last one it had when offline.
The file lives outside the app because YouTube live streams change their video id whenever a channel restarts its stream: a catalogue frozen in the app would go dark within days.

The file is generated. Do not edit it, and do not send pull requests that change it: the next daily run replaces it.

## Status

Stable. About 830 cams in 41 regions, from four sources: YouTube live streams, Caltrans (California) and DelDOT (Delaware) road cams, and Fintraffic Digitraffic weather cams (Finland).

- Every day at 04:17 UTC, a GitHub Actions workflow in the app's own repository (private) rebuilds the catalogue and pushes it here.
- YouTube is refreshed from known channels and the ids already in the catalogue, without search. Road cam streams are probed, so dead ones drop out.
- A source that comes back with less than half of its previous cams keeps the previous ones, so a failing source never empties the catalogue.
- Each run replaces the history with a single commit, so the repository stays one file deep. Links to older commits do not last.

## Getting started

Nothing to build. Read the file directly:

```sh
curl -sL https://raw.githubusercontent.com/storagebits/lukarne-catalog/main/catalog.json | python3 -m json.tool | head -40
```

### Format (`version` 1)

```json
{
  "version": 1,
  "generated": "2026-10-18T08:56:55Z",
  "regions": [ { "id": "california", "en": "California", "fr": "Californie", "es": "California",
                 "lat": 36.503, "lon": -120.1554, "tz": "America/Los_Angeles", "cams": 302, "live": 302 } ],
  "places":  [ { "id": "south-us/new-orleans", "region": "south-us", "name": "New Orleans",
                 "lat": 29.9584, "lon": -90.0651, "tz": "America/Chicago", "cams": 2, "live": 2 } ],
  "cams":    [ { "id": "yt:xqdukYhcGEU", "source": "youtube", "kind": "youtube", "stream": "xqdukYhcGEU",
                 "thumb": "https://i.ytimg.com/vi/xqdukYhcGEU/hqdefault_live.jpg",
                 "lat": 29.9584, "lon": -90.0651, "place": "Bourbon Street", "locality": "New Orleans",
                 "country": "US", "tz": "America/Chicago", "cluster": "south-us/new-orleans" } ]
}
```

| Key | Meaning |
|---|---|
| `version` | Format version, `1`. A breaking change gets a new number. |
| `generated` | When the file was built, ISO 8601 in UTC. The app compares it with its built-in copy and keeps the newer. |
| `regions[]` | The first zoom level on the globe. `id`, names in English, French and Spanish (`en`, `fr`, `es`), centre (`lat`, `lon`), IANA time zone (`tz`), number of cams (`cams`) and of live cams (`live`). |
| `places[]` | Clusters of nearby cams inside a region. `id` is `<region>/<slug>`, `region` points to `regions[].id`, then `name`, centre, `tz`, `cams` and `live` as above. |
| `cams[]` | One entry per cam. `id` is unique and prefixed by its source (`yt:`, `caltrans:`, …). `source` is `youtube`, `caltrans`, `deldot` or `digitraffic`. |
| `cams[].kind` | How to play it: `youtube` (`stream` is a YouTube video id), `hls` (`stream` is an HLS playlist URL) or `image` (`stream` is a JPEG URL refreshed by its source every few minutes; not counted as live). |
| `cams[].thumb` | Optional preview image URL. |
| `cams[].place`, `locality`, `country` | The cam's own title, its town and its ISO 3166-1 alpha-2 country code. |
| `cams[].lat`, `lon`, `tz` | Position and IANA time zone, used for day and night on the globe. |
| `cams[].cluster` | The `places[].id` the cam belongs to. |

## Configuration

None in this repository. The build pipeline and its settings (sources, curated YouTube channels, regions) live in the app's repository. The daily workflow uses two GitHub Actions secrets there: a YouTube Data API key and a deploy key that may write to this repository.

## Licence and credits

The catalogue's structure and the curation work are released under the [MIT licence](LICENSE). The data about each cam comes from its source and stays under that source's terms:

- [Fintraffic Digitraffic](https://www.digitraffic.fi/) weather cameras: CC BY 4.0. Credit "Fintraffic / digitraffic.fi, CC BY 4.0" wherever their images or data are shown.
- [Caltrans](https://cwwp2.dot.ca.gov/) (California Department of Transportation) and [DelDOT](https://deldot.gov/) (Delaware Department of Transportation) public camera feeds.
- YouTube live streams belong to their channels and must be played in YouTube's own player, under the [YouTube Terms of Service](https://www.youtube.com/t/terms).

No Windy cam is included: Windy's terms forbid redistributing what its API returns.
