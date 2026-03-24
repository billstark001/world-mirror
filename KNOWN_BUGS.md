# World Mirror — Known Bugs

This document tracks confirmed bugs in the mod.  
Items marked **Fixed** were resolved and the fix is described.

---

## BUG-1: Container blocks export without item data [Fixed in 0.2.0]

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
