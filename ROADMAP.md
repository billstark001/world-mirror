# World Downloader — Roadmap

This document tracks known bugs and planned features, grouped by theme.
Each item references the primary class(es) involved.

---

## 1. Build & Infrastructure

### [BUG] Incorrect Fabric Loom plugin ID in `build.gradle` *(CI-breaking)*
`build.gradle`  
Plugin ID `net.fabricmc.fabric-loom-remap` does not exist; the correct ID is `fabric-loom`.

---

## 2. Correctness & Crash Fixes

### [BUG] `EntityTracker::serializeEntity` calls non-existent `entity.writeNbt`
`EntityTracker`  
The method `entity.writeNbt(NbtCompound)` does not exist in MC 1.21+.
Must be replaced with the `entity.writeData(net.minecraft.storage.WriteView)` API, or
a manual serialization of the public entity state.

### [BUG] Block-entity serialization crashes when `originalNbt` is null
`ContainerTracker`, `ClientChunkSerializer`  
`BlockEntity.createNbtWithIdentifyingData` can return `null`; passing that value to
`ContainerTracker.enhanceBlockEntityWithContainerData` causes a NPE.
Both call-sites need null-guards.

### [BUG] Saved world LevelName is always "Downloaded World"
`WorldExporter`  
The `LevelName` field in `level.dat` is hard-coded to `"Downloaded World"`.
It should be derived from the mirror folder name / source world name.

### [BUG] "World structure created at:" is logged on every export, not only on first creation
`WorldExporter`, `DownloadManager`  
`WorldExporter.createLoadableWorld` always logs this line, even for incremental updates.
The method should distinguish between first-time creation and subsequent updates and log
accordingly.

### [BUG] Empty chunks (sent by server for out-of-render-distance positions) are stored
`ChunkDataMixin`, `ClientChunkSerializer`  
Servers sometimes send completely empty chunks. A fast check for all-air block sections
and zero block entities should be performed before caching the chunk, to avoid wasting
memory and polluting the exported world.

### [BUG] Exported world generates terrain from an uncontrolled random seed
`WorldExporter`  
The `level.dat` produced by `createLoadableWorld` uses a standard dimension preset, so
undownloaded chunks are generated procedurally. The world should instead use a void
superflat preset so that areas the player has not visited remain empty.

### [BUG] Possible render-thread violation when exporting
`WorldExporter`, `DownloadManager`  
`WorldExporter.createLoadableWorld` is called on a background export thread but may
indirectly trigger render-system calls (e.g. via lazy Minecraft internals). The method
must be confirmed safe to call off-thread; any render-system calls must be marshalled
back to the main thread via `MinecraftClient.getInstance().execute(...)`.

---

## 3. Performance & Thread Safety

### [BUG] `captureLoadedChunks` runs synchronously on the game thread
`DownloadManager`  
When download is toggled on, `captureLoadedChunks` serializes all loaded chunks on the
game thread. This can cause a noticeable hitch. The serialization loop should run on a
background thread (similar to the export path), with proper snapshot/copy semantics.

### [BUG] Cached chunks never expire — potential OOM under long sessions
`ChunkListener`, `ModConfig`  
The in-memory chunk cache grows without bound.  
Add cache-control settings to `ModConfig`:
- `maxCachedChunks` — evict oldest chunks when this limit is reached
- `maxCacheDistanceChunks` — evict chunks farther than N chunks from the player
- `maxCacheAgeSeconds` — evict chunks older than this threshold
- `invalidateAfterExport` — if `true`, remove a chunk from the cache immediately after
  it has been successfully written to disk

---

## 4. Save-Name & Conflict Handling

### [BUG] No handling for folder-name collision when saving to `saves/`
`WorldMetadata`, `DownloadManager`, `MirrorMapping`  
When `defaultSaveLocation = SAVES` and a folder with the same name already exists in
`saves/`, the mod silently overwrites `level.dat`. The correct approach is to read the
existing `wdl_meta.json` on first access to confirm the folder belongs to this mirror,
and append a numeric suffix (e.g. `_2`) if it does not.

---

## 5. UI Polish

### [BUG] "Clear Data" button label is misleading
`StatusScreen`, `en_us.json`  
The button labelled "Clear Data" only clears the in-memory chunk/entity/container
cache — it does not delete anything from disk. The label should be
"Clear Cache" (or similar) to avoid user confusion.

### [BUG] Export status label on the status screen is not refreshed after export completes
`StatusScreen`  
The export-running/idle label is read once at screen construction. After an export
completes the label remains "Running" until the screen is closed and reopened.
The screen should poll `DownloadManager.isExportInProgress()` periodically or
hook into the export-complete callback to trigger a UI refresh.

### [IMPROVEMENT] In-game status screen is too tall; consider tabbed layout
`StatusScreen`  
The single-panel design grows very tall. Splitting content into tabs
(e.g. Status, Settings, Conflicts) would improve usability on small screens.

### [IMPROVEMENT] Mod-menu config screen should match the vanilla settings style
`ConfigScreen`  
Replace the current plain-button layout with a style closer to vanilla
`GameOptionsScreen` (option rows with title on the left, cycling value on the right).

---

## 6. Chunk Tracking & Integrity (Low Priority)

### [FEATURE] Record whether a chunk was mod-generated or subsequently modified
`ChunkListener`, `WorldMetadata`  
Add a flag or status field (e.g. `ChunkOrigin { DOWNLOADED, MODIFIED }`) so that
future tooling can distinguish pure mod-captured chunks from ones that the player or
chunk events have changed after capture.

---

## 7. Features (Low Priority)

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
been marked as `MODIFIED` (see §6 above).

### [FEATURE] Automated publishing to Modrinth and GitHub Releases via Actions
`.github/workflows/`  
Add a `release.yml` workflow triggered on version tags that:
1. Runs `./gradlew build`
2. Publishes the JAR to Modrinth using the Modrinth upload API
3. Creates a GitHub Release and uploads the JAR as an asset
