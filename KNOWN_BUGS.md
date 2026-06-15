# World Mirror - Known Bugs

This document tracks confirmed bugs in the mod.
Items marked **Fixed** were resolved and the fix is described.

External references used for the notes below:

- Minecraft Wiki, Chunk format: https://minecraft.wiki/w/Chunk_format
- Minecraft Wiki, Region file format: https://minecraft.wiki/w/Region_file_format
- Yarn/API, `net.minecraft.world.chunk.Chunk`:
  https://maven.fabricmc.net/docs/yarn-1.21.1+build.1/net/minecraft/world/chunk/Chunk.html

---

## BUG-1: Container blocks export without item data [Fixed in 0.2.0]

### BUG-1-1: Block entity data may be overridden with empty data on export

**Status:** Known, not fully fixed

The exporter merges cached container inventories into `block_entities`, but the
base block entity NBT still comes from the client-side `BlockEntity` instance.
Some server-only fields may be absent, stale, or overwritten by an empty
client-side value.

**Mod source pointers**

- `src/main/java/net/billstark001/worldmirror/io/ChunkSerializer.java`
  serializes block entities through `BlockEntity.saveWithFullMetadata(...)`.
- `src/main/java/net/billstark001/worldmirror/io/ChunkExporter.java`
  merges cached container data into the exported `block_entities` list.
- `src/main/java/net/billstark001/worldmirror/core/ContainerTracker.java`
  only learns container contents when the player opens a container UI.

**Minecraft / format pointers**

- Minecraft Wiki `Chunk format`: block entities are stored in the chunk root
  `block_entities` list.
- Yarn/API `Chunk`: the chunk owns both `blockEntityNbts` and live
  `blockEntities`, which are distinct sources of persisted vs instantiated
  block entity data.

### BUG-1-2: Large chest inventory order may be reversed on export

**Status:** Known, not fully fixed

Double chest reconstruction is inferred from the block state and the screen
slot order. This may still be wrong when the client hit result points at the
opposite half, when the screen contents were opened from unusual interaction
paths, or when a server/plugin remaps the inventory.

---

## BUG-2: Entity data is not persisted correctly

**Status:** Known, not fixed

**Affected entities:** Villagers, zombified villagers, mobs with non-trivial AI
state, vehicles, paintings, item frames, dropped items, and other entities that
rely on data not transferred to the client by default.

### Root cause

1. `EntityTracker` captures entities by iterating client-rendered entities. The
   client only knows entities inside its tracking/render scope, and many
   server-side fields are never sent in normal entity tracking packets.

2. The current serializer calls `Entity.saveWithoutId(...)`, but the local code
   does not add the entity `id` tag afterward. Modern entity region chunks need
   entity compounds with type ids.

3. Entity data belongs in separate `entities/r.X.Z.mca` files in modern worlds.
   The exporter now writes `entities/`, but the captured NBT is still incomplete.

**Mod source pointers**

- `src/main/java/net/billstark001/worldmirror/core/EntityTracker.java`
  captures from `world.entitiesForRendering()` and serializes via
  `Entity.saveWithoutId(...)`.
- `src/main/java/net/billstark001/worldmirror/io/ChunkExporter.java`
  writes entity chunks into the dimension `entities` directory.

**Minecraft / format pointers**

- Minecraft Wiki `Chunk format` points entity storage to the entity format page.
- `src/main/java/io/github/ensgijs/nbt/mca/DataVersion.java` notes that entities
  were pulled out of terrain `region/r.X.Z.mca` files into `entities/r.X.Z.mca`.
- `src/main/java/io/github/ensgijs/nbt/mca/McaEntitiesFile.java` is the local MCA
  abstraction for modern entity region files.

### Planned fix

- Add the entity type `id` after `saveWithoutId(...)`.
- Keep per-chunk entity snapshots instead of replacing each dimension snapshot
  with only the currently rendered entities.
- Investigate packet-level capture for server-only fields such as villager
  trades when relevant screens are opened.

---

## BUG-3: World generation / noise metadata is incomplete

**Status:** Known, not fixed

Exported chunks are written as terrain NBT assembled from client chunk state.
The serializer currently writes block states, biomes, heightmaps, a blank
`structures` compound, and a status string, but it does not preserve all
generation metadata used by proto-chunks or blending/migration.

Likely missing or unreliable fields include:

- `yPos`
- `blending_data`
- `below_zero_retrogen`
- carving masks and post-processing data
- structure starts/references
- generation status for non-full/proto chunks

**Mod source pointers**

- `src/main/java/net/billstark001/worldmirror/io/ChunkSerializer.java`
  currently creates `structures` as an empty compound and does not write
  `yPos`, `blending_data`, or `below_zero_retrogen`.
- `src/main/java/io/github/ensgijs/nbt/mca/TerrainChunkBase.java` contains local
  path mappings for `Status`, `blending_data`, and related terrain tags.

**Minecraft / format pointers**

- Minecraft Wiki `Chunk format` documents root chunk tags such as `Status`,
  `sections`, `structures`, and `blending_data`.
- Yarn/API `Chunk` exposes `chunkNoiseSampler`, `blendingData`, and
  `heightmaps` as separate chunk fields; those are not all serialized by this
  mod today.

---

## BUG-4: Heightmaps may be incomplete or invalid

**Status:** Known, not fixed

`ChunkSerializer` copies every client-side heightmap entry with
`Heightmap.getRawData()`. This is fragile because the client may not have all
server-side heightmap types, proto-chunk heightmaps, or correct values after a
partial load. Heightmaps are packed arrays with strict width/offset rules, so an
incorrect set can load but still produce bad surface, lighting, spawn, or map
behavior.

**Mod source pointers**

- `src/main/java/net/billstark001/worldmirror/io/ChunkSerializer.java` writes
  the chunk root `Heightmaps` compound from `chunk.getHeightmaps()`.
- `src/main/java/io/github/ensgijs/nbt/mca/TerrainChunkBase.java` maps
  `Heightmaps` paths for modern terrain chunks.
- `src/main/java/io/github/ensgijs/nbt/mca/util/LongArrayTagPackedIntegers.java`
  documents the packed integer handling needed for heightmaps.

**Minecraft / format pointers**

- Minecraft Wiki `Chunk format` describes `Heightmaps` as packed values.
- Yarn/API `Chunk` exposes `getHeightmaps()`, `setHeightmap(...)`,
  `getHeightmap(...)`, and `sampleHeightmap(...)`.

---

## BUG-5: Light data is incomplete

**Status:** Known, not fixed

The exported chunk currently writes only the root `isLightOn` boolean. It does
not serialize per-section `BlockLight` and `SkyLight`, nor does it preserve the
full light update data sent with chunk/light packets. The result may be dark,
overbright, or recalculated incorrectly when loaded as a singleplayer world.

**Mod source pointers**

- `src/main/java/net/billstark001/worldmirror/mixin/ChunkDataMixin.java`
  listens to `ClientboundLevelChunkWithLightPacket`.
- `src/main/java/net/billstark001/worldmirror/io/ChunkSerializer.java`
  writes `isLightOn` but does not write section `BlockLight` or `SkyLight`.

**Minecraft / format pointers**

- Minecraft Wiki `Chunk format` documents per-section `BlockLight` and
  `SkyLight`.
- Yarn/API `Chunk` exposes chunk light state separately from block states and
  heightmaps, including `chunkSkyLight`.
