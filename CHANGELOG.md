# Changelog

All notable changes to World Mirror are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [0.2.0] — 2026-03-09

### Fixed

- **Container items now export correctly.**  
  Chest, barrel, hopper, dropper, dispenser, shulker box, and other container block entity inventories are now merged from `ContainerTracker` at export time rather than at capture time.  Previously, containers were always exported empty because item data arrives in separate inventory packets after the initial chunk data.  See `KNOWN_BUGS.md` BUG-1 for details.

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
