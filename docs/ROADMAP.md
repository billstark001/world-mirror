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

## 6. In-Game Status UI ✅

**Goal:** Provide a LibGUI-based screen showing sync state and allowing the user to take actions.

Screen contents (implemented in `ui/StatusScreen.java`):

- **Source info**: world/server name and type, local mirror folder name.
- **Metadata summary**: last sync time, total chunks cached across all dimensions.
- **Download status**: active / inactive label; export running / idle label.
- **Action buttons**:
  - *Start / Stop Download* — toggles download mode (same as **P** key).
  - *Export Now* — triggers immediate background export (same as **O** key).
  - *Clear Data* — clears all cached chunks, entities, and containers.
- **Conflict queue**: pending-conflict count with *Overwrite All* / *Ignore All* buttons
  (only visible when the `MANUAL` conflict strategy has queued conflicts).
- **Per-world settings**: cycle buttons to override save location and conflict strategy
  for the current world/server, stored in `mirrors.json` via `MirrorMapping`.
- **Global Settings** button → opens the existing `ConfigScreen`.
- **Done** button → closes the screen.

The screen opens via the **I** keybinding (`key.worlddownloader.status`) and is also
accessible via the "Open Status Screen" button in the Mod Menu settings screen.
Reopening recreates the description object so all labels always show current state.

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

## 9. Entity Download ✅

### 9a. Decorative Entities
Paintings, item frames (regular and glow), and armour stands are captured as part of
the general entity snapshot.  Each entity is serialized via `entity.writeNbt()` —
Minecraft's own serialization — which captures the full entity state that the client
has received via tracking packets:

- **Paintings**: `variant` (motive), `facing`, attachment block position (`TileX/Y/Z`)
- **Item frames / Glow item frames**: `Item` (held item stack with full component data),
  `ItemRotation`, `Fixed`
- **Armour stands**: `Pose` (all six limb rotations), equipped items in `ArmorItems` and
  `HandItems`, display flags (`ShowArms`, `NoBasePlate`, `Small`, `Invisible`, `Marker`)

### 9b. All Entities
All non-player entities visible in captured chunks are serialized.  `entity.writeNbt()`
automatically covers every entity type supported by the running Minecraft version:
- **Mobs and animals**: health, equipment (actual item stacks), mob-specific state such
  as breeding age, tame-owner UUID, collar colour, saddle, wool colour, etc.
- **Dropped items**: full item stack with NBT components
- **Projectiles, vehicles, and all other entity types**: all tracked data

Server-side-only fields (e.g. AI path, spawn reinforcements count) are not available on
the client and will be absent or default; this is an inherent limitation of client-side
capture.

---

## 10. Block Entity (Tile Entity) Data ✅

Block entities are serialized via `BlockEntity.createNbtWithIdentifyingData()`, which
writes the entity type identifier, position, and all data the client has received.
For most block entities this includes the full state that the server sends:

- **Signs / Hanging signs**: front and back text, waxed flag, glow-ink flag
- **Beacons**: primary and secondary effect IDs
- **Banners**: all pattern layers
- **Skulls / Heads**: owner profile (for player heads)
- **Lecterns**: stored book item
- **Brushable blocks**: loot table / stored item (if loaded)
- **Beds, candles, cauldrons, and other state-only blocks**: captured as block-state in
  the chunk palette, not as a block entity, so no separate capture is needed

**Container inventories** (chests, barrels, hoppers, furnaces, etc.) are NOT present
in the client-side block entity NBT because Minecraft only sends slot data to the
player when they open the container.  The `ContainerTracker` mixin intercepts these
inventory packets so that items are merged into the block entity NBT on export for
every container the player has opened during the session.

---

## Implementation Notes

- Items §1–§10 are **complete**.
- Multi-dimension support (Overworld, Nether, End, custom dimensions) is implemented in
  the dimension-aware `ChunkListener`, `EntityTracker`, and `Exporter` classes.
- Entity serialization (§9) uses `Entity.writeNbt()` so that all entity types —
  paintings, item frames, armour stands, mobs, animals, dropped items, etc. — are
  covered automatically using the same code path as the Minecraft server.
- Block entity serialization (§10) uses `BlockEntity.createNbtWithIdentifyingData()`,
  which captures all client-available state; container inventories are supplemented by
  `ContainerTracker` which intercepts inventory packets.
- Export runs on a background daemon thread (`WDL-Export`) to avoid freezing the game.
  Progress is reported via `WDLogger` (and therefore in-game chat at INFO level).
- The dirty-check mechanism (`CapturedChunk.capturedAtMs` vs `WorldMetadata.chunkUpdateTimes`)
  ensures that unchanged chunks are not re-written on every periodic sync.
- The dimension-to-directory mapping follows Minecraft conventions:
  overworld → `region/`, nether → `DIM-1/region/`, end → `DIM1/region/`,
  other dimensions → `dimensions/<namespace>/<path>/region/`.


