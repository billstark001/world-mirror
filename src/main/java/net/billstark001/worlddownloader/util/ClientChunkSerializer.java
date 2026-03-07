package net.billstark001.worlddownloader.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.nbt.*;
import net.minecraft.util.Nameable;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.*;
import net.minecraft.world.Heightmap;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.RegistryKeys;

@Environment(EnvType.CLIENT)
public class ClientChunkSerializer {
    public static NbtCompound serialize(ClientWorld world, Chunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        NbtCompound chunkNbt = new NbtCompound();


        chunkNbt.putInt("DataVersion", SharedConstants.getGameVersion().dataVersion().id());


        chunkNbt.putInt("xPos", chunkPos.x);
        chunkNbt.putInt("zPos", chunkPos.z);


        chunkNbt.putLong("InhabitedTime", chunk.getInhabitedTime());


        Registry<ChunkStatus> chunkStatusRegistry = world.getRegistryManager().getOrThrow(RegistryKeys.CHUNK_STATUS);
        Identifier statusId = chunkStatusRegistry.getId(chunk.getStatus());
        chunkNbt.putString("Status", (statusId != null) ? statusId.toString() : "minecraft:full");


        NbtCompound heightmaps = new NbtCompound();
        for (Map.Entry<Heightmap.Type, Heightmap> entry : chunk.getHeightmaps()) {
            heightmaps.put(entry.getKey().getId(), new NbtLongArray(entry.getValue().asLongArray()));
        }
        chunkNbt.put("Heightmaps", heightmaps);


        NbtList sections = new NbtList();
        Registry<Biome> biomeRegistry = world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
        int minSectionY = world.getBottomSectionCoord();

        for (int y = minSectionY; y < world.getTopSectionCoord(); y++) {
            ChunkSection section = chunk.getSectionArray()[world.sectionCoordToIndex(y)];
            if (section != null && !section.isEmpty()) {
                NbtCompound sectionNbt = new NbtCompound();
                sectionNbt.putByte("Y", (byte) y);


                PalettedContainer<BlockState> blockContainer = section.getBlockStateContainer();
                sectionNbt.put("block_states", serializeBlockStates(blockContainer, world.getRegistryManager()));


                ReadableContainer<RegistryEntry<Biome>> biomeContainer = section.getBiomeContainer();
                sectionNbt.put("biomes", serializeBiomes(biomeContainer, biomeRegistry));

                sections.add(sectionNbt);
            }
        }
        chunkNbt.put("sections", sections);


        NbtList blockEntities = new NbtList();
        for (BlockPos pos : chunk.getBlockEntityPositions()) {
            BlockEntity be = chunk.getBlockEntity(pos);
            if (be != null) {
                try {
                    NbtCompound beNbt = serializeBlockEntityWithItems(be, world.getRegistryManager());
                    if (beNbt != null) {
                        blockEntities.add(beNbt);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to serialize block entity at " + pos + ": " + e.getMessage());
                }
            }
        }
        chunkNbt.put("block_entities", blockEntities);


        chunkNbt.put("structures", new NbtCompound());


        chunkNbt.putBoolean("isLightOn", chunk.isLightOn());


        chunkNbt.putLong("LastUpdate", System.currentTimeMillis() / 1000L);

        return chunkNbt;
    }


    private static NbtCompound serializeBlockEntityWithItems(BlockEntity blockEntity, RegistryWrapper.WrapperLookup registryLookup) {
        try {
            NbtCompound beNbt = blockEntity.createNbtWithIdentifyingData(registryLookup);


            beNbt = ContainerTracker.enhanceBlockEntityWithContainerData(blockEntity, beNbt);


            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.world != null) {

                    Registry<BlockEntityType<?>> blockEntityTypeRegistry = client.world.getRegistryManager().getOrThrow(RegistryKeys.BLOCK_ENTITY_TYPE);
                    if (blockEntityTypeRegistry != null) {
                        Identifier beTypeId = blockEntityTypeRegistry.getId(blockEntity.getType());
                        if (beTypeId != null) {
                            beNbt.putString("id", beTypeId.toString());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("⚠ Failed to get block entity type ID: " + e.getMessage());
            }


            BlockPos pos = blockEntity.getPos();
            beNbt.putInt("x", pos.getX());
            beNbt.putInt("y", pos.getY());
            beNbt.putInt("z", pos.getZ());


            try {
                NbtCompound additionalData = blockEntity.createNbtWithIdentifyingData(registryLookup);


                if (additionalData.contains("Items") && !beNbt.contains("Items")) {
                    beNbt.put("Items", additionalData.get("Items"));
                    System.out.println("✅ Saved items for " + blockEntity.getType().toString() + " at " + blockEntity.getPos());
                }


                for (String key : new String[]{"inventory", "Inventory", "items", "Contents"}) {
                    if (additionalData.contains(key) && !beNbt.contains(key)) {
                        beNbt.put(key, additionalData.get(key));
                        System.out.println("✅ Saved " + key + " for " + blockEntity.getType().toString());
                    }
                }


                if (additionalData.contains("CustomName") && !beNbt.contains("CustomName")) {
                    beNbt.put("CustomName", additionalData.get("CustomName"));
                } else if (additionalData.contains("custom_name") && !beNbt.contains("CustomName")) {
                    beNbt.put("CustomName", additionalData.get("custom_name"));
                }


                if (additionalData.contains("Lock")) {
                    beNbt.put("Lock", additionalData.get("Lock"));
                }


                if (blockEntity instanceof Nameable) {
                    Nameable nameable = (Nameable) blockEntity;
                    if (nameable.hasCustomName()) {
                        Text customName = nameable.getCustomName();
                        if (customName != null) {
                            try {
                                String jsonName = customName.getString();
                                beNbt.putString("CustomName", jsonName);
                            } catch (Exception e) {
                                String simpleName = "\"" + customName.getString() + "\"";
                                beNbt.putString("CustomName", simpleName);
                            }

                        }
                    }
                }

            } catch (Exception e) {
                System.err.println("⚠ Failed to get additional NBT data for block entity: " + e.getMessage());
            }

            return beNbt;
        } catch (Exception e) {
            System.err.println("❌ Failed to serialize block entity with items: " + e.getMessage());

            try {
                return blockEntity.createNbtWithIdentifyingData(registryLookup);
            } catch (Exception fallbackException) {
                System.err.println("❌ Fallback serialization also failed: " + fallbackException.getMessage());
                return null;
            }
        }
    }

    private static NbtCompound serializeBlockStates(PalettedContainer<BlockState> container, RegistryWrapper.WrapperLookup registryLookup) {
        NbtCompound blockStatesNbt = new NbtCompound();
        Registry<Block> blockRegistry = (Registry<Block>) registryLookup.getOrThrow(RegistryKeys.BLOCK);


        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> paletteIndices = new HashMap<>();
        List<Integer> rawData = new ArrayList<>();


        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int paletteIndex;
                    BlockState state = container.get(x, y, z);

                    if (!paletteIndices.containsKey(state)) {
                        paletteIndex = palette.size();
                        palette.add(state);
                        paletteIndices.put(state, paletteIndex);
                    } else {
                        paletteIndex = paletteIndices.get(state).intValue();
                    }
                    rawData.add(paletteIndex);
                }
            }
        }


        int bitsPerEntry = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));


        long[] packedData = packIntoLongArray(rawData, bitsPerEntry);


        NbtList paletteNbt = new NbtList();
        for (BlockState state : palette) {
            NbtCompound stateNbt = new NbtCompound();
            Identifier blockId = blockRegistry.getId(state.getBlock());
            stateNbt.putString("Name", blockId.toString());

            if (!state.getEntries().isEmpty()) {
                NbtCompound properties = new NbtCompound();
                state.getEntries().forEach((property, value) -> properties.putString(property.getName(), value.toString()));

                stateNbt.put("Properties", properties);
            }
            paletteNbt.add(stateNbt);
        }
        blockStatesNbt.put("palette", paletteNbt);
        blockStatesNbt.putLongArray("data", packedData);

        return blockStatesNbt;
    }

    private static NbtCompound serializeBiomes(ReadableContainer<RegistryEntry<Biome>> container, Registry<Biome> biomeRegistry) {
        NbtCompound biomesNbt = new NbtCompound();


        List<RegistryEntry<Biome>> palette = new ArrayList<>();
        Map<RegistryEntry<Biome>, Integer> paletteIndices = new HashMap<>();
        List<Integer> rawData = new ArrayList<>();


        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    int paletteIndex;
                    RegistryEntry<Biome> biome = container.get(x, y, z);

                    if (!paletteIndices.containsKey(biome)) {
                        paletteIndex = palette.size();
                        palette.add(biome);
                        paletteIndices.put(biome, paletteIndex);
                    } else {
                        paletteIndex = paletteIndices.get(biome).intValue();
                    }
                    rawData.add(paletteIndex);
                }
            }
        }


        int bitsPerEntry = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));


        long[] packedData = packIntoLongArray(rawData, bitsPerEntry);


        NbtList paletteNbt = new NbtList();
        for (RegistryEntry<Biome> biomeEntry : palette) {
            biomeEntry.getKey().ifPresent(key -> paletteNbt.add(NbtString.of(key.getValue().toString())));
        }

        biomesNbt.put("palette", paletteNbt);
        biomesNbt.putLongArray("data", packedData);

        return biomesNbt;
    }

    private static long[] packIntoLongArray(List<Integer> data, int bitsPerEntry) {
        int valuesPerLong = 64 / bitsPerEntry;
        int arraySize = (data.size() + valuesPerLong - 1) / valuesPerLong;
        long[] result = new long[arraySize];

        long mask = (1L << bitsPerEntry) - 1L;

        for (int i = 0; i < data.size(); i++) {
            int arrayIndex = i / valuesPerLong;
            int bitIndex = i % valuesPerLong * bitsPerEntry;
            result[arrayIndex] = result[arrayIndex] | (data.get(i).intValue() & mask) << bitIndex;
        }

        return result;
    }
}
