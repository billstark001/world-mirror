package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.core.EntityTracker;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Region fixtures shared by entity persistence and cross-chunk movement tests. */
abstract class EntityRegionFixture {
    @TempDir Path tempDir;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    void writeInitialRegion(ChunkPos pos, List<CompoundTag> entities) throws Exception {
        Path dir = entitiesDir();
        Files.createDirectories(dir);
        // Deliberately use slot 0 of r.0.0; one fixture stores a mismatched Position here.
        McaWriteSupport.writeChunks(dir.resolve("r.0.0.mca"), Level.OVERWORLD,
                Map.of(new ChunkPos(pos.getRegionLocalX(), pos.getRegionLocalZ()),
                        entityChunk(pos, entities)));
    }

    static Map<ChunkPos, CompoundTag> readRegion(Path file) throws Exception {
        String[] parts = file.getFileName().toString().split("\\.");
        Map<ChunkPos, CompoundTag> chunks = new java.util.HashMap<>();
        AnvilReader.visitRegion(file, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), chunks::put);
        return chunks;
    }

    Path entitiesDir() {
        return WorldSaveLayout.dimensionDirectory(tempDir, Level.OVERWORLD).resolve("entities");
    }

    static CompoundTag entityChunk(ChunkPos pos, List<CompoundTag> entities) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", 4671);
        chunk.putIntArray("Position", new int[] {pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4});
        ListTag list = new ListTag();
        list.addAll(entities);
        chunk.put("Entities", list);
        return chunk;
    }

    static EntityTracker.EntityRecord record(String id, UUID uuid, Set<UUID> contained) {
        net.minecraft.nbt.CompoundTag nbt = new net.minecraft.nbt.CompoundTag();
        nbt.putString("id", id);
        nbt.putIntArray("UUID", uuidToIntArray(uuid));
        return new EntityTracker.EntityRecord(uuid, contained, nbt);
    }

    static CompoundTag storedEntity(String id, UUID uuid) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putIntArray("UUID", uuidToIntArray(uuid));
        return tag;
    }

    static int[] uuidToIntArray(UUID uuid) {
        return new int[] {(int) (uuid.getMostSignificantBits() >> 32), (int) uuid.getMostSignificantBits(),
                (int) (uuid.getLeastSignificantBits() >> 32), (int) uuid.getLeastSignificantBits()};
    }
}
