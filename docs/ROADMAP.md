# World Downloader — Roadmap

This document outlines planned features and improvements for the World Downloader mod.
Items are grouped by theme and ordered roughly by priority.

---

## 1. Download Toggle

**Goal:** Replace the single-shot export with a persistent, user-controlled download session.

- Add a keybinding (and a button in the in-game UI — see §6) to **start / stop** downloading.
- While active, periodically compare received chunk data against the saved copy (configurable interval, default every *n* seconds) and write only changed chunks.
- Display the current state (active / inactive) in the HUD or chat when it changes.

---

## 2. World Metadata

**Goal:** Each mirrored world carries enough metadata to resume and manage downloads.

Every downloaded world must store:

| Field | Description |
|---|---|
| `modVersion` | Mod version that created / last updated the world |
| `sourceType` | `singleplayer` or `server` |
| `sourceId` | World folder name or server address |
| `lastSyncTime` | Timestamp of the most recent sync session |
| `chunkUpdateTime` | Per-chunk last-modified timestamp |

Metadata is written to a dedicated file (e.g. `wdl_meta.json`) inside the mirror world folder so it survives being copied into `saves/`.

---

## 3. Chunk Conflict Resolution

**Goal:** Handle chunks that have been modified locally (e.g. the user opened the mirror world in singleplayer and made changes) before the next sync.

Strategies to implement:

| Strategy | Behaviour |
|---|---|
| `overwrite` | Always replace local chunk with server version |
| `ignore` | Keep local version, discard incoming server data |
| `manual` | Prompt the user; sub-options on decision: **overwrite now**, **ignore and delete flag**, **ignore and decide later** (allows copying data out first) |

- A well-defined interface (`ConflictResolver`) must be provided so advanced strategies can be added later without changing core sync logic.
- The active strategy is configurable per-world (see §5) with a global default in mod settings (see §4).

---

## 4. Mod Menu Settings

**Goal:** Add a proper settings screen accessible from Mod Menu.

Settings to expose:

- **Default save location**: `downloaded` folder (current behaviour) or `saves/` (playable immediately).
- **Sync interval**: How often the mod checks for updated chunks while downloading is active.
- **In-game log level**: `DEBUG`, `INFO`, or `WARNING` (see §7).
- **Default conflict strategy**: global fallback when no per-world override is set.

A `ModMenuApiImpl` entry point must be registered so the screen appears in Mod Menu's mod list.

---

## 5. World–Mirror Mapping Table

**Goal:** Maintain a persistent mapping between source worlds / servers and their local mirror folders.

- Stored in the Minecraft run directory (e.g. `config/worlddownloader/mirrors.json`).
- Each entry records: `sourceId`, `mirrorPath`, `conflictStrategy`, `saveLocation`.
- On first use of a world or server, the user is prompted to confirm / customise the mirror path.
- The mapping is read on login and written on change.

---

## 6. In-Game Status UI

**Goal:** Provide a LibGUI-based screen showing sync state and allowing the user to take actions.

Screen contents:

- **Source info**: world/server name and type.
- **Metadata summary**: last sync time, total chunks tracked.
- **Download status**: active / inactive toggle button.
- **Conflict queue**: list of chunks awaiting manual resolution, with action buttons (overwrite / ignore / defer).
- **Per-world settings**: override save location and conflict strategy.

The screen opens via a dedicated keybinding and is also accessible from the Mod Menu settings screen.

---

## 7. In-Game Logging Utility

**Goal:** Surface important log messages inside the game, not only in the console.

- Add a `WDLogger` helper in `util/` wrapping SLF4J and optionally echoing to the in-game chat or HUD overlay.
- Log level is controlled by the Mod Menu setting (§4); messages below the threshold are suppressed in-game but still written to the log file.
- All existing `System.out` calls must be migrated to `WDLogger`.

---

## 8. Internationalisation (i18n)

**Goal:** Ship translation files for four locales; fall back to English.

Locales to include:

| Code | Language |
|---|---|
| `en_us` | English (default) |
| `zh_cn` | Simplified Chinese |
| `zh_tw` | Traditional Chinese |
| `ja_jp` | Japanese |

All user-visible strings (UI labels, chat messages, keybinding names) must use translation keys.

---

## 9. Entity Download *(low priority)*

### 9a. Decorative Entities
Download and save paintings, item frames, and armour stands that are present in received chunks.

### 9b. All Entities
Download any entity (mobs, animals, etc.) visible in received chunks and write them to the region's entity data.

---

## 10. Block Entity (Tile Entity) Data *(low priority)*

Download and persist block entity data (chest inventories, furnace state, etc.) embedded in chunk data.
This largely overlaps with the existing `ContainerTracker` work; the goal is to make it robust and complete.

---

## Implementation Notes

- The `ConflictResolver` interface (§3) and the mapping table (§5) should be designed first, as other features depend on them.
- LibGUI is already a required dependency; use it for all new screens.
- The existing one-shot export keybinding can remain as a convenience action inside the new UI (§6).
