# lukarne — decision log

Format: **context → options → choice → why**. Newest entries at the bottom of each section.

---

## 0. Device and connection (2026-10-09)

| Property | Value |
|---|---|
| Device | NVIDIA SHIELD Android TV, Android 11 (API 30), 1920×1080 @ density 320 → **960×540 dp** |
| RAM | 3 GB total, ~1 GB `MemAvailable` at idle |
| Video decoders | `OMX.Nvidia.h264.decode`: up to 3840×2176, **2 000 000 blocks/s** (≈ 8 × 1080p30 or ≈ 70 × 360p30 by throughput). No `concurrent-instances` limit declared, so the real number must be measured (see slice 3). |
| ADB address | `<shield-ip>:5555` on the LAN, given to the scripts as `LUKARNE_DEVICE` |

**macOS "Local Network" privacy blocks adb.** On this Mac, `adb connect <shield-ip>:5555` fails with
`No route to host`, and Homebrew Python fails the same way, while Apple-signed `/usr/bin/nc` connects fine.
That is the macOS 15+ Local Network permission, which the detached adb daemon doesn't have.
- **Options:** (a) ask for the permission to be granted in System Settings → Privacy → Local Network;
  (b) relay through `nc` on loopback.
- **Choice:** (b), as `tools/adb-relay.sh` (`127.0.0.1:15555 → shield:5555`). `tools/deploy.sh` starts it
  when a direct connect fails. (a) remains the cleaner fix and the README says how.

## 1. Webcam sources (investigation, ~30 min)

| Source | What it gives | Live? | Key | Verdict |
|---|---|---|---|---|
| **Windy Webcams API v3** | Largest geolocated catalogue (tens of thousands), categories, preview images | No: images + timelapse player | Free key (`x-windy-api-key`) | **Use for catalogue + snapshot tiles.** Free-tier image URLs carry a token that **expires after 10 min** (HTTP 401 after), images are low-res, offset is capped. Attribution is required. So image URLs are **never stored** in the catalogue: the app re-fetches them at runtime (`/webcams/{id}?include=images`) for the cams on screen. |
| **YouTube Live** | The largest pool of *true* live scenic cams (cities, beaches, wildlife, ISS) | Yes | Data API v3 key (only in the offline pipeline) | **Use for live.** Search costs 100 units of a 10 000/day quota, so it runs **only in the batch pipeline**, never in the app. `videos.list` (1 unit per 50 ids) re-checks liveness. Playback goes through the official IFrame player in a WebView (`android-youtube-player` 13.0.0), as YouTube's terms require. The mosaic shows YouTube's own live thumbnail (`i.ytimg.com/vi/<id>/hqdefault_live.jpg`, refreshed by YouTube every few minutes) instead of running players. |
| **Caltrans CWWP2** (California DOT) | 1 179 cams with **public HLS** (`wzmedia.dot.ca.gov/…/playlist.m3u8`, 640×480 or 720p, 125–410 kb/s) + a JPEG per cam, documented open JSON | Yes | None | **Use.** Media3 plays it natively; the low bitrate suits multi-tile live. Traffic cams are less "travel" than scenic, so the pipeline keeps a curated subset (coast, mountains, cities) to avoid drowning the globe in freeways. |
| **Québec 511** (MTMD) | Open-data camera list (WFS/GeoJSON) | Video page behind a **Cloudflare JS challenge** | — | **Rejected.** The video lives behind `quebec511.info/…/FenetreVideo.html`, which only answers to a browser that passes the challenge. Scraping around it is not acceptable. |
| **Digitraffic (Finland)** weathercams | 813 road-weather stations, JPEG every ~10 min, CC BY 4.0, no key | No (snapshots) | None (`Digitraffic-User` header) | **Use sparingly** as a snapshot source: Nordic landscapes, good under the "image refreshed" tile type. |
| SkylineWebcams, EarthCam, webcamtaxi… | Popular aggregators | Yes | — | **Rejected:** no public API, no embed licence. Some of their streams are on YouTube and come in through that route, under YouTube's terms. |

**Outcome.** Three tile types in a source-agnostic model: `HLS` (Caltrans, Media3), `YOUTUBE`
(IFrame player, live thumbnail as snapshot), `IMAGE` (Windy, Digitraffic, Caltrans JPEG: refreshed still).
Live scenic coverage mostly comes from YouTube, catalogue breadth from Windy.

## 2. Stack

- **Toolchain identical to almanak**, known to work on this Shield and this Mac: Gradle 9.7.1, AGP 9.4.1,
  Kotlin 2.4.10, Compose BOM 2026.09.00, tv-material 1.1.0, compileSdk 37.2, minSdk 28, targetSdk 36.
  Single module `:app`, package `app.lukarne`, manual wiring.
- **Media3 1.10.1** (ExoPlayer + HLS), **Coil 3.6.2** (images, with OkHttp), **android-youtube-player 13.0.0**.
  All released more than two weeks ago.
- **Secrets** (`windy.apiKey`, `youtube.apiKey`) live in `local.properties` (gitignored) or env vars.
  The YouTube key is only read by the pipeline. The Windy key is compiled into the app via `BuildConfig`,
  because free-tier image tokens force runtime calls. That is acceptable for a sideloaded personal app, and
  would need a proxy for anything published.
- **No backend.** The catalogue is a JSON asset built offline by `tools/catalog/` and embedded in the APK.

## 3. Globe

- **Options:** MapLibre Native (tiles from OpenFreeMap) vs a custom globe drawn in Compose `Canvas`.
- **Finding:** MapLibre Native still has **no globe projection on Android** (GL JS has it since 5.0; native
  tracks it in maplibre-native#3161). A flat Mercator map with free pan is the opposite of the brief.
- **Choice: custom orthographic globe** in Compose Canvas: land drawn as a field of dots (Natural Earth,
  public domain), webcam clusters as glowing points, rotation and zoom fully driven by our own D-pad focus
  model. Cost per frame: a ~10k-point rotation + one `drawPoints` call, well within the Shield's budget.

---

## Slice 2 — Catalogue

### Pipeline
- `tools/catalog/build.sh` (Python stdlib + `timezonefinder` in a private venv) writes `app/src/main/assets/catalog.json`.
  No backend: a ~0.6 MB JSON in the APK is enough for a few thousand cams, loads in ~150 ms on the Shield
  and works offline for browsing.
- **Sources, in priority order** (all adapters normalise to the same `Webcam` record):

  | Source | How cams are picked | Liveness check at build time |
  |---|---|---|
  | YouTube | `youtube_places.json`: ~100 hand-placed spots, each with a search query and match tokens. With a key, one `search.list` per place (cached a week), then `videos.list` keeps `live` + `embeddable` + `public`. | Without key: oEmbed (public + embeddable) + `hqdefault_live.jpg` exists |
  | Caltrans | 2 176 streaming cams → thinned to ≥ 4 km apart, scenic keywords first (Tahoe, Donner, SR-1, bridges, beaches…), capped at 300 (V1.6) | Master + media playlist answer with segments (131/180 pass) |
  | Windy | `nearby` (250 km, 50 most popular) around every region centre and every YouTube place, plus every country's 1 050 most popular (V1.6), thinned to ≥ 8 km, road cams last | `status=active` and updated < 48 h before Windy answered |
  | Digitraffic | Finnish weathercams thinned to ≥ 12 km, capped at 150 (V1.6) | JPEG answers |

- **Windy rate limit.** The free tier answered HTTP 429 after ~400 calls in an hour. Raw answers are cached per
  search point for 3 days (`tools/catalog/cache/`, gitignored) and a stale cache answer is used when a call fails.
  A source that returns less than half of what it had last time keeps its previous cams: a rate-limited run
  must never wipe the catalogue (that happened once, which is how this rule exists).
- **Windy image URLs are token-free** today (`imgproxy.windy.com/_/preview/plain/current/<id>/original.jpg`,
  320×224, `Cache-Control: max-age=150`), contrary to the documented 10-minute tokens. The catalogue stores them
  directly; the app needs no Windy call at runtime and no key in the APK. `BuildConfig.WINDY_API_KEY` stays
  wired for the day tokens come back (runtime resolver = V2).
- **Names are cleaned** for reading at 3 m: Windy's `City › North: FRE-41-AT TULARE AVE` → `City`, Caltrans'
  `Bass_Lake_ED50_WB` → `Bass Lake`. Road codes and bare compass directions are dropped.
- **De-duplication:** Windy republishes Caltrans cams; a still within 500 m of a live cam is dropped.
- **Geography:** 38 named regions (EN/FR) are the first focus level; a cam joins the nearest centre within
  2 200 km. Regions with < 3 cams merge into neighbours, except when they hold a live cam (Southern Africa
  disappeared once because of that). Inside a region, cams cluster greedily into **places** (40 km), live cams
  seeding first so places are named after them.
- **Dead list from the device.** `adb pull /sdcard/Android/data/app.lukarne/files/dead.json` then
  `build.sh --dead dead.json` drops what the Shield found dead.

### Result (2026-10-09, no YouTube key yet)
1 648 cams — 16 YouTube live, 90 HLS live (Caltrans), 1 542 stills (1 502 Windy, 40 Digitraffic) — in 34 regions
and ~580 places. Live coverage is thin outside California until the YouTube key is in: that is the single biggest
lever on the product, and adding the key + `build.sh --only youtube` is all it takes.

---

## Slice 3 — Mosaic

### Decoder budget (measured on the Shield, Caltrans 480p–720p H.264, 125–410 kb/s)
| Live tiles | Works? | App CPU (of 400 %) | Notes |
|---|---|---|---|
| 4 | yes | ~80 % | |
| 6 | yes | ~90 % | |
| 9 | **yes** — 9 concurrent `OMX.Nvidia.h264.decode` instances, first frames in 2.3–3.6 s | 113–137 % | no decoder errors; the cost is CPU (OkHttp + extractors + 9 TextureViews) |

- **Choice: `maxPlayers = 6`** (8 since V1.2, see below) → 4 tiles stay live at ≤ 480p, 1 slot follows the focused tile, 1 is kept for the
  fullscreen prefetch. The other tiles are stills that refresh themselves (Caltrans JPEG every 30 s, Windy 30 s).
- **The live set does not follow the focus.** A fixed set of HLS tiles per page is live; moving the focus starts at
  most one player (when it rests 350 ms on a non-live HLS or YouTube tile). Players are **recycled** (`stop()` frees
  the decoder, the ExoPlayer object goes back to an idle list): building/releasing one costs 50–150 ms of main thread.
- **One YouTube player.** The IFrame WebView is a singleton, so only the focused tile plays YouTube. It is handed
  over between screens by ownership (`claim`/`release`): during the mosaic → fullscreen crossfade the outgoing tile
  can't stop it, and the fullscreen picks the video up without reloading (instant).

### Frame time
- Measured with an **in-app autopilot** (debug only, `--es autopilot right,down,ok`): `adb shell input` boots a
  Java VM per key on the Shield (skews the very frame being measured) and the remote's `/dev/input` nodes are
  not writable from the adb shell (SELinux). `tools/jank.sh` + `tools/framestats.py` break slow frames down by phase.
- **Debug builds are not representative:** on a key press, debug Compose spends ~10 ms recomposing (≈ 20 ms frames,
  one dropped frame per press); the R8 release build spends ~1 ms (0–1 slow frame over 10 presses, p90 11 ms).
  `tools/deploy.sh` therefore installs the release build by default (signed with the debug key: sideloaded app).
- Animated values (focus scale, dim, fade-in) are read in `graphicsLayer`/draw lambdas, not in composition.

### Stills
- **YouTube live thumbnails are stale**: every `*_live.jpg` variant shows the frame from when the stream started
  (Jackson Hole: snow and Christmas lights in October). They are shown as a blurred, dimmed **poster** (colour and
  mood, plus the live dot), never as a current view. The focused tile goes live within ~2 s.
- **Windy stills (320×224) in fullscreen** would look like broken video stretched to 1080p. They are shown as a
  **postcard**: the sharp frame centred, on a blurred ground made from the same image downscaled to 24 px
  (API 30 has no `RenderEffect`).
- A refresh replaces the frame only once the new one has decoded; a failed refresh keeps the last good frame.
- A still that never loads, a stream that errors or shows no frame within 8 s (HLS) / 12 s (YouTube) is marked
  dead (persisted 6 h), replaced on the wall by the next cam of the zone, and skipped in fullscreen.
- `VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER` happens even when oEmbed says embeddable (`6aJXND_Lfk8`): only the
  Data API's `status.embeddable` + the device's dead list are trustworthy.

---

## Slice 4 — Globe

### Rendering: Canvas → OpenGL ES
- **First version in Compose Canvas** (native `drawPoints`/`drawLines`): recording took 2 ms, but HWUI's RenderThread
  + GPU took **28 ms for 18k land dots, 35 ms for 580 light halos (one radial gradient each), 83–150 ms for the
  coastlines** (AA strokes). Square aliased dots, hairlines and a pre-rendered halo sprite halved it; still far
  from 60 fps. Android's Canvas is not built for tens of thousands of primitives per frame on a Tegra X1.
- **Choice: GLES 2 on its own thread.** Land dots, coastline segments and lights live in VBOs uploaded once; the
  projection (orthographic, camera basis as three uniforms) runs in the vertex shader; a frame is 4 draw calls
  (sky+ocean quad with per-pixel day/night, dots, `GL_LINES` coastline, additive point-sprite lights).
  **~3 ms per frame, 60 fps, no missed vsync** during navigation. 4× MSAA.
- **SurfaceView, not TextureView**: with a TextureView, HWUI re-composited the window for every GL frame (UI frames
  at ~20 ms p90, 10–16 dropped GL frames per 5 s). SurfaceView: UI p90 10 ms, GL 60 fps flat. Compose (panel,
  labels, focus ring) draws above it in the window.
- MapLibre was not pursued (no globe on Android; free pan is the wrong interaction anyway — see §3).

### Look
- Land = 18 394 dots of a 64k-point Fibonacci sphere filtered by Natural Earth 1:50m land; coastlines = NE 1:50m
  (59k segments). Zoomed in, the dots fade and the hairline coastline carries the shape. (Growing the dots into a
  soft fill was tried: the overdraw cost 200 ms of GPU per frame.)
- **Real day/night**: subsolar point from the date (NOAA approximation), soft terminator on land and sea; the panel
  says DAYLIGHT/NIGHT for the focused spot. Lights = places, size by log(cams), warmer and larger when live,
  additive so dense areas bloom.

### Navigation
- Two levels: **regions** (whole globe) and **places** (zoomed so the region's places spread fills ~⅓ of the
  height, capped at ×6 so neighbours stay on screen). OK dives, BACK climbs, the camera flies (850 ms ease) to
  the focused target — the remote never pans blindly.
- A move picks the nearest target within 60° (then 89°) of the pressed direction, measured on an **azimuthal map
  centred on the current focus** (north up), so RIGHT keeps going around the world. At place level, when the
  region has nothing in that direction, the focus continues into the next region's places: browsing flows
  across borders.
- Diving into a region lands on its fullest wall (cams + live). The start focus is the region sharing the TV's
  time zone. ⏯ / Menu = Surprise me on every screen.

---

## Slice 5 — Finish

- **Brand.** lukarne = a dormer window: the mark is a round window onto a dotted globe (a screen-space grid of
  dots kept where Natural Earth has land, so it stays legible at 48 dp) with one warm light on Montréal; the
  wordmark is Instrument Serif. Rendered by `tools/render_brand.swift` from the app's own assets (banner 320×180 dp,
  launcher icons mdpi→xxxhdpi), no binary design source.
- **Splash** while the catalogue and the globe geometry load (~1.5 s cold): wordmark + one breathing light,
  never a black screen.
- **Fullscreen caption**: place, country, local time, current weather (Open-Meteo, no key, cached 20 min per
  ~10 km cell; the caption simply goes without when offline), position in the wall, source credit. Fades after
  4.5 s, any arrow except LEFT/RIGHT brings it back.
- **Attribution**: Windy's free tier requires it wherever its images show → the mosaic footer lists the sources of
  the page and the fullscreen caption names the cam's source. Digitraffic and Open-Meteo (CC BY 4.0), Caltrans,
  Natural Earth and YouTube are credited in Settings.
- **Settings** (≡ Menu on any screen): language (English default, French) and °C/°F, persisted in
  SharedPreferences, applied live. ⏯ stays "Surprise me". A long press on OK was considered and dropped: every
  screen acts on OK's key-down, so a long press would first dive/open.
- **Surprise me**: a region is drawn first (√ of its daylight cams as weight), then a cam in it, live 7 times out of
  10. Without that, California's 90 live road cams won almost every draw.
- **Back from the background**: `onStop` releases every player (nothing plays behind the TV's home screen); on
  return the screens remount (navigation state kept above a session key) so tiles re-acquire their players.
- The dead-stream list lives in app-specific external storage so `adb pull` reads it from the release build.

### Soak (2026-10-09, release build, `tools/soak.sh 22 120`)
Two 22-minute runs of the autopilot loop (globe → region → place → mosaic → fullscreen ←/→ → back ×3 →
Surprise me → back ×3), 27 rounds each, ~1 300 key presses per run.
- **Same process start to finish, 0 crashes, 0 ANRs** in both runs.
- TOTAL PSS oscillates 237–433 MB with no trend (players and the WebView come and go); `run1.csv`, `run2.csv`.
- Live heap after a forced GC, once per round (`gc.txt`, last 8 rounds kept by logcat): Java 8.0–8.3 MB,
  native 153.7–155.1 MB, threads 52–60 — flat. No leak.

---

## V1.1 — Live first (2026-10-10)

Feedback on the Shield: too many stills, and no way to reach Settings (the Shield remote has no Menu key).
- **Stills are opt-in** (Settings → Still images, off by default). With stills off: the globe only targets regions
  and places that have live video (still-only places stay as faint embers, unlabelled); a mosaic is the live cams
  of the place's region plus neighbours within 800 km, nearest first (first version: ≤ 2 500 km, else the nearest
  anywhere — London's wall ended in Patagonia; a short local wall is better); Surprise me is live only; the
  panel counts live webcams. Turning stills on restores the previous behaviour.
- **BACK on the whole-globe view opens a small sheet** (Settings / Quit lukarne) instead of quitting outright.
  Menu still opens Settings directly on remotes that have it.
- YouTube posters in the mosaic are now readable (160 px, dimmed) rather than pixel-blurred (40 px):
  `Modifier.blur` is a no-op before Android 12, and a wall of unreadable blocks gave nothing to choose from.
  The catch: a poster can show daylight at night. Only the focused tile plays YouTube (one WebView).

## V1.2 — More live sources (2026-10-11)

Live cams went from 135 to ~345 (YouTube 45 → ~225, HLS 90 → 120).
- **YouTube channels, not searches.** `search.list` costs 100 units and a separate, small "Search Queries per day"
  quota that kept running out. 24/7 webcam channels (EarthCam, Virtual Railfan, I Love You Venice, Kyoto City
  Tourism, The Real Samui Webcam, Ozolio, Webcamtaxi…) are listed through their uploads playlist instead
  (`playlistItems.list`, 1 unit per 50) and `videos.list` keeps what is live, public and embeddable: ~500 units
  per build, no search quota, so every build refreshes stream ids that change when a channel restarts a stream.
  Channels were found without searching either: the channels behind the lives we already had.
- **Placed by curated title rules** (`tools/catalog/youtube_channels.json`), not by geocoding titles: titles mix
  archives, news, walking tours, fashion shows and multi-cam montages; a rule names a place only for titles it
  recognises, unknown titles are left out. YouTube's `recordingDetails.location` was empty on every live checked.
- **DelDOT** (Delaware): 357 public 720p HLS cams from the JSON behind deldot.gov's map, thinned to 30 (coast and
  bridges first). Rejected: Wisconsin 511 (352×240, too soft on a TV), Georgia/Florida 511 (video needs auth),
  Norway Vegvesen (HLS endpoint answered 503), OpenWebcamDB (25 API requests a day).
- **`maxPlayers` 6 → 8** (6 steady tiles per page): DelDOT has no stills, so a tile without a player was black.
  Measured on a 9-cam DelDOT wall: 57–82 % app CPU (of 400 %), no decoder errors; a 6-minute soak stayed within the
  22-minute soak's PSS envelope (283–437 MB vs 245–432 MB). Tiles without a still get the live slots first and a
  quiet ▶ card while they wait.
- **Europe (same day):** 21 more channels (livespotting for Hamburg, the Kiel Canal, Warnemünde and Borkum; Croatia
  live panorama; Zermatt; Mont Ventoux and Provence; Malta, Skiathos, Lanzarote, Barcelona, Lisbon, Madeira; Warsaw,
  Lake Solina; Oulu and Lyngen aurora cams; Llandudno, Portsmouth…), found through YouTube's live-filtered result
  page browsed once by hand (no API search quota), then scanned like the others. Europe went from 7 live cams to ~75.
  Left out: streams whose title carries the date (a new video id every day, dead by the next build).
  New region "Croatia & the Adriatic"; Italy's and Iberia's centres moved so Naples stays in Italy and Catalonia in
  Iberia. Places are no longer named "unknown" (Windy's placeholder locality): a place takes its live cams' town.
- Still thin: Scandinavia (no live), the Alps (2), France (3).

## V1.3 — Catalogue refreshed daily, outside the APK (2026-10-11)

- **Why:** YouTube lives change video id whenever a channel restarts its stream; with the catalogue frozen in the
  APK, live cams went 74 → 52 → 45 in one weekend.
- **How:** a GitHub Actions cron (`.github/workflows/catalog.yml`) reruns the YouTube source daily and force-pushes
  one orphan commit holding `catalog.json` to the public repo `storagebits/lukarne-catalog` (the app repo is
  private, and a token inside the app was ruled out). The daily run never calls `search.list` (`--no-search`): the
  searched spots are re-checked from the previous catalogue's ids, channels are re-read — ~700 units a day.
- **App:** downloads it behind the splash (waits at most until the logo would leave anyway; otherwise it finishes in
  the background for the next launch), at most once an hour, keeps it in `filesDir`, and loads the newer of it and
  the embedded copy (by `generated`). Never swapped under an open screen. A truncated file or another schema
  `version` is ignored. Back from the TV home after 30 min or more, the app starts over behind the logo (globe,
  newest catalogue) instead of resuming: the Shield keeps the process for days, so "next launch" alone could mean
  never. A shorter absence resumes where it was. Rejected: a server of our own (something to host and keep up for one JSON file).
- **Not refreshed by CI:** Windy, Caltrans, DelDOT, Digitraffic come from the committed asset (stable URLs; Windy
  needs a rate-limited key and a cache CI doesn't keep).

## V1.4 — Clean stills: frames grabbed on the device (2026-10-12)

- **Problem:** YouTube tiles showed the live thumbnail decoded at 160 px (pixelated on a ~464 px tile). Worse, that
  thumbnail is a *poster chosen by the channel*, not a frame: `hqdefault_live`/`maxresdefault_live` showed a sunny
  Tamariu beach at 4 a.m. (checked 2026-10-12). DelDOT has no still at all.
- **Server-side grab, rejected:** yt-dlp + ffmpeg takes a real frame in ~3 s from a home connection (301/311 cams
  in 72 s), but from GitHub's runners YouTube answers "Sign in to confirm you're not a bot" for every video (23/311,
  DelDOT only). Cookies would tie it to an account: no.
- **On-device grab (`FrameStore`):** while a cam plays, the app keeps one frame of it (640 px JPEG in
  `cache/frames`, deleted after 24 h): HLS via `TextureView.getBitmap` (clean video, no overlay), YouTube via
  `PixelCopy` of the tile's window region (the IFrame's video can't be read back), with the tile's caption hidden
  for the 3 frames of the copy. First grab 4 s after the picture appears, then every 3 min while it keeps playing,
  never twice within 90 s. A uniform copy (blank video surface) is dropped.
- **Shown:** a grabbed frame replaces the still while younger than 3 h (20 min for Caltrans, whose own still
  refreshes every minute). Otherwise the YouTube poster, now decoded at tile size, under a veil that turns heavy
  where it is night, so a daylight poster doesn't pass for the scene at night.

## V1.5 — Dive into a place, climb back out (2026-10-12)

- **OK on a place** no longer cross-fades: the camera dives. A `dive` value 0→1 (1.1 s, ease-in) multiplies the
  place's zoom by `30^dive` (geometric, so the fall keeps speeding up instead of braking), slides the place from the
  globe's off-centre spot to the screen centre, fades the panel, hints and labels, and blooms the lights
  (`GlobeCamera.dive` scales them ×6). At 72 % the wall takes over: it scales up from 0.72 out of the centre while
  the globe fades.
- **BACK from a wall** is the reverse: the wall shrinks and fades, the globe arrives at `dive = 1` and climbs out
  (1.15 s, decelerating) to the place's normal view. The climb waits for the fresh GL surface's first frames
  (`GlobeCamera.drawn`): without that, half of it happened behind a blank surface (seen in screen recordings).
- Keys are swallowed during a dive (the wall is already on its way). Checked with `adb shell screenrecord` +
  ffmpeg contact sheets: the orthographic globe at ×30–90 reads as falling towards the ground (coastlines and
  lights rushing outwards); no extra geometry was needed.

## V1.6 — The whole world (2026-10-12)

- **Regions:** 19 more (Western Canada, Great Plains, Greenland, Colombia & Venezuela, Patagonia, Canaries/Madeira/
  Azores, Poland & Ukraine, Russia, Siberia, Svalbard, Turkey & Caucasus, Arabian Peninsula, Central Asia, West
  Africa, Indian Ocean, Indonesia & Philippines, Himalaya, Western Australia, Pacific islands), 58 in all;
  `MAX_KM` 2200 → 3000 so no cam on land is left without one. A region still needs 3 cams or one live cam to show.
- **Windy, country by country:** `nearby` only reaches 250 km around ~400 points, so Australia, France or Norway were
  mostly missed. Each country is now paged too (`countries=XX`, 50 per page, offset capped at 1 000 by the free
  tier: at most 1 050 per country, most popular first). Windy holds 67 500 cams (34 000 in North America, 27 000 in
  Europe, 350 in South America, 200 in Africa), so thin regions stay thin. The ~600 calls go over the free tier's
  ~400/hour: the sweep stops at the first 429, builds from the cache, and the next run carries on (pages cached 14
  days). "Updated < 48 h" is now judged when Windy answered, not at build time (cached answers are days old).
- **`thin()` is grid-bucketed** (20 000 cams in 0.2 s instead of minutes), checked against the naive version.
- **More live road video:** Caltrans 90 → 300, DelDOT 30 → 100, Digitraffic stills 40 → 150.
- **YouTube for empty regions:** live search is a poor tool (100 units per query, most country queries return
  nothing live). ~40 searches found 9 usable 24/7 cams, added as channels (Stockholm, Makkah, Nepal, Varanasi,
  Cape Town, Muizenberg, Hong Kong ×2) or pinned ids (Western Wall).
- **Live-only by default, unchanged:** a region without live video is still hidden unless stills are on in
  settings; with stills on, every region shows.
- **Result:** 10 724 cams (288 YouTube, 400 HLS, 10 036 stills), 3 477 places, 55 regions (Pacific islands, West
  Africa and North Africa have too few Windy cams to show). Windy is thin where the world is: India 9, Andes 9.
- **Scale on the Shield:** catalogue 3.8 MB, parsed in 0.38 s (release, behind the logo); globe at 60 fps live-only
  with 3 477 lights; navigation jank unchanged against the 1 931-cam catalogue (same autopilot run). With stills on
  the globe runs at ~48–50 fps, as it already did with the old catalogue, and not because of the lights (drawing
  none changes nothing): a known, older cost, left for later.

## V1.7 — Demo mode and corner player (2026-10-12)

- **Demo** (`DemoScreen`): random live cams, the "surprise me" draw (daylight, regions weighted by √live cams),
  never one of the last 40 nor the region just left. Time per cam is a setting (20 s, 30 s, 1, 2, 5 min). Entered
  from the leave sheet (BACK on the whole globe: Demo mode / Settings / Quit) or `--ez demo true`.
- **Transition = a dip to a title card**, not a crossfade between two videos: the app has one YouTube player
  (one WebView), so the next YouTube cam cannot start before the current one stops. The cam on screen drifts forward
  and fades (1.1 s) while region, place and local time come up; the next cam plays hidden under the card and is
  revealed (1.6 s fade, then a slow 6 % zoom-out over its whole stay) only once its video is on screen, and 4 s
  later for YouTube, whose own title overlay covers the first seconds. A slow start reads as a longer title; a dead
  or 12 s-silent cam is swapped for another under the same card. An HLS successor is prefetched 10 s ahead.
  Two layers alternate in a fixed order (a reordered `key` remounted the players: every cam started twice,
  seen in the logs and screen recordings).
- **Corner player** (setting, off by default): BACK from fullscreen leaves the cam playing, with its sound, in a
  256×144 dp box bottom-right over the walls and the globe. It shares the fullscreen player (HLS by cam id, YouTube
  by video id), so nothing restarts; it grows out of the corner (scale 2.6 → 1). While it holds YouTube, the walls'
  YouTube tiles show stills (one player). On a wall it moves to the bottom-left while the bottom-right tile, which
  it covers, has focus. Opening any fullscreen or the demo replaces it; BACK on the whole globe closes it.
- **No YouTube screen grabs** in the demo (the title card may cover the video) or the corner player (too small);
  one was found storing a picture of the title card as a cam's still.
- **Prefetched HLS players were declared dead:** without a surface a player never renders a first frame, so the
  8 s timeout marked it dead (the demo, and the fullscreen neighbours since V1.0). Reaching `STATE_READY` now
  counts as alive. Demo soak after the fix: 11 cams in 5 min, no timeout, PSS 230–400 MB, no crash.


## V1.7.1 — A YouTube cam that never started (2026-10-12)

- **Reported:** switching from one YouTube cam to another sometimes left the new one frozen (its still, no video);
  going to a third cam and back made it play. Not reproducible on demand.
- **Found** by logging every IFrame state: when a screen hands the player to the next one, Compose removes the old
  host (the WebView leaves the window) *before* the new screen claims the player, so `loadVideo` ran while the
  WebView was detached (`attached=false parent=null` in the log). The page then sometimes stayed UNSTARTED and
  stopped reacting for over a minute (84 s seen): later loads were ignored, the old video's sound kept playing, and
  no PLAYING ever came, so the tile kept showing its still. A second, smaller fault: a PLAYING reported by the
  previous video right after a quick double switch was taken for the new one's.
- **Fix (YouTubeStage):** a load waits until the WebView is back in a window (attach listener, then `post`); a
  PLAYING counts only after the UNSTARTED that every newly loaded video reports first; and a video still not
  started halfway to the 12 s timeout is loaded once more before being declared dead.
- **Checked** on the Venice wall (all YouTube): 12 single switches, 8 quick double switches, 6 wall → fullscreen
  hand-overs, 2 min of demo: every cam started (0.8–2.7 s), no retry needed, no timeout.

## V1.8 — Getting ready to publish (2026-10-13)

One build, the one the viewers get: no "personal" variant (asked for explicitly, after one was tried for a day).

- **No key in the APK.** The Windy key was still compiled into `BuildConfig` (unused): removed. Git history,
  working tree and built APK checked against the real key values and the usual secret patterns: nothing found.
- **Release signing.** The release build takes its key from local.properties or the environment
  (`release.storeFile`…), else the debug key. The key lives outside the repo and must be kept for life: every update
  must be signed with it (Play: the upload key under Play App Signing). The Shield was reinstalled once to move from
  the debug key to it.
- **Decoder budget per device.** `HlsPool.deviceBudget`: min(8, hardware H.264 instances − 1, 4 under 1.6 GB of
  RAM / 6 under 2.5 GB), at least 3. The Shield (32 instances, 3 GB) stays at 8; a Chromecast with Google TV HD
  (1.5 GB) would get 4 (not tested on one).
- **The daily CI also rebuilds the road cams.** Their streams are probed at build time, so a dead feed drops out
  without a device reporting it (`dead.json` needs adb, which viewers won't have).
- **Windy is gone.** Its API terms (account.windy.com, *Webcams API terms of use*) forbid redistributing the
  content obtained through the API (the catalogue in the APK, the repo and lukarne-catalog), "continuous scanning of
  a significant number of available Webcams" by automated software (the country sweep), and publishing graphics
  that show webcam images. Its 9,886 cams were all stills (no video): in the default live-only mode they showed
  nothing; they only fed the stills setting. The catalogue is now YouTube + road cams: ~845 cams, 40 regions, all
  with live video. The only lawful way back would be the app calling Windy's API itself for the cams on screen,
  which needs the key in the app or a proxy, and a paid tier for an audience. The repo's history and `shots/` still
  hold Windy data and images: the public repo must start from a clean snapshot, not this history.
- **YouTube by its terms** (Developer Policies, Required Minimum Functionality):
  - no stored frames: the on-device YouTube grabs of V1.4 are gone (HLS grabs stay: our own decoder, open feeds);
  - nothing in front of the player: wall tiles no longer play YouTube (poster, labelled "YouTube" as the policies
    ask of YouTube thumbnails); in fullscreen the picture makes room for the band instead of sitting under it (it
    shrinks first, the band fades in after, and the reverse on the way out); the corner player's caption sits under
    its picture and the player steps aside while a sheet's scrim is up;
  - no playback before the player is visible: no more fade-in from alpha 0; in the demo a YouTube cam starts in plain
    sight once its title card has gone, without Ken Burns or caption, and the next card waits for it to fade out;
  - at least 200×200 px: the corner player is 512×288 px on the Shield (256×144 dp at 320 dpi).
  The visible cost: YouTube's own title and logo show over the first seconds of a video.
- **Whole-word title matching** in the YouTube pipeline: "como" matched "Comodoro" and put a Patagonia stream on
  Lake Como. Search results, channel rules and (daily run) searched cams kept from the previous catalogue must now
  name their spot as a word; a stream titled "Kilauea" filed as Reykjanes went the same way.

**Updates (2026-10-13).** One build for every channel, so it decides by its installer: from Google Play (or
Amazon) it never asks GitHub, since the store updates it and Play's policy forbids pointing elsewhere; sideloaded,
it reads `releases/latest` from the GitHub API at most every 6 h (anonymous: 60 requests/h per IP is plenty) and
shows the newer version on the globe (top right) and in Settings. It does not download or install anything itself:
that needs `REQUEST_INSTALL_PACKAGES`, which Play only grants to apps whose core function is installing. The user
types one stable link into Downloader (`…/releases/latest/download/lukarne.apk`, every release carries that asset);
same key, so it installs over the old one and keeps the settings. 1.0.0 predates this check.

## "Quit lukarne" does nothing (2026-10-17, ALG-18)

Reproduced on the Android TV emulator (5/5, scripted since in `tools/quit-check.sh`): open the leave sheet (BACK on the
whole globe), leave for the TV home, come back. The sheet is still drawn, but every key goes to the globe behind
it: DOWN moves the region, OK dives into a place, the sheet's highlight never moves. 5/5 failures before the fix,
0/20 on the other paths (plain, during the splash, after a wall, after the demo, fast presses).
- **Cause.** `onStart` after `onStop` bumps `LocalSession`, which remounts the screens (`key(LocalSession.current)`
  in `LukarneRoot`) so they re-acquire their players. The remounted `GlobeScreen` ran its
  `LaunchedEffect(LocalFocusEpoch.current) { requestFocus() }` again and took the focus from the open sheet, which
  lives above that key and is not remounted. The Settings sheet had the same hole.
- **Options.** (a) The sheets re-request focus when `LocalSession` changes: works only because they are composed
  after the screens, an ordering nobody would see. (b) A focus trap on the sheets (take it back on every loss):
  fights the screen during the sheet's fade-out. (c) **Chosen:** a screen never takes the remote while a sheet is
  open. `LukarneRoot` provides `LocalOverlayOpen` (leave or Settings sheet up); the four screens call
  `TakeRemote(requester)`, which requests focus on appearing and when an overlay closes, as before, but not while
  `LocalOverlayOpen` is true. Closing a sheet already bumps `LocalFocusEpoch`, so the screen still takes the remote
  back then.
- **Held BACK.** Found on the way: holding BACK on the globe opens the sheet on the first press and its key repeats
  closed it at once. The leave sheet now ignores repeated key-downs. Holding a key in Settings is unchanged.
- **Regression check:** `tools/quit-check.sh [rounds]` (adb, emulator by default, `--shield` for the device) runs the
  plain, home-and-back and held-BACK scenarios and fails if OK on Quit leaves lukarne on screen. Scripted rather than
  a Compose UI test: the bug needs a real `onStop`/`onStart`, and the project has no instrumented-test setup.

## Public README and 1.0.1 (2026-10-18, ALG-32)

Getting `storagebits/lukarne` ready to go public: the mirror must make sense to a stranger who arrives from a post,
and carry nothing private.
- **No LAN address.** The Shield's address was in the README, this file and two scripts. `tools/device.sh` now
  requires `LUKARNE_DEVICE` (clear error, port 5555 if omitted); `tools/adb-relay.sh` requires `SHIELD_HOST`
  (device.sh sets it). Options were a placeholder default (silently wrong for everyone) or a config file (one more
  thing to explain): an explicit variable is what the README already documented.
- **What the mirror carries.** `tools/publish.sh` also leaves out `REPRO.md` (the ALG-18 reproduction log: emulator
  build ids and step timings, useful only to us; the fix is explained above and `tools/quit-check.sh` is the lasting
  check). `DECISIONS.md` stays: the README points to it and it is where the measurements are. Its ALG ticket numbers
  stay too: harmless, and they match the commit messages. The default remote is now HTTPS (works with `gh` or any
  credential helper; SSH stays available through `LUKARNE_PUBLIC`).
- **README the Kronograf way.** Pitch, centred demo GIF, one overview sheet of nine captures, a "What it does" table,
  then install (Downloader), controls, build, sources and credits.
- **Where the pictures come from.** The GIF is a 52 s `screenrecord` of the SHIELD's installed build (versionCode 1,
  `e90e12a`: the same screens as 1.0.1), cut to 19.9 s. 1.0.1 was deliberately **not** installed on the SHIELD: its
  older build is the end-to-end test of the update notice once the repo is public. The emulator (KVM, SwiftShader)
  draws the globe at ~19 fps, too jerky for a GIF but fine for stills, which come from the 1.0.1 release build there,
  or from frames of the SHIELD recording (the dive, the places map, Indian River Inlet). The run on the SHIELD forces
  English with the `locale` extra, which is not saved: the owner's language setting is untouched.
- **Road cameras only** in the pictures (Caltrans, DelDOT, Fintraffic): no YouTube frame (YouTube's terms, as V1.8).
  Every GIF frame and every capture is reviewed for faces, plates, notifications and addresses.
- **GIF size.** 960 px at 12 fps: ffmpeg with one 192-colour palette (no dithering: dithered video noise defeats the
  GIF's frame differencing, 18 MB → 12.9 MB), then `gifsicle -O3 --lossy=80` → 7.3 MB, under the 8 MB rule. Lower
  frame rates looked choppy on the globe; 800 px would have been smaller but blurs the captions.
- **PNG size.** Each 1920×1080 capture is saved in full colour when that is ≤ 1 MB, else quantised by pngquant
  (libimagequant, quality 65–95): 0.4–0.95 MB, side by side indistinguishable from the original. Pillow's median-cut
  quantiser was tried first: it fits too, but shifts a cam's colours (the Sierra granite turned to "snow").
- **Tooling.** `tools/make_screens.sh` reproduces every step (shots, record, frames, gif, update, publish) with
  `tools/screens.py` (Pillow, ffmpeg from `imageio-ffmpeg`, in a venv under `build/`; gifsicle and pngquant from
  the system).
  New launch extra `--es cam <id>`: straight to a cam in fullscreen, so a capture names the cam it shows instead of
  a key path that breaks when the catalogue changes. Debug builds also take `--es updatesUrl <url>` (with plain HTTP
  allowed to localhost in the debug manifest only) to test the update notice against a stub `releases/latest`;
  release builds ignore it, so no other app can make lukarne announce a fake version.
- **Counts measured, not remembered.** Today's published catalogue: 831 cams, 41 regions, 242 places; 681 live video
  (Caltrans 300, DelDOT 100, YouTube 281) and 150 Fintraffic stills. The 1.0.0 notes' "~840 live webcams in 40
  regions" counted the stills as live; the vault's "~400" was older. The README and the 1.0.1 notes say ~830 / 680.
- **Scripts on Linux.** `release.sh` uses `sha256sum` when there is no `shasum`, gets `--draft` (the release stays
  invisible, and `releases/latest` unchanged, until it is published) and `--dry-run` (build and checks, then prints
  what the mirror and the release would get); `publish.sh` gets `--dry-run`. `release.sh` appends the APK's and the
  certificate's SHA-256 to the notes, as the 1.0.0 notes had by hand.
