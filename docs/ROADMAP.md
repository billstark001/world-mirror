# World Downloader — Roadmap

This document outlines planned features and improvements for the World Downloader mod.
Items are grouped by theme and ordered roughly by priority.

> **Status legend:** ✅ implemented · 🔲 pending

---

## 1. Download Toggle ✅

**Goal:** Replace the single-shot export with a persistent, user-controlled download session.

- Keybinding **P** (and a button in the in-game UI — see §6) to **start / stop** downloading.
- While active, periodically compare received chunk data against the saved copy (configurable
  interval — see §4) and write only changed / new chunks.
- Current download state (active / inactive) is shown in the action bar when it changes.

---

## 2. World Metadata ✅

**Goal:** Each mirrored world carries enough metadata to resume and manage downloads.

Every downloaded world stores a `wdl_meta.json` file containing:

| Field | Description |
|---|---|
| `modVersion` | Mod version that created / last updated the world |
| `sourceType` | `singleplayer` or `server` |
| `sourceId` | World folder name or server address |
| `lastSyncTime` | Unix-millis timestamp of the most recent sync session |
| `chunkUpdateTimes` | Map `"chunkX,chunkZ" → millis` of per-chunk last-write time |

---

## 3. Chunk Conflict Resolution ✅

**Goal:** Handle chunks that have been modified locally before the next sync.

Implemented strategies (`net.billstark001.worlddownloader.conflict`):

| Strategy | Class | Behaviour |
|---|---|---|
| `overwrite` | `OverwriteResolver` | Always replace local chunk with server version |
| `ignore` | `IgnoreResolver` | Keep local version; write only new chunks |
| `manual` | `ManualResolver` | Queue conflict for later; keep local until resolved |

- `ConflictResolver` interface leaves the door open for advanced strategies.
- `ManualResolver.getPendingConflicts()` exposes the queue for the future UI (§6).
- The active strategy is set in mod settings (§4).

---

## 4. Mod Menu Settings ✅

**Goal:** Add a proper settings screen accessible from Mod Menu.

Settings exposed via `ConfigScreen`:

| Setting | Values | Default |
|---|---|---|
| Save location | `Downloaded Folder` / `Saves Folder` | Downloaded Folder |
| Sync interval | 5 / 10 / 30 / 60 / 120 s | 10 s |
| In-game log level | `Debug` / `Info` / `Warning` | Info |
| Conflict strategy | `Overwrite` / `Ignore` / `Manual` | Overwrite |

Config is persisted in `config/worlddownloader.json`.
`ModMenuApiImpl` is registered as a `modmenu` entrypoint in `fabric.mod.json`.

---

## 5. World–Mirror Mapping Table ✅

**Goal:** Maintain a persistent mapping between source worlds / servers and their local mirror folders.

- Stored in `config/worlddownloader/mirrors.json` (`MirrorMapping` class).
- Each entry maps `sourceId` → local folder name inside `downloaded_worlds/`.
- On first use of a world or server, a folder name is auto-generated from the sanitised server
  address or world name; the mapping is persisted immediately.
- The output base directory changed from the single `downloaded_world/` folder to
  `downloaded_worlds/<name>/` where `<name>` comes from the mapping table.
- For `SaveLocation.SAVES`, the world is placed in `saves/<name>/` so it is immediately playable.
- Future UI (§6) will allow the player to rename / reassign the mirror path.

---

## 6. In-Game Status UI 🔲

**Goal:** Provide a LibGUI-based screen showing sync state and allowing the user to take actions.

Screen contents:

- **Source info**: world/server name and type.
- **Metadata summary**: last sync time, total chunks tracked.
- **Download status**: active / inactive toggle button.
- **Conflict queue**: list of chunks awaiting manual resolution, with action buttons (overwrite / ignore / defer).
- **Per-world settings**: override save location and conflict strategy.

The screen opens via a dedicated keybinding and is also accessible from the Mod Menu settings screen.

---

## 7. In-Game Logging Utility ✅

**Goal:** Surface important log messages inside the game, not only in the console.

- `WDLogger` helper in `util/` wraps SLF4J and echoes messages to the player's chat.
- Log level is controlled by the Mod Menu setting (§4); messages below the threshold are
  suppressed in-game but still written to the log file.
- All `System.out` / `System.err` calls have been migrated to `WDLogger`.

---

## 8. Internationalisation (i18n) ✅

**Goal:** Ship translation files for four locales; fall back to English.

| Code | Language | File |
|---|---|---|
| `en_us` | English (default) | `assets/worlddownloader/lang/en_us.json` |
| `zh_cn` | Simplified Chinese | `assets/worlddownloader/lang/zh_cn.json` |
| `zh_tw` | Traditional Chinese | `assets/worlddownloader/lang/zh_tw.json` |
| `ja_jp` | Japanese | `assets/worlddownloader/lang/ja_jp.json` |

All user-visible strings (UI labels, chat messages, keybinding names) use translation keys.

---

## 9. Entity Download *(low priority)* 🔲

### 9a. Decorative Entities
Download and save paintings, item frames, and armour stands that are present in received chunks.

### 9b. All Entities
Download any entity (mobs, animals, etc.) visible in received chunks and write them to the region's entity data.

---

## 10. Block Entity (Tile Entity) Data *(low priority)* 🔲

Download and persist block entity data (chest inventories, furnace state, etc.) embedded in chunk data.
This largely overlaps with the existing `ContainerTracker` work; the goal is to make it robust and complete.

---

## Implementation Notes

- Items §1–§5 and §7–§8 are **complete**.
- Items relating to multi-dimension support (§9, §10) can build on the dimension-aware
  `ChunkListener` and `Exporter` that are already in place.
- §6 (in-game UI) is the main remaining priority; it depends on the mapping table (§5, done).
- LibGUI is already a required dependency; use it for all new screens (§6).
- Export runs on a background daemon thread (`WDL-Export`) to avoid freezing the game.
  Progress is reported via `WDLogger` (and therefore in-game chat at INFO level).
- The dirty-check mechanism (`CapturedChunk.capturedAtMs` vs `WorldMetadata.chunkUpdateTimes`)
  ensures that unchanged chunks are not re-written on every periodic sync.
- The dimension-to-directory mapping follows Minecraft conventions:
  overworld → `region/`, nether → `DIM-1/region/`, end → `DIM1/region/`,
  other dimensions → `dimensions/<namespace>/<path>/region/`.


