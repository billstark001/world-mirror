# World Mirror — Roadmap

This document tracks known bugs and planned features, grouped by theme.
Each item references the primary class(es) involved.

---

## 1. Chunk Tracking & Integrity

### ✅ [DONE] Priority-based chunk update tracking
`ChunkDatabase`, `WorldMetadata`, `Exporter`
Chunk update history is now stored in `data/world_mirror.sqlite` with an `update_sources`
priority table.  Third-party tools can register their own update sources and have their
data respected by World Mirror's sync.  See `DATABASE.md`.

### [FEATURE] Record whether a chunk was mod-generated or subsequently modified
`ChunkListener`, `ChunkDatabase`
Add a `ChunkOrigin { DOWNLOADED, MODIFIED }` flag so that future tooling can distinguish
pure mod-captured chunks from ones that the player or chunk events have changed after
capture.

---

## 2. Features

### ✅ [DONE] One-click area download to a new save (Feature 2.1)
`ExportNearbyScreen`, `DownloadManager.exportNearbyToNewSave`  
Players can open a new Export Nearby Region screen (accessible from the Status screen's
Status tab) to capture all loaded chunks within a configurable radius (1–50 chunks) and
save them to a fresh singleplayer save.  The spawn point is set to the player's current
block position.

### ✅ [DONE] Compatibility standard for third-party chunk importers
`ChunkDatabase`, `DATABASE.md`
The `update_sources` priority table defines precedence rules: if a chunk was written by
a higher-priority source (e.g. `player`, `map_hp`), World Mirror will not overwrite it.
Third-party tools can insert their own rows to participate in the priority system.
See `DATABASE.md` for the full integration guide.

### ✅ [DONE] Automated publishing to Modrinth and GitHub Releases via Actions
`.github/workflows/release-modrinth.yml`, `.github/workflows/release-github.yml`
Manual-trigger workflows added for publishing to Modrinth and creating GitHub Releases.
Automated tag-triggered publishing is a future enhancement.

---

## 3. UI / Chunk Map (Window 1)

### ✅ [DONE] Full-screen chunk map (Window 1)
`ChunkMapScreen`  
A draggable, full-screen grid map showing the download status of every recorded chunk.
Colour coding:
- **Transparent** — never downloaded.
- **Green → Blue** (logarithmic, based on age) — downloaded via `world_mirror`; green
  = within 10 minutes, blue = older than 1 month.
- **Orange** — written by a non-`world_mirror` source (e.g. `player`, `map_hp`).
- **Red border** (inset 1 px) — chunk has an unresolved conflict entry stored in
  `conflict_chunks/`.

Clicking a conflicted chunk opens a per-chunk dialog offering
**Overwrite** (apply the stored server chunk) / **Discard** (keep local, delete conflict)
/ **Cancel**.  Available from both singleplayer mirror worlds and live server sessions.

### ✅ [DONE] File-based conflict storage
`ConflictManager`, `ManualResolver`  
When the **Manual** conflict strategy is active and a downloaded chunk collides with an
existing local chunk, the incoming server chunk is now persisted to
`conflict_chunks/<dim>/r.X.Z.mca` inside the mirror-world folder (MCA format).
Previously, conflicts were only tracked in memory and lost on restart.

Bulk resolution is available from the Conflicts tab of the status screen
(**Overwrite All** / **Discard All**) or per-chunk via the Chunk Map.

---

## 4. Integration Investigation — Fullscreen Map Mods

### [RESEARCH] Integrate Chunk Map into major fullscreen map mods

Several popular Minecraft map mods provide a fullscreen map view that would be a natural
host for World Mirror's chunk-status overlay.  Below is an investigation into the most
viable candidates.

#### Xaero's World Map
- **Modrinth/CurseForge:** very popular, actively maintained for 1.21.x.
- **Extension API:** Xaero's World Map exposes a `WorldMapSession` and
  `IWaypointSet` API; however, there is **no public overlay API** for third-party
  chunk-status layers as of 2026-03.  An overlay would require either:
  - Mixining into `WorldMapRenderer` to inject custom draw calls after the base
    terrain tiles (medium complexity, ~200–400 LoC).
  - Contributing an upstream PR for an overlay hook (uncertain timeline).
- **Estimated work:** 3–5 days to implement a Mixin-based overlay; an upstream hook
  would remove the Mixin dependency but relies on maintainer cooperation.

#### JourneyMap
- **Modrinth/CurseForge:** widely used, 1.21.x support confirmed.
- **Extension API:** JourneyMap provides a first-class **Plugin API**
  (`journeymap.client.api`) including `IClientPlugin`, `IOverlayListener`, and
  `PolygonOverlay` / `MarkerOverlay` shapes.  A chunk-status overlay can be
  implemented by:
  1. Registering a `IClientPlugin` annotated with `@Plugin`.
  2. Creating one `PolygonOverlay` per chunk record retrieved from `ChunkDatabase`.
  3. Updating overlays when the player moves or the database changes.
- **Estimated work:** 2–3 days; the JourneyMap API is well-documented and stable.
  The main challenge is performance — rendering thousands of polygons simultaneously
  may require spatial culling and LOD.

#### Voxelmap / Antique Atlas
- Both lack public overlay extension APIs; integration would require heavy Mixin use
  and carries a high maintenance burden.  **Not recommended** at this time.

#### Recommended path
Implement a **JourneyMap plugin module** (separate optional dependency, `modCompileOnly`)
that exposes the chunk-status and conflict data as polygon overlays in the JourneyMap
fullscreen view.  This can be done without breaking the standalone Chunk Map screen.

