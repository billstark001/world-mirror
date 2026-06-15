package net.billstark001.worldmirror.io;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Optionull;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import java.util.*;

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

    /**
     * Matches the implementation of the corresponding method in {@link SerializableChunkData}
     * as of Minecraft version 1.21.11.
     * * <p>This implementation substitutes or omits world structure information, as {@code ClientWorld}
     * does not provide such data. This logic was previously handled by {@code net.minecraft.world.ChunkSerializer}.</p>
     * * <p><b>Maintenance Note:</b> During future mod updates, it is critical to ensure this
     * implementation maintains parity with the latest upstream logic in {@code SerializedChunk}.</p>
     */
    public static SerializableChunkData createSerializedChunk(ClientLevel world, ChunkAccess chunk) {
        if (!chunk.canBeSerialized()) {
            throw new IllegalArgumentException("Chunk can't be serialized: " + chunk);
        } else {
            ChunkPos chunkPos = chunk.getPos();
            List<SerializableChunkData.SectionData> list = new ArrayList<>();
            LevelChunkSection[] chunkSections = chunk.getSections();
            LevelLightEngine lightingProvider = world.getChunkSource().getLightEngine();

            for(int i = lightingProvider.getMinLightSection(); i < lightingProvider.getMaxLightSection(); ++i) {
                int j = chunk.getSectionIndexFromSectionY(i);
                boolean bl = j >= 0 && j < chunkSections.length;
                DataLayer chunkNibbleArray = lightingProvider.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(chunkPos, i));
                DataLayer chunkNibbleArray2 = lightingProvider.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(chunkPos, i));
                DataLayer chunkNibbleArray3 = chunkNibbleArray != null && !chunkNibbleArray.isEmpty() ? chunkNibbleArray.copy() : null;
                DataLayer chunkNibbleArray4 = chunkNibbleArray2 != null && !chunkNibbleArray2.isEmpty() ? chunkNibbleArray2.copy() : null;
                if (bl || chunkNibbleArray3 != null || chunkNibbleArray4 != null) {
                    LevelChunkSection chunkSection = bl ? chunkSections[j].copy() : null;
                    list.add(new SerializableChunkData.SectionData(i, chunkSection, chunkNibbleArray3, chunkNibbleArray4));
                }
            }

            List<CompoundTag> list2 = new ArrayList<>(chunk.getBlockEntitiesPos().size());

            for(BlockPos blockPos : chunk.getBlockEntitiesPos()) {
                CompoundTag nbtCompound = chunk.getBlockEntityNbtForSaving(blockPos, world.registryAccess());
                if (nbtCompound != null) {
                    list2.add(nbtCompound);
                }
            }

            List<CompoundTag> list3 = new ArrayList<>();
            long[] ls = null;
            if (chunk.getPersistedStatus().getChunkType() == ChunkType.PROTOCHUNK) {
                ProtoChunk protoChunk = (ProtoChunk)chunk;
                list3.addAll(protoChunk.getEntities());
                CarvingMask carvingMask = protoChunk.getCarvingMask();
                if (carvingMask != null) {
                    ls = carvingMask.toArray();
                }
            }

            Map<Heightmap.Types, long[]> map = new EnumMap<>(Heightmap.Types.class);

            for(Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
                if (chunk.getPersistedStatus().heightmapsAfter().contains(entry.getKey())) {
                    long[] ms = entry.getValue().getRawData();
                    map.put(entry.getKey(), (ms).clone());
                }
            }

            ChunkAccess.PackedTicks tickSchedulers = chunk.getTicksForSerialization(world.getGameTime());
            ShortList[] shortLists = Arrays.stream(chunk.getPostProcessing())
                    .map((postProcessings) -> postProcessings != null && !postProcessings.isEmpty() ? new ShortArrayList(postProcessings) : null)
                    .toArray(ShortList[]::new);
            CompoundTag nbtCompound2 = createEmptyStructuresCompound(); // Client worlds do not contain structure info
            return new SerializableChunkData(
                    world.palettedContainerFactory(), chunkPos, chunk.getMinSectionY(),
                    world.getGameTime(), chunk.getInhabitedTime(),
                    chunk.getPersistedStatus(),
                    Optionull.map(chunk.getBlendingData(), blendingData -> blendingData != null ? blendingData.pack() : null),
                    chunk.getBelowZeroRetrogen(),
                    chunk.getUpgradeData().copy(), ls, map, tickSchedulers, shortLists, chunk.isLightCorrect(), list, list3, list2, nbtCompound2
            );
        }
    }

    private static CompoundTag createEmptyStructuresCompound() {
        CompoundTag nbtCompound = new CompoundTag();
        CompoundTag nbtCompound2 = new CompoundTag();

        nbtCompound.put("starts", nbtCompound2);
        CompoundTag nbtCompound3 = new CompoundTag();

        nbtCompound.put("References", nbtCompound3);
        return nbtCompound;
    }

    public static CompoundTag serialize(ClientLevel world, ChunkAccess chunk) {
        return createSerializedChunk(world, chunk).write();
    }
}
