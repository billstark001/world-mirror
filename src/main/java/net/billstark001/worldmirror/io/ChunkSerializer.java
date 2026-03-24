package net.billstark001.worldmirror.io;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Nullables;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.*;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.gen.carver.CarvingMask;

import java.util.*;

@Environment(EnvType.CLIENT)
public class ChunkSerializer {

    /**
     * Returns {@code true} if the chunk has no non-air blocks and no block entities,
     * which means the server sent an empty placeholder chunk (e.g. beyond its own
     * render distance).  Such chunks are not worth caching or exporting.
     */
    public static boolean isChunkEmpty(Chunk chunk) {
        for (ChunkSection section : chunk.getSectionArray()) {
            if (section != null && !section.isEmpty()) {
                return false;
            }
        }
        return chunk.getBlockEntityPositions().isEmpty();
    }

    /**
     * Matches the implementation of the corresponding method in {@link SerializedChunk}
     * as of Minecraft version 1.21.11.
     * * <p>This implementation substitutes or omits world structure information, as {@code ClientWorld}
     * does not provide such data. This logic was previously handled by {@code net.minecraft.world.ChunkSerializer}.</p>
     * * <p><b>Maintenance Note:</b> During future mod updates, it is critical to ensure this
     * implementation maintains parity with the latest upstream logic in {@code SerializedChunk}.</p>
     */
    public static SerializedChunk createSerializedChunk(ClientWorld world, Chunk chunk) {
        if (!chunk.isSerializable()) {
            throw new IllegalArgumentException("Chunk can't be serialized: " + chunk);
        } else {
            ChunkPos chunkPos = chunk.getPos();
            List<SerializedChunk.SectionData> list = new ArrayList<>();
            ChunkSection[] chunkSections = chunk.getSectionArray();
            LightingProvider lightingProvider = world.getChunkManager().getLightingProvider();

            for(int i = lightingProvider.getBottomY(); i < lightingProvider.getTopY(); ++i) {
                int j = chunk.sectionCoordToIndex(i);
                boolean bl = j >= 0 && j < chunkSections.length;
                ChunkNibbleArray chunkNibbleArray = lightingProvider.get(LightType.BLOCK).getLightSection(ChunkSectionPos.from(chunkPos, i));
                ChunkNibbleArray chunkNibbleArray2 = lightingProvider.get(LightType.SKY).getLightSection(ChunkSectionPos.from(chunkPos, i));
                ChunkNibbleArray chunkNibbleArray3 = chunkNibbleArray != null && !chunkNibbleArray.isUninitialized() ? chunkNibbleArray.copy() : null;
                ChunkNibbleArray chunkNibbleArray4 = chunkNibbleArray2 != null && !chunkNibbleArray2.isUninitialized() ? chunkNibbleArray2.copy() : null;
                if (bl || chunkNibbleArray3 != null || chunkNibbleArray4 != null) {
                    ChunkSection chunkSection = bl ? chunkSections[j].copy() : null;
                    list.add(new SerializedChunk.SectionData(i, chunkSection, chunkNibbleArray3, chunkNibbleArray4));
                }
            }

            List<NbtCompound> list2 = new ArrayList<>(chunk.getBlockEntityPositions().size());

            for(BlockPos blockPos : chunk.getBlockEntityPositions()) {
                NbtCompound nbtCompound = chunk.getPackedBlockEntityNbt(blockPos, world.getRegistryManager());
                if (nbtCompound != null) {
                    list2.add(nbtCompound);
                }
            }

            List<NbtCompound> list3 = new ArrayList<>();
            long[] ls = null;
            if (chunk.getStatus().getChunkType() == ChunkType.PROTOCHUNK) {
                ProtoChunk protoChunk = (ProtoChunk)chunk;
                list3.addAll(protoChunk.getEntities());
                CarvingMask carvingMask = protoChunk.getCarvingMask();
                if (carvingMask != null) {
                    ls = carvingMask.getMask();
                }
            }

            Map<Heightmap.Type, long[]> map = new EnumMap<>(Heightmap.Type.class);

            for(Map.Entry<Heightmap.Type, Heightmap> entry : chunk.getHeightmaps()) {
                if (chunk.getStatus().getHeightmapTypes().contains(entry.getKey())) {
                    long[] ms = entry.getValue().asLongArray();
                    map.put(entry.getKey(), (ms).clone());
                }
            }

            Chunk.TickSchedulers tickSchedulers = chunk.getTickSchedulers(world.getTime());
            ShortList[] shortLists = Arrays.stream(chunk.getPostProcessingLists())
                    .map((postProcessings) -> postProcessings != null && !postProcessings.isEmpty() ? new ShortArrayList(postProcessings) : null)
                    .toArray(ShortList[]::new);
            NbtCompound nbtCompound2 = createEmptyStructuresCompound(); // Client worlds do not contain structure info
            return new SerializedChunk(
                    world.getPalettesFactory(), chunkPos, chunk.getBottomSectionCoord(),
                    world.getTime(), chunk.getInhabitedTime(),
                    chunk.getStatus(),
                    Nullables.map(chunk.getBlendingData(), blendingData -> blendingData != null ? blendingData.toSerialized() : null),
                    chunk.getBelowZeroRetrogen(),
                    chunk.getUpgradeData().copy(), ls, map, tickSchedulers, shortLists, chunk.isLightOn(), list, list3, list2, nbtCompound2
            );
        }
    }

    private static NbtCompound createEmptyStructuresCompound() {
        NbtCompound nbtCompound = new NbtCompound();
        NbtCompound nbtCompound2 = new NbtCompound();

        nbtCompound.put("starts", nbtCompound2);
        NbtCompound nbtCompound3 = new NbtCompound();

        nbtCompound.put("References", nbtCompound3);
        return nbtCompound;
    }

    public static NbtCompound serialize(ClientWorld world, Chunk chunk) {
        return createSerializedChunk(world, chunk).serialize();
    }
}
