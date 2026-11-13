package io.github.billstark001.worldmirror.io;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Optionull;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
#if MC_VERSION >= 260300
#else
import net.minecraft.world.level.chunk.CarvingMask;
#endif
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;

@Environment(EnvType.CLIENT)
public class ChunkSerializer {

    /**
     * Returns {@code true} if the chunk has no non-air blocks and no block entities,
     * which may be a server placeholder beyond its render distance. Ordinary
     * observations skip these chunks; explicit block-change captures may save
     * the empty result to remove previously recorded terrain.
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
        // The native writer packs section palettes and offsets synchronously on
        // the client thread. Only the resulting NBT leaves this call.
        CompoundTag chunkNbt = createSerializedChunk(world, chunk).write();
        BlockEntityNbtSupport.applyContainerOverlays(world.dimension(), chunkNbt);
        return chunkNbt;
    }

    private static SerializableChunkData createSerializedChunk(ClientLevel world, ChunkAccess chunk) {
        if (!chunk.canBeSerialized()) {
            throw new IllegalArgumentException("Chunk can't be serialized: " + chunk);
        }

        ChunkPos chunkPos = chunk.getPos();
        LevelChunkSection[] chunkSections = chunk.getSections();
        LevelLightEngine lightEngine = world.getChunkSource().getLightEngine();
        int minLightSection = lightEngine.getMinLightSection();
        int maxLightSection = lightEngine.getMaxLightSection();
        var blockLightListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        var skyLightListener = lightEngine.getLayerListener(LightLayer.SKY);
        List<SerializableChunkData.SectionData> sections = new ArrayList<>(maxLightSection - minLightSection);

        for (int sectionY = minLightSection; sectionY < maxLightSection; sectionY++) {
            int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
            boolean hasChunkSection = sectionIndex >= 0 && sectionIndex < chunkSections.length;
            SectionPos sectionPos = SectionPos.of(chunkPos, sectionY);
            DataLayer blockLight = blockLightListener.getDataLayerData(sectionPos);
            DataLayer skyLight = skyLightListener.getDataLayerData(sectionPos);
            // A non-null but empty DataLayer means that the client explicitly
            // knows this section is all zero.  It must not be confused with a
            // null layer, which means the client has no data for that section.
            // The writer stores these arrays directly in NBT, so copy the layers.
            DataLayer blockLightCopy = blockLight != null ? blockLight.copy() : null;
            DataLayer skyLightCopy = skyLight != null ? skyLight.copy() : null;

            if (hasChunkSection || blockLightCopy != null || skyLightCopy != null) {
                LevelChunkSection section = hasChunkSection ? chunkSections[sectionIndex] : null;
                sections.add(new SerializableChunkData.SectionData(
                        sectionY, section, blockLightCopy, skyLightCopy));
            }
        }

        Set<BlockPos> blockEntityPositions = chunk.getBlockEntitiesPos();
        List<CompoundTag> blockEntities = new ArrayList<>(blockEntityPositions.size());
        for (BlockPos blockPos : blockEntityPositions) {
            CompoundTag blockEntityNbt = chunk.getBlockEntityNbtForSaving(blockPos, world.registryAccess());
            if (blockEntityNbt != null) {
                blockEntities.add(blockEntityNbt);
            }
        }

        List<CompoundTag> entities = new ArrayList<>();
#if MC_VERSION >= 260300
#else
        long[] carvingMask = null;
#endif
        if (chunk.getPersistedStatus().getChunkType() == ChunkType.PROTOCHUNK) {
            ProtoChunk protoChunk = (ProtoChunk) chunk;
            entities.addAll(protoChunk.getEntities());
#if MC_VERSION >= 260300
#else
            CarvingMask mask = protoChunk.getCarvingMask();
            if (mask != null) {
                carvingMask = mask.toArray();
            }
#endif
        }

        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            if (chunk.getPersistedStatus().heightmapsAfter().contains(entry.getKey())) {
                heightmaps.put(entry.getKey(), entry.getValue().getRawData().clone());
            }
        }

        ChunkAccess.PackedTicks ticks = chunk.getTicksForSerialization(world.getGameTime());

        return new SerializableChunkData(
                world.palettedContainerFactory(),
                chunkPos,
                chunk.getMinSectionY(),
                world.getGameTime(),
                chunk.getInhabitedTime(),
                chunk.getPersistedStatus(),
                Optionull.map(chunk.getBlendingData(), blendingData -> blendingData != null ? blendingData.pack() : null),
                chunk.getBelowZeroRetrogen(),
                chunk.getUpgradeData().copy(),
#if MC_VERSION >= 260300
#else
                carvingMask,
#endif
                heightmaps,
                ticks,
                chunk.getPostProcessing(),
                chunk.isLightCorrect(),
                sections,
                entities,
                blockEntities,
                createEmptyStructuresCompound());
    }

    private static CompoundTag createEmptyStructuresCompound() {
        CompoundTag structures = new CompoundTag();
        structures.put("starts", new CompoundTag());
        structures.put("References", new CompoundTag());
        return structures;
    }
}
