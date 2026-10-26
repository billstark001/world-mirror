package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runs in a small heap so retaining a whole decoded region fails deterministically. */
public final class LargeRegionProbe {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path world = Path.of(args[0]);
        Path directory = Files.createDirectories(ChunkExporter.dimensionDirForDimension(world, Level.OVERWORLD).resolve("entities"));
        Path file = directory.resolve("r.0.0.mca");
        int chunks = 144;
        byte[] opaque = new byte[2 * 1024 * 1024];
        try (RegionFile writer = new RegionFile(new RegionStorageInfo("probe", Level.OVERWORLD, "entity"), file, directory, false)) {
            for (int i = 0; i < chunks; i++) {
                CompoundTag chunk = new CompoundTag();
                chunk.putIntArray("Position", new int[] {i % 32, i / 32});
                chunk.put("Entities", new ListTag());
                chunk.putByteArray("opaque", opaque);
                NbtWriteSupport.writeChunkAndDigest(chunk, writer.getChunkDataOutputStream(new ChunkPos(i % 32, i / 32)));
            }
        }
        int[] visited = {0};
        AnvilReader.visitRegion(file, 0, 0, (pos, chunk) -> visited[0]++);
        if (visited[0] != chunks) throw new AssertionError("Incomplete large-region scan");
        UUID uuid = new UUID(1, 2);
        CompoundTag pig = new CompoundTag();
        pig.putString("id", "minecraft:pig");
        pig.putIntArray("UUID", UUIDUtil.uuidToIntArray(uuid));
        var update = new EntityTracker.ChunkUpdate(7, EntityTracker.Observation.PARTIAL,
                Map.of(uuid, new EntityTracker.EntityRecord(uuid, Set.of(uuid), pig)), Set.of());
        var written = EntityRegionWriter.write(world, Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), update)), ExportProgress.NONE);
        if (!Long.valueOf(7).equals(written.getOrDefault(Level.OVERWORLD, Map.of()).get(new ChunkPos(0, 0)))) {
            throw new AssertionError("Large-region rewrite was not acknowledged");
        }
        CompoundTag saved = AnvilReader.readChunk(file, new ChunkPos(0, 0));
        if (saved.getByteArray("opaque").orElseThrow().length != opaque.length
                || saved.getListOrEmpty("Entities").size() != 1) throw new AssertionError("Large-region data changed");
        System.out.println("PASS: 288 MiB decoded region scanned and rewritten in a 160 MiB heap");
        verifyLargeConflictBatches(world.resolveSibling("conflicts"), chunks, opaque);
    }

    private static void verifyLargeConflictBatches(Path world, int chunks, byte[] opaque) throws Exception {
        if (!WorldStructureCreator.createLoadableWorld(world, "large conflict probe", true, true, WorldSettingsSnapshot.defaults())) {
            throw new AssertionError("Cannot initialize isolated conflict target");
        }
        String source = "local:large-conflict-probe";
        WorldMetadata metadata = WorldMetadata.create(source, "singleplayer", "synchronized");
        metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(), MirrorWorldgenAssets.ASSET_REVISION);
        if (!metadata.save(world)) throw new AssertionError("Cannot save isolated conflict metadata");
        Path directory = Files.createDirectories(ConflictManager.getConflictDir(world, Level.OVERWORLD));
        Path file = directory.resolve("r.0.0.mca");
        try (RegionFile writer = new RegionFile(new RegionStorageInfo("probe", Level.OVERWORLD, "chunk"), file, directory, false)) {
            for (int i = 0; i < chunks; i++) {
                CompoundTag chunk = new CompoundTag();
                chunk.putInt("xPos", i % 32); chunk.putInt("zPos", i / 32);
                chunk.putByteArray("opaque", opaque);
                NbtWriteSupport.writeChunkAndDigest(chunk, writer.getChunkDataOutputStream(new ChunkPos(i % 32, i / 32)));
            }
        }
        if (!ConflictManager.clearAllConflicts(new MirrorTarget(world, source), true)
                || ConflictManager.countAllConflicts(world) != 0) throw new AssertionError("Large conflict batches remain pending");
        Path target = ChunkExporter.regionDirForDimension(world, Level.OVERWORLD).resolve("r.0.0.mca");
        int[] visited = {0};
        AnvilReader.visitRegion(target, 0, 0, (pos, chunk) -> {
            if (chunk.getByteArray("opaque").orElseThrow().length != opaque.length) throw new AssertionError("Lost conflict payload");
            visited[0]++;
        });
        if (visited[0] != chunks) throw new AssertionError("Missing copied conflict chunks");
        System.out.println("PASS: 288 MiB conflicts applied and verified in a 160 MiB heap");
    }
}
