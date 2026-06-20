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

**Status:** Fixed in 0.2.2 for captured/local container inventories; client-only data limits remain

The exporter now preserves non-empty `Items` lists from previously captured or
locally written block entities when a chunk is recaptured or overwritten by a
newer client-side block entity snapshot with empty inventory data. Container
overlays are snapshotted before deferred/background exports, so normal cache
cleanup no longer drops item data before the writer reaches the chunk.

Default container GUI titles (`container.chest`, `container.chestDouble`) are
also filtered so they do not become persisted `CustomName` tags. Existing
default-name tags are stripped when block entities are merged.

The base block entity NBT still comes from the client-visible chunk/block entity
state plus packet-derived container overlays, so server-only fields may still be
absent or stale when the server never sends them to the client.

**Mod source pointers**

- `src/main/java/net/billstark001/worldmirror/io/ChunkSerializer.java`
  serializes block entities through Minecraft's `SerializableChunkData` path
  and then applies container overlays.
- `src/main/java/net/billstark001/worldmirror/io/BlockEntityNbtSupport.java`
  centralizes block entity serialization, local/cached block entity merging,
  and container overlay application.
- `src/main/java/net/billstark001/worldmirror/io/ChunkExporter.java`
  merges locally written block entity data before overwriting a chunk.
- `src/main/java/net/billstark001/worldmirror/core/ContainerTracker.java`
  stores only packet-derived container inventory overlays; it only learns
  container contents when the player opens a container UI.

**Minecraft / format pointers**

- Minecraft Wiki `Chunk format`: block entities are stored in the chunk root
  `block_entities` list.
- Yarn/API `Chunk`: the chunk owns both `blockEntityNbts` and live
  `blockEntities`, which are distinct sources of persisted vs instantiated
  block entity data.

### BUG-1-2: Large chest inventory order may be reversed on export

**Status:** Fixed in 0.2.2 for vanilla double chests; custom server remaps remain possible

Double chest reconstruction now follows vanilla's `DoubleBlockCombiner` order:
`ChestType.RIGHT` is the first half and receives screen slots `0..26`, while the
second half receives slots `27..53`. The same split is used for full container
content packets and later single-slot update packets.

This can still be wrong only if a server/plugin deliberately remaps the
container inventory order away from vanilla semantics.

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

**Status:** Mitigated in 0.2.2; full server generation state is still unavailable

Exported chunks are written from client chunk state. Since 0.2.2, the serializer
uses Minecraft's `SerializableChunkData` path, which preserves more vanilla
chunk fields that are available on the client, including blending data,
below-zero retrogen, upgrade data, carving masks for proto chunks, filtered
heightmaps, ticks, post-processing data, and section light.

Structure data is still emitted as an empty compound because client worlds do
not provide authoritative structure starts/references, and server-only
generation state may still be absent.

Likely missing or unreliable fields include:

- structure starts/references
- generation status for non-full/proto chunks
- server-only world-generation or noise state not present in client chunks

**Mod source pointers**

- `src/main/java/net/billstark001/worldmirror/io/ChunkSerializer.java`
  uses `SerializableChunkData` but still creates `structures` as an empty
  compound because the client does not expose authoritative structure data.
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

**Status:** Mitigated in 0.2.2; client-side completeness is still not guaranteed

`ChunkSerializer` now follows vanilla `SerializableChunkData` behavior and only
writes heightmaps required by the chunk's persisted status. This is less fragile
than copying every client-side heightmap entry.

The client still may not have every server-side heightmap type or the correct
values after a partial load, so bad or stale heightmaps remain possible in
edge cases.

**Mod source pointers**

- `src/main/java/net/billstark001/worldmirror/io/ChunkSerializer.java` gathers
  heightmaps through the same status-filtered path used by
  `SerializableChunkData`.
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

**Status:** Mitigated in 0.2.2; limited by client-known light data

Chunk serialization now goes through Minecraft's `SerializableChunkData` path
and copies the client light engine's available per-section `BlockLight` and
`SkyLight` data. This is a substantial improvement over writing only the root
`isLightOn` boolean.

The exporter still cannot reconstruct light sections the client has never
received or has already discarded, so stale or missing light data may still be
possible in edge cases.

**Mod source pointers**

- `src/main/java/net/billstark001/worldmirror/mixin/ChunkDataMixin.java`
  listens to `ClientboundLevelChunkWithLightPacket`.
- `src/main/java/net/billstark001/worldmirror/io/ChunkSerializer.java`
  uses `SerializableChunkData.SectionData` with client light-engine
  `DataLayer` copies for `BlockLight` and `SkyLight` when available.

**Minecraft / format pointers**

- Minecraft Wiki `Chunk format` documents per-section `BlockLight` and
  `SkyLight`.
- Yarn/API `Chunk` exposes chunk light state separately from block states and
  heightmaps, including `chunkSkyLight`.
