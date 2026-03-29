# Changelog

All notable changes to World Mirror are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
- Language file additions for all new UI strings (en_us, zh_cn, zh_tw, ja_jp).

### Changed

- Status screen Conflicts tab redesigned: shows stored-conflict count instead of in-memory
  queue, with Overwrite All / Discard All / Open Chunk Map buttons.
- Status screen Status tab: added *Export Nearby Region…* button.

### Fixed

- CI/CD: Added `-Djsse.enableSNIExtension=false` JVM argument to `gradle.properties` to fix
  TLS SNI handshake failures with `server.bbkr.space` in the GitHub Actions environment.

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
