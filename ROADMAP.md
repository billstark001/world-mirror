# World Mirror — Roadmap

This document tracks known bugs and planned features, grouped by theme.
Each item references the primary class(es) involved.

---

## 1. Chunk Tracking & Integrity

### [FEATURE] Record whether a chunk was mod-generated or subsequently modified
`ChunkListener`, `WorldMetadata`  
Add a flag or status field (e.g. `ChunkOrigin { DOWNLOADED, MODIFIED }`) so that
future tooling can distinguish pure mod-captured chunks from ones that the player or
chunk events have changed after capture.

---

## 2. Features

### [FEATURE] One-click area download to a new save
New UI screen and `DownloadManager`  
Allow the player to choose a radius and a save name, then snapshot all chunks within
that radius into a freshly created save. Spawn point = player's current position. The
new save must also use the void superflat preset (see §2).

### [FEATURE] Compatibility standard for third-party chunk importers
*(Discussion)*  
Define a precedence rule: if a chunk in the exported world originated from a third-party
tool (DistantHorizons, Voxy, BlueMap, etc.) it should be silently overwritten the first
time the player walks through and downloads the real chunk data — unless the chunk has
been marked as `MODIFIED` (see §1 above).

### [FEATURE] Automated publishing to Modrinth and GitHub Releases via Actions
`.github/workflows/`  
Add a `release.yml` workflow triggered on version tags that:
1. Runs `./gradlew build`
2. Publishes the JAR to Modrinth using the Modrinth upload API
3. Creates a GitHub Release and uploads the JAR as an asset
