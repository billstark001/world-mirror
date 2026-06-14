# Changelog

All notable changes to World Mirror are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
