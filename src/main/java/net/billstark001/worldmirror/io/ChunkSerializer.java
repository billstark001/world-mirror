package net.billstark001.worldmirror.io;

import java.util.List;
import java.util.Map;

import net.billstark001.worldmirror.core.ContainerTracker;
import net.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.PalettedContainerRO.PackedData;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

@Environment(EnvType.CLIENT)
public class ChunkSerializer {

    /**
     * Returns {@code true} if the chunk has no non-air blocks and no block entities,
     * which means the server sent an empty placeholder chunk (e.g. beyond its own
     * render distance).  Such chunks are not worth caching or exporting.
     */
    public static boolean isChunkEmpty(ChunkAccess chunk) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (section != null && !section.hasOnlyAir()) {
                return false;
            }
        }
        return chunk.getBlockEntitiesPos().isEmpty();
    }

    public static CompoundTag serialize(ClientLevel world, ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        CompoundTag chunkNbt = new CompoundTag();


        chunkNbt.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());


        chunkNbt.putInt("xPos", chunkPos.x());
        chunkNbt.putInt("zPos", chunkPos.z());


        chunkNbt.putLong("InhabitedTime", chunk.getInhabitedTime());


        Registry<ChunkStatus> chunkStatusRegistry = world.registryAccess().lookupOrThrow(Registries.CHUNK_STATUS);
        Identifier statusId = chunkStatusRegistry.getKey(chunk.getPersistedStatus());
        chunkNbt.putString("Status", (statusId != null) ? statusId.toString() : "minecraft:full");


        CompoundTag heightmaps = new CompoundTag();
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            heightmaps.put(entry.getKey().getSerializationKey(), new LongArrayTag(entry.getValue().getRawData()));
        }
        chunkNbt.put("Heightmaps", heightmaps);


        ListTag sections = new ListTag();
        Registry<Biome> biomeRegistry = world.registryAccess().lookupOrThrow(Registries.BIOME);
        int minSectionY = world.getMinSectionY();

        for (int y = minSectionY; y < world.getMaxSectionY(); y++) {
            LevelChunkSection section = chunk.getSections()[world.getSectionIndexFromSectionY(y)];
            if (section != null && !section.hasOnlyAir()) {
                CompoundTag sectionNbt = new CompoundTag();
                sectionNbt.putByte("Y", (byte) y);


                PalettedContainer<BlockState> blockContainer = section.getStates();
                sectionNbt.put("block_states", serializeBlockStates(blockContainer, world.registryAccess()));


                PalettedContainerRO<Holder<Biome>> biomeContainer = section.getBiomes();
                sectionNbt.put("biomes", serializeBiomes(biomeContainer, biomeRegistry));

                sections.add(sectionNbt);
            }
        }
        chunkNbt.put("sections", sections);


        ListTag blockEntities = new ListTag();
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            BlockEntity be = chunk.getBlockEntity(pos);
            if (be != null) {
                try {
                    CompoundTag beNbt = serializeBlockEntityWithItems(be, world.registryAccess());
                    if (beNbt != null) {
                        blockEntities.add(beNbt);
                    }
                } catch (Exception e) {
                    WMLogger.warn("Failed to serialize block entity at " + pos + ": " + e.getMessage());
                }
            }
        }
        chunkNbt.put("block_entities", blockEntities);


        chunkNbt.put("structures", new CompoundTag());


        chunkNbt.putBoolean("isLightOn", chunk.isLightCorrect());


        chunkNbt.putLong("LastUpdate", System.currentTimeMillis() / 1000L);

        return chunkNbt;
    }


    /**
     * Serializes a block entity to NBT.
     * <p>
     * {@link BlockEntity#saveWithFullMetadata} writes the entity type id,
     * coordinates, and all client-side data (sign text, beacon effects, banner
     * patterns, skull owner, etc.) via {@code writeNbt}.  The only data that is NOT
     * available on the client is item inventories for containers the player has never
     * opened; those are supplied by {@link ContainerTracker}, which intercepts the
     * inventory packets when the player opens a container.
     */
    private static CompoundTag serializeBlockEntityWithItems(BlockEntity blockEntity, HolderLookup.Provider registryLookup) {
        try {
            // Get full block entity NBT: type id, x/y/z, and all client-tracked state
            CompoundTag beNbt = blockEntity.saveWithFullMetadata(registryLookup);

            // Merge container item data captured when the player opened this container
            beNbt = ContainerTracker.enhanceBlockEntityWithContainerData(blockEntity, beNbt);

            return beNbt;
        } catch (Exception e) {
            WMLogger.warn("Failed to serialize block entity at "
                    + blockEntity.getBlockPos() + ": " + e.getMessage());
            return null;
        }
    }

    private static CompoundTag serializeBlockStates(PalettedContainer<BlockState> container, HolderLookup.Provider registryLookup) {
        CompoundTag blockStatesNbt = new CompoundTag();
        Registry<Block> blockRegistry = (Registry<Block>) registryLookup.lookupOrThrow(Registries.BLOCK);
        PackedData<BlockState> packedData = container.pack(Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));

        ListTag paletteNbt = new ListTag();
        for (BlockState state : packedData.paletteEntries()) {
            CompoundTag stateNbt = new CompoundTag();
            Identifier blockId = blockRegistry.getKey(state.getBlock());
            if (blockId != null) {
                stateNbt.putString("Name", blockId.toString());
            } else {
                stateNbt.putString("Name", "minecraft:air");
            }

            List<Property.Value<?>> values = state.getValues().toList();
            if (!values.isEmpty()) {
                CompoundTag properties = new CompoundTag();
                values.forEach(value -> properties.putString(value.property().getName(), value.valueName()));

                stateNbt.put("Properties", properties);
            }
            paletteNbt.add(stateNbt);
        }
        blockStatesNbt.put("palette", paletteNbt);
        packedData.storage().ifPresent(storage -> blockStatesNbt.putLongArray("data", storage.toArray()));

        return blockStatesNbt;
    }

    private static CompoundTag serializeBiomes(PalettedContainerRO<Holder<Biome>> container, Registry<Biome> biomeRegistry) {
        CompoundTag biomesNbt = new CompoundTag();
        PackedData<Holder<Biome>> packedData = container.pack(Strategy.createForBiomes(biomeRegistry.asHolderIdMap()));

        ListTag paletteNbt = new ListTag();
        for (Holder<Biome> biomeEntry : packedData.paletteEntries()) {
            String biomeId = biomeEntry.unwrapKey()
                    .map(key -> key.identifier().toString())
                    .orElseGet(() -> {
                        Identifier id = biomeRegistry.getKey(biomeEntry.value());
                        return id != null ? id.toString() : "minecraft:plains";
                    });
            paletteNbt.add(StringTag.valueOf(biomeId));
        }

        biomesNbt.put("palette", paletteNbt);
        packedData.storage().ifPresent(storage -> biomesNbt.putLongArray("data", storage.toArray()));

        return biomesNbt;
    }
}
