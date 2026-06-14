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

### [FEATURE] One-click area download to a new save
New UI screen and `DownloadManager`  
Allow the player to choose a radius and a save name, then snapshot all chunks within
that radius into a freshly created save. Spawn point = player's current position. The
new save must also use the void superflat preset.

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
