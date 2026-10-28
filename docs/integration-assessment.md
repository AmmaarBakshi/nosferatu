# BombayChitra integration assessment

Written before any Nosferatu code, from BombayChitra's source at `main` (commit `0f762db`, dataset built
2026-10-17) and its `data-latest` release.

## What BombayChitra is

BombayChitra is **not a single app and not an Android library**. It is a map *platform* in four parts:

| Part | Stack | Output |
|---|---|---|
| `pipeline/` | Java 25 + Planetiler | `bombaychitra.pmtiles` (vector tiles, z0–15, ~96 MB) and `bombaychitra.db` (SQLite, ~230 MB) |
| `server/` | Go, pure-Go SQLite | REST API, tiles, styles, embedded web app |
| `web/` | TypeScript + MapLibre GL JS 6 | Browser map UI; generates the `light` / `dark` MapLibre styles |
| `config/` | JSON | Region and taxonomy |

Licence: code MIT; data © OpenStreetMap contributors (ODbL), Overture Maps (CDLA-Permissive-2.0 / ODbL); fonts
Noto Sans (OFL). Coverage: the Mumbai Metropolitan Region, bounds `72.70,18.80 – 73.85,19.82`. Outside these bounds
there is no map and no road data.

## Rendering

The map is drawn by MapLibre from vector tiles with a generated style (`/styles/light.json`, `/styles/dark.json`).
The style has exactly one source (`bc`, vector, TileJSON at `/tiles/bombaychitra.json`) and glyphs at
`/fonts/{fontstack}/{range}.pbf` (Noto Sans Regular, Medium, Italic). No sprite sheet: the portable styles draw POIs
as dots. BombayChitra's own docs list **MapLibre Native on Android** as a supported consumer of these styles.

## Road and path data

`bombaychitra.db` contains a `routing` table: the full road *and path* network as gzip-compressed little-endian
arrays (`docs/database.md`, "Routing graph"). Measured on the current build:

| | |
|---|---|
| Vertices | 197,968 |
| Segments (ways between vertices, with full shape) | 247,098 |
| Directed edges (CSR) | 492,765 |
| Compressed size | 13.4 MB |

Each segment carries length, class (motorway … path), vehicle direction, **access bits (car / two-wheeler / foot)**,
flags (steps, bridge, tunnel…), speed and a name. Footways, steps and foot over-bridges are included. This is exactly
what road-aware GPS correction needs: geometry, connectivity and "may a pedestrian be here".

What does **not** exist in BombayChitra:

- No map matching, no trajectory validation, no live-location features.
- No spatial index *on disk* for the routing graph; the Go server builds a ~550 m grid in memory at load time.
- No Android code at all.

## Can it be reused directly?

| Option | Verdict |
|---|---|
| Import the web frontend into Android | No. It is a Vite/TypeScript app; embedding it in a WebView would put rendering in JS and force a JS ↔ native bridge for every location update. MapLibre Native draws the **same styles and tiles** natively. |
| Call the REST API from the phone | Possible, but needs a running server and a network on every update. Unsuitable for live tracking. |
| Use the data files directly | **Yes.** PMTiles is read natively by MapLibre Native (`pmtiles://`). The routing graph is a documented binary format readable from any language. |
| Reuse the Go graph loader | Not on Android, but its decoding rules (delta-encoded shapes, `~s` reversed edges, CSR offsets) are small and documented; the Kotlin reader follows them line for line. |

## Chosen integration (least disruptive)

1. **Rendering:** MapLibre Native Android with BombayChitra's own generated styles, shipped unmodified except for
   two URLs rewritten at runtime: the `bc` source → `pmtiles://file://…/bombaychitra.pmtiles` and glyphs →
   bundled Noto Sans (copied from `web/public/fonts`). Optionally, a running BombayChitra server can be used
   instead (`https://server/styles/dark.json`) with no rewriting at all.
2. **Tiles:** downloaded on the phone from the BombayChitra `data-latest` release and verified against its
   `SHA256SUMS` — the same mechanism as `bombaychitra download`.
3. **Road graph:** `scripts/extract-graph.py` copies BombayChitra's `meta` and `routing` tables, byte for byte, into
   a 13 MB `graph.db` that ships in the APK. It is a strict subset of `bombaychitra.db` with the same schema, so the
   adapter can also open a full `bombaychitra.db`.
4. **Isolation:** a `:bombaychitra` module (`BombayChitraAdapter`) owns everything that knows about BombayChitra's
   formats. The tracking engine (`:engine`) only sees a `RoadNetwork` interface: nearby candidates, geometry,
   connectivity, network distance, pedestrian access, and "no data here".
5. **No second map provider.** No Google Maps, no Mapbox, no OSM tile server.

## Risks

- PMTiles via `file://` in MapLibre Native must be verified on a device (done in Phase B).
- The graph shape array decodes to ~11 MB of `IntArray`; plus the grid index the whole graph is ~40 MB of heap.
  Acceptable on current phones; measured in Phase G.
- Android's `CursorWindow` limits rows to 2 MB, and the largest blob is 7 MB, so blobs are read in chunks with
  `substr()`.
- The graph has paths that OpenStreetMap knows about. Unmapped paths (a park loop, a building compound) are real;
  the matcher must allow leaving the network.
