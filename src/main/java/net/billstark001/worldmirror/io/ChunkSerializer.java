package net.billstark001.worldmirror.io;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

@Environment(EnvType.CLIENT)
public class ChunkSerializer {

    /**
     * Returns {@code true} if the chunk has no non-air blocks and no block entities,
     * which means the server sent an empty placeholder chunk (e.g. beyond its own
     * render distance). Such chunks are not worth caching or exporting.
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
        CompoundTag chunkNbt = createSerializedChunk(world, chunk).write();
        BlockEntityNbtSupport.applyContainerOverlays(world.dimension(), chunkNbt);
        return chunkNbt;
    }

    private static SerializableChunkData createSerializedChunk(ClientLevel world, ChunkAccess chunk) {
        if (!chunk.canBeSerialized()) {
            throw new IllegalArgumentException("Chunk can't be serialized: " + chunk);
        }

        ChunkPos chunkPos = chunk.getPos();
        List<SerializableChunkData.SectionData> sections = new ArrayList<>();
        LevelChunkSection[] chunkSections = chunk.getSections();
        LevelLightEngine lightEngine = world.getChunkSource().getLightEngine();

        for (int sectionY = lightEngine.getMinLightSection();
                sectionY < lightEngine.getMaxLightSection();
                sectionY++) {
            int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
            boolean hasChunkSection = sectionIndex >= 0 && sectionIndex < chunkSections.length;
            DataLayer blockLight = lightEngine.getLayerListener(LightLayer.BLOCK)
                    .getDataLayerData(SectionPos.of(chunkPos, sectionY));
            DataLayer skyLight = lightEngine.getLayerListener(LightLayer.SKY)
                    .getDataLayerData(SectionPos.of(chunkPos, sectionY));
            DataLayer blockLightCopy = blockLight != null && !blockLight.isEmpty() ? blockLight.copy() : null;
            DataLayer skyLightCopy = skyLight != null && !skyLight.isEmpty() ? skyLight.copy() : null;

            if (hasChunkSection || blockLightCopy != null || skyLightCopy != null) {
                LevelChunkSection section = hasChunkSection ? chunkSections[sectionIndex].copy() : null;
                sections.add(new SerializableChunkData.SectionData(
                        sectionY, section, blockLightCopy, skyLightCopy));
            }
        }

        List<CompoundTag> blockEntities = new ArrayList<>(chunk.getBlockEntitiesPos().size());
        for (BlockPos blockPos : chunk.getBlockEntitiesPos()) {
            CompoundTag blockEntityNbt = chunk.getBlockEntityNbtForSaving(blockPos, world.registryAccess());
            if (blockEntityNbt != null) {
                blockEntities.add(blockEntityNbt);
            }
        }

        List<CompoundTag> entities = new ArrayList<>();
        long[] carvingMask = null;
        if (chunk.getPersistedStatus().getChunkType() == ChunkType.PROTOCHUNK) {
            ProtoChunk protoChunk = (ProtoChunk) chunk;
            entities.addAll(protoChunk.getEntities());
            CarvingMask mask = protoChunk.getCarvingMask();
            if (mask != null) {
                carvingMask = mask.toArray();
            }
        }

        Map<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            if (chunk.getPersistedStatus().heightmapsAfter().contains(entry.getKey())) {
                heightmaps.put(entry.getKey(), entry.getValue().getRawData().clone());
            }
        }

        ChunkAccess.PackedTicks ticks = chunk.getTicksForSerialization(world.getGameTime());
        ShortList[] postProcessing = Arrays.stream(chunk.getPostProcessing())
                .map(entries -> entries != null && !entries.isEmpty() ? new ShortArrayList(entries) : null)
                .toArray(ShortList[]::new);

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
                carvingMask,
                heightmaps,
                ticks,
                postProcessing,
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
