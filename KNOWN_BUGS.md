# World Mirror — Known Bugs

This document tracks confirmed bugs in the mod.  
Items marked **Fixed** were resolved and the fix is described.

---

## BUG-1: Container blocks export without item data

**Status:** Fixed in 0.2.0  
**Affected blocks:** Chests, double chests, barrels, hoppers, droppers, dispensers, shulker boxes, and all other container block entities.

### Root cause

When a chunk is received from the server (`ChunkDataS2CPacket`), Minecraft does **not** send container inventories as part of the chunk data — only the block entity type and coordinates are included.  Item contents are only sent later, as separate `InventoryS2CPacket` / `ScreenHandlerSlotUpdateS2CPacket` packets, when the player opens the container.

`ContainerTracker` correctly intercepts these inventory packets and stores the item data keyed by `BlockPos`.  However, the chunk NBT captured by `ChunkListener` at receive-time does **not** contain the items, and the captured NBT was never updated when the container was later opened.  As a result, exported MCA files had container block entities with empty inventories.

### Fix

`Exporter.mergeContainerData()` is now called at export time (on the background export thread) for every chunk being written.  It iterates the chunk's `block_entities` list and overlays the latest `Items` / `CustomName` data from `ContainerTracker` for each matching `BlockPos`.  Because `ContainerTracker.savedContainerData` is a `ConcurrentHashMap`, the read is thread-safe.

---

## BUG-2: Villager and entity data not persisted correctly

**Status:** Known, not yet fixed  
**Affected entities:** Villagers (trade offers, XP), zombified villagers, and other non-trivial entities that rely on data not transferred to the client by default.

### Root cause

There are two related issues:

1. **Entity data scope:** `EntityTracker` captures entities by iterating `ClientWorld.getEntities()`.  The Minecraft client only receives entity data for entities within the client's render/tracking distance.  Villager trade offers (`Offers`) in particular are a server-side field and are **not** sent to the client in the standard entity tracking packets; they are only populated client-side under specific conditions (when the trading screen is open).

2. **Entity region files:** In Minecraft 1.17+, entity data is stored in separate `entities/r.x.z.mca` region files, not in the terrain chunk's region files.  The current exporter writes entity NBT into the `entities` field of the terrain chunk, which is the pre-1.17 format and is ignored by the game when loading a modern world.

### Impact

Entities (including villagers) appear in the exported world but without their full data (trades, inventory, AI state, etc.).  The entities themselves are present because basic position/type data is tracked client-side.

### Planned fix

- Write entities to `entities/r.x.z.mca` using an `EntityChunk` region format writer.
- Investigate whether villager trade data can be captured via packet interception when the player opens the trading screen.
