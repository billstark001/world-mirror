# Changelog

All notable changes to World Mirror are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [0.2.2] — 2026-06-21

### Added

- **Xaero's World Map Overlay:** Render World Mirror status fills and merged boundaries directly on Xaero's World Map screen.
- Added a PowerShell script helper `Get-LatestXaerosWorldMap.ps1` for downloading the latest Xaero's World Map jar for development/testing.
- Customizable Xaero overlay settings: toggle overlay, configure overlay refresh rate, and maximum visible cells limit.

### Changed

- Version bump: `0.2.1` → `0.2.2`.
- Updated the Minecraft/Fabric dependency set to `26.2`, Fabric Loader `0.19.3`,
  Fabric API `0.152.2+26.2`, ModMenu `20.0.0-beta.3`, LibGUI `17.0.0+26.2`,
  and Cloth Config `26.2.155`.
- Reduced routine lifecycle, capture, export, migration, and world-structure logs from
  info to debug so normal gameplay chat/log output is quieter.
- Removed obsolete chest/debug mixins and centralized block-entity/container NBT handling
  in `BlockEntityNbtSupport`.
- **Chunk Map Optimizations:** Introduced `ChunkStatusCache` and `ChunkStatusSnapshot` to cache status data, reducing rendering and database query overhead on both the built-in chunk map and Xaero's overlay.
- Added merged boundary segment rendering to the built-in chunk map, reducing drawing calls when zoomed out.
- Extracted SQLite native drivers isolation for stable read-only status loading.

### Fixed

- Container block entities are no longer overwritten with empty `Items` data when a
  chunk is recaptured or exported over an existing local chunk. Previously captured or
  locally written non-empty item lists are preserved when the latest client-side block
  entity snapshot is empty.
- Container overlays are snapshotted when an export is deferred or running in the
  background, preventing later cache cleanup from dropping item data before the writer
  reaches the chunk.
- Default container titles such as `container.chest` and `container.chestDouble` are no
  longer written as `CustomName`, and old default-name compounds are stripped when chunks
  are merged.
- Double chest item ordering now follows vanilla's `ChestType.RIGHT == FIRST` combiner
  order for both full inventory packets and later single-slot updates, preventing large
  chest halves from being swapped or partially written.
- Chunk serialization now uses Minecraft's `SerializableChunkData` path, preserving
  available section light data (`BlockLight` / `SkyLight`), blending data, retrogen,
  post-processing, filtered heightmaps, and other vanilla chunk serialization fields
  that the client currently has.
- The download toggle keybinding now uses the `P` keysym instead of a raw scancode.
- Ported 26.2 client API usages for screen switching, HUD-hidden checks, and flat
  world preset lookup.

---

## [0.2.1] — 2026-03-29

### Added

- **Chunk Map (Window 1):** Full-screen draggable grid map accessible from the status screen
  (Conflicts tab → Open Chunk Map).  Each cell is color-coded:
  - Transparent — never downloaded.
  - Green → Blue (logarithmic, based on age) — downloaded via `world_mirror`.
  - Orange — written by a third-party source (e.g. `player`, `map_hp`).
  - Red inset border — chunk has an unresolved conflict stored on disk.
  Hovering shows a tooltip with chunk coordinates, last-update time, and source.
  Clicking a conflicted chunk opens a per-chunk dialog: **Overwrite** / **Discard** / **Cancel**.
- **File-based conflict storage:** When the `Manual` conflict strategy is active, incoming
  server chunks that collide with existing local chunks are now written to
  `conflict_chunks/<dim>/r.X.Z.mca` inside the mirror-world folder (MCA format) rather than
  being lost on restart.
- **Conflicts tab — bulk resolution:** *Overwrite All* (apply all stored server chunks) and
  *Discard All* (delete all stored server chunks, keep local) buttons in the status screen.
- **Export Nearby Region (Feature 2.1):** New screen (Status tab → Export Nearby Region…)
  lets you choose a world name and radius (1–50 chunks) to snapshot all loaded chunks in
  that area into a fresh singleplayer save.  Spawn point is set to the player's current
  block position.
- **Direct Chunk Map keybinding:** Press **M** to open the chunk map without going through
  the status screen.  The binding is configurable in *Options → Controls → World Mirror*.
- **Chunk map settings:** Added configurable sparse-render threshold (4–16, default 8)
  and map background style (black or transparent, default black).
- **MCA write support:** Added shared per-file locking and same-directory temporary-file
  replacement for terrain, entity, and conflict MCA writes.
- **Known-bugs research notes:** Re-enabled and expanded `KNOWN_BUGS.md` with current
  entity, block entity, world-generation/noise, heightmap, and lighting persistence gaps,
  plus pointers into Minecraft format docs and local mod source.
- Language file additions for all new UI strings (en_us, zh_cn, zh_tw, ja_jp).

### Changed

- Status screen Conflicts tab redesigned: shows stored-conflict count instead of in-memory
  queue, with Overwrite All / Discard All / Open Chunk Map buttons.
- Status screen Status tab: added *Export Nearby Region…* button.
- Dimension and server-world transition detection logs now use debug level instead of
  info level.
- Chunk map rendering switches to sparse drawing when zoomed far out, avoiding full-grid
  per-cell drawing at tiny cell sizes.
- Background exports are no longer daemon threads, reducing the chance of JVM shutdown
  cutting off an in-progress save write.

### Fixed

- CI/CD: Added `-Djsse.enableSNIExtension=false` JVM argument to `gradle.properties` to fix
  TLS SNI handshake failures with `server.bbkr.space` in the GitHub Actions environment.
- Export requests made while another export is running are queued and coalesced instead of
  being dropped.
- Chunks are only marked written after their region file has been flushed successfully, so
  failed region writes stay cached for a later retry.
- Region and conflict writes now share per-file locks, reducing races between normal export,
  manual conflict storage, and conflict resolution.
- Bulk conflict overwrite now maps legacy conflict folders (`region`, `DIM-1`, `DIM1`) back
  to the mod's current per-dimension world folder layout.
- Cleaned up several definite IDE warnings in mod-owned code without changing mixin method
  signatures or Minecraft mapping-sensitive casts.

---

## [0.2.0] — 2026-03-25

### Fixed

- Container items now export correctly. (BUG-1 in `KNOWN_BUGS.md`)
- Redstone components and other stateful blocks now export with their correct state.
- Biome and light data are now included correctly in chunk exports.
- Performance and memory issues in the caching and serialization processes have been comprehensively addressed:
  - Reduced blocking of the rendering/game threads caused by large-scale block capture before export, preventing noticeable stuttering caused by synchronous serialization.
  - Fixed an issue where container caches were not cleaned up synchronously when blocks expired, and modified the storage to be isolated by dimension to reduce memory growth after prolonged operation.
  - Cleaned up entity data that was out of sync with the block cache to prevent the continuous accumulation of entity cache data after dimension switching or frequent exports.
  - Tightened concurrent access paths for mirror mapping configurations to reduce the risk of race conditions between background exports and foreground configuration reads/writes.

### Added

- **SQLite chunk database** (`data/world_mirror.sqlite` inside each mirror world folder).
  Replaces the `chunkUpdateTimes` field in `worldmirror_meta.json` for chunk dirty-tracking.  The database also introduces an `update_sources` priority table that lets World Mirror and third-party tools (map renderers, importers) coexist without overwriting each other's data.  See `DATABASE.md` for the schema and integration guide.

- **Automatic migration** of legacy `chunkUpdateTimes` data from `worldmirror_meta.json` to SQLite on the first export after upgrading.  The JSON field is removed from the file after migration.

- **GitHub Actions release workflows** (manual trigger):
  - `release-modrinth.yml` — builds and publishes to Modrinth.
  - `release-github.yml` — builds and creates a GitHub Release with bundled JAR.

- **`KNOWN_BUGS.md`** — documents confirmed issues and their status.

- **`DATABASE.md`** — English documentation for the SQLite schema, intended for third-party tool authors.

### Changed

- Version bump: `0.1.0` → `0.2.0`.

---

## [0.1.0] — Initial release

- Initial public release of World Mirror.
- Client-side Fabric mod for Minecraft 1.21.11.
- Captures and exports multiplayer world data (chunks, entities, containers) to a playable singleplayer save.
- Features: dirty-chunk tracking, multi-dimension support, configurable conflict resolution, container and entity tracking, in-game status screen.
