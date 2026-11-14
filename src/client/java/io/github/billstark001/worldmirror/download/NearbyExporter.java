package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;

import io.github.billstark001.worldmirror.conflict.OverwriteResolver;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.SeedChunkTracking;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Captures nearby chunks and materializes them as a separate playable save. */
public final class NearbyExporter {
    public static final int MIN_RADIUS_CHUNKS = 1;
    public static final int MAX_RADIUS_CHUNKS = 50;

    public enum Choice { INHERIT_ORIGINAL, CURRENT_MIRROR, INDEPENDENT }

    record Lineage(String nodeId, String parentNodeId, String sourceId) {}
    private NearbyExporter() {}

    static Lineage independentLineage() {
        String id = "snapshot:" + UUID.randomUUID();
        return new Lineage(id, null, id);
    }

    static Lineage resolveLinkedLineage(
            Choice choice, WorldGraph.Snapshot graph, String currentNode, String indexSource) {
        if (currentNode == null || !graph.isUnambiguous(currentNode)) {
            throw new IllegalArgumentException("Current world identity is unavailable");
        }
        String parent = choice == Choice.INHERIT_ORIGINAL && graph.nodes().containsKey(currentNode)
                ? graph.findRoot(currentNode)
                          .orElseThrow(() -> new IllegalArgumentException("Original world unavailable"))
                          .id()
                : currentNode;
        return new Lineage("mirror:" + UUID.randomUUID(), parent, indexSource == null ? parent : indexSource);
    }

    static List<Choice> lineageChoices(
            WorldGraph.Snapshot graph, String sourceId, boolean canLinkUntrackedLocal) {
        if (sourceId == null) {
            return canLinkUntrackedLocal ? List.of(Choice.INHERIT_ORIGINAL, Choice.INDEPENDENT)
                                         : List.of(Choice.INDEPENDENT);
        }
        if (!graph.isUnambiguous(sourceId))
            return List.of(Choice.INDEPENDENT);
        var node = graph.nodes().get(sourceId);
        if (node == null || node.parentId() == null) {
            if (node != null && node.kind() == WorldGraph.Kind.SNAPSHOT) {
                return List.of(Choice.CURRENT_MIRROR, Choice.INDEPENDENT);
            }
            return List.of(Choice.INHERIT_ORIGINAL, Choice.INDEPENDENT);
        }
        List<Choice> choices = new ArrayList<>(3);
        if (graph.findRoot(sourceId).isPresent())
            choices.add(Choice.INHERIT_ORIGINAL);
        choices.add(Choice.CURRENT_MIRROR);
        choices.add(Choice.INDEPENDENT);
        return List.copyOf(choices);
    }

    /** Returns the choices without registering a source for an untracked save. */
    public static List<Choice> lineageChoices(Minecraft client) {
        var service = WorldGraphService.instance();
        String source = service.available() && service.routing().observationIsCurrent(client)
                ? service.routing().currentResolution().sourceNodeId()
                : null;
        return lineageChoices(
                service.graph(), source, source == null && service.routing().canUseLinkedSource(client));
    }

    /** Captures on the game thread, then writes the new save in a dedicated worker. */
    public static void export(Minecraft client, String worldName, int radiusChunks, Choice lineageChoice) {
        Objects.requireNonNull(lineageChoice, "lineageChoice");
        ClientLevel world = client.level;
        if (world == null || client.player == null) {
            WMPlayerMessages.sendSystemMessage(client.player,
                    Component.translatable("msg.worldmirror.nearbyNoWorld").withStyle(ChatFormatting.RED));
            return;
        }
        radiusChunks = Math.clamp(radiusChunks, MIN_RADIUS_CHUNKS, MAX_RADIUS_CHUNKS);

        ResourceKey<Level> dimension = world.dimension();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        int playerBX = client.player.getBlockX();
        int playerBY = client.player.getBlockY();
        int playerBZ = client.player.getBlockZ();

        Map<ChunkPos, ChunkListener.CapturedChunk> nearbyChunks = new HashMap<>();
        for (int cx = playerCX - radiusChunks; cx <= playerCX + radiusChunks; cx++) {
            for (int cz = playerCZ - radiusChunks; cz <= playerCZ + radiusChunks; cz++) {
                ChunkAccess chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk worldChunk)
                        || !RemoteChunkProvenance.canSample(world, cx, cz)
                        || SeedChunkTracking.hidden(world, worldChunk))
                    continue;
                try {
                    if (ChunkSerializer.isChunkEmpty(worldChunk))
                        continue;
                    CompoundTag nbt = ChunkSerializer.serialize(world, worldChunk);
                    nearbyChunks.put(worldChunk.getPos(),
                            new ChunkListener.CapturedChunk(nbt, System.currentTimeMillis(), 0L, false));
                } catch (Exception e) {
                    WMLogger.warnRateLimited("nearby-capture", 30_000L,
                            "Nearby export capture failed chunk=" + worldChunk.getPos(), e);
                }
            }
        }

        if (nearbyChunks.isEmpty()) {
            WMPlayerMessages.sendSystemMessage(client.player,
                    Component.translatable("msg.worldmirror.nearbyNoChunks", radiusChunks)
                            .withStyle(ChatFormatting.RED));
            return;
        }

        ChunkListener.DirtySnapshot snapshot = ChunkListener.snapshotOf(Map.of(dimension, nearbyChunks));
        Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entitySnapshot =
                Map.of(dimension, EntityTracker.snapshotVisibleEntities(world, nearbyChunks.keySet()));
        Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot =
                ContainerTracker.snapshotSavedData();

        String safeName = worldName == null || worldName.isBlank() ? "NearbyExport" : worldName;
        WorldSettingsSnapshot worldSettings = WorldStructureCreator.resolveNewWorldSettings(
                WorldStructureCreator.captureWorldSettings(world));
        int capturedCount = nearbyChunks.size();
        Consumer<Lineage> startWrite = lineage
                -> startWrite(client, safeName, dimension, playerBX, playerBY, playerBZ, snapshot,
                        entitySnapshot, containerSnapshot, lineage, worldSettings, capturedCount);
        if (lineageChoice == Choice.INDEPENDENT) {
            startWrite.accept(independentLineage());
            return;
        }
        resolveLinkedExport(client, world, lineageChoice, startWrite);
    }

    private static void resolveLinkedExport(
            Minecraft client, ClientLevel world, Choice choice, Consumer<Lineage> startWrite) {
        var service = WorldGraphService.instance();
        var connection = client.getConnection();
        long epoch = service.routing().connectionEpoch();
        service.routing().requireLinkedSource(client).whenComplete((source, failure) -> {
            if (failure != null) {
                notifyUnavailable(client, failure);
                return;
            }
            if (client.level != world || client.getConnection() != connection
                    || service.routing().connectionEpoch() != epoch
                    || !service.routing().observationIsCurrent(client)
                    || !Objects.equals(source, service.routing().currentResolution().sourceNodeId())) {
                notifyUnavailable(client, new IOException("The active source changed during nearby export"));
                return;
            }
            var observation = service.routing().source();
            String indexSource = observation != null && observation.endpoint() != null
                    ? "server:" + observation.endpoint()
                    : null;
            Lineage lineage;
            try {
                lineage = resolveLinkedLineage(choice, service.graph(), source, indexSource);
            } catch (IllegalArgumentException invalid) {
                notifyUnavailable(client, invalid);
                return;
            }
            startWrite.accept(lineage);
        });
    }

    private static void startWrite(Minecraft client, String safeName, ResourceKey<Level> dimension,
            int playerBX, int playerBY, int playerBZ, ChunkListener.DirtySnapshot snapshot,
            Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entitySnapshot,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot, Lineage lineage,
            WorldSettingsSnapshot worldSettings, int capturedCount) {
        Path base = FabricLoader.getInstance().getGameDir().resolve("saves");
        Path outFolder;
        try {
            outFolder = claimOutputFolder(base, MirrorFolders.sanitiseFolderName(safeName, "NearbyExport"));
        } catch (IOException error) {
            WMLogger.warn("Nearby export directory claim failed base=" + base, error);
            notifyFailure(client);
            return;
        }

        WMLogger.info(
                "Exporting " + capturedCount + " nearby chunk(s) to '" + outFolder.getFileName() + "'...");
        Thread worker = new Thread(
                ()
                        -> writeSave(client, safeName, outFolder, dimension, playerBX, playerBY, playerBZ,
                                snapshot, entitySnapshot, containerSnapshot, lineage, worldSettings),
                "WM-NearbyExport");
        worker.setDaemon(false);
        worker.start();
    }

    private static void writeSave(Minecraft client, String worldName, Path output,
            ResourceKey<Level> spawnDimension, int spawnX, int spawnY, int spawnZ,
            ChunkListener.DirtySnapshot snapshot,
            Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entities,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers, Lineage lineage,
            WorldSettingsSnapshot worldSettings) {
        try (WorldWriteLock ignored = WorldWriteLock.acquire(output)) {
            Files.createDirectories(output);
            long latestCaptureTime;
            try (ChunkDatabase database = ChunkDatabase.open(output, lineage.sourceId())) {
                ChunkExporter.ExportResult result = ChunkExporter.exportChunks(output, snapshot, entities,
                        containers, new OverwriteResolver(), database,
                        ModConfig.get().performance.diagnosticPerformanceLogging
                                ? Math.max(0L, ModConfig.get().performance.slowRegionMillis)
                                : -1L,
                        io.github.billstark001.worldmirror.io.ExportProgress.NONE);
                commitDurabilityIndex(database, result);
                if (!result.chunkWritesSuccessful() || !result.entityWritesSuccessful()) {
                    throw new java.io.IOException("One or more nearby-export region writes failed");
                }
                latestCaptureTime = result.written()
                                            .values()
                                            .stream()
                                            .flatMap(chunks -> chunks.values().stream())
                                            .mapToLong(ChunkExporter.WriteStamp::capturedAtMs)
                                            .max()
                                            .orElse(0L);
            }
            if (!WorldStructureCreator.createLoadableWorldWithSpawn(
                        output, worldName, spawnDimension, spawnX, spawnY, spawnZ, worldSettings)) {
                throw new IllegalStateException("Could not create nearby-export world structure");
            }
            WorldMetadata metadata = WorldMetadata.create(lineage.nodeId(),
                    lineage.parentNodeId() == null ? WorldGraph.Kind.SNAPSHOT : WorldGraph.Kind.MIRROR,
                    lineage.parentNodeId(), WorldGraph.Role.SNAPSHOT, lineage.sourceId());
            metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(),
                    MirrorWorldgenAssets.ASSET_REVISION);
            if (!metadata.markSyncComplete(output, latestCaptureTime)) {
                notifyFailure(client);
                return;
            }
            WorldGraphService.instance().refreshAsync();
            WMLogger.info("Nearby export completed world=" + output.toAbsolutePath()
                    + " linkedUpstream=" + (lineage.parentNodeId() != null));
            client.execute(
                    ()
                            -> WMPlayerMessages.sendSystemMessage(client.player,
                                    Component.translatable("msg.worldmirror.nearbyDone", output.getFileName())
                                            .withStyle(ChatFormatting.GREEN)));
        } catch (Exception e) {
            WMLogger.warn("Nearby export failed output=" + output, e);
            notifyFailure(client);
        }
    }

    private static void notifyFailure(Minecraft client) {
        client.execute(()
                               -> WMPlayerMessages.sendSystemMessage(client.player,
                                       Component.translatable("msg.worldmirror.nearbyFailed")
                                               .withStyle(ChatFormatting.RED)));
    }

    private static void notifyUnavailable(Minecraft client, Throwable failure) {
        WMLogger.warnRateLimited("nearby-source", 30_000L, "Nearby export source unavailable", failure);
        WMPlayerMessages.sendSystemMessage(client.player,
                Component.translatable("msg.worldmirror.nearbySourceUnavailable")
                        .withStyle(ChatFormatting.RED));
    }

    private static void commitDurabilityIndex(ChunkDatabase database, ChunkExporter.ExportResult result)
            throws SQLException {
        for (Map.Entry<ResourceKey<Level>, Set<ChunkPos>> entry : result.unreadableChunks().entrySet()) {
            if (!database.removeUnreadableUpdates(entry.getKey().identifier().toString(), entry.getValue())) {
                throw new SQLException("Could not remove unreadable nearby-export durability rows");
            }
        }
        for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, ChunkExporter.WriteStamp>> entry :
                result.written().entrySet()) {
            Map<ChunkPos, Long> timestamps = new HashMap<>();
            entry.getValue().forEach((pos, stamp) -> timestamps.put(pos, stamp.capturedAtMs()));
            if (!database.recordUpdates(entry.getKey().identifier().toString(), timestamps, "world_mirror")) {
                throw new SQLException("Could not commit nearby-export durability index");
            }
        }
    }

    /** Claims a fresh save directory atomically, even when exports use the same name concurrently. */
    static Path claimOutputFolder(Path base, String folderName) throws IOException {
        MirrorFolders.requireFolderName(folderName);
        Files.createDirectories(base);
        for (int suffix = 0; suffix < Integer.MAX_VALUE; suffix++) {
            Path candidate = base.resolve(suffix == 0 ? folderName : folderName + '_' + suffix);
            try {
                return Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException occupied) {
                // Another export or existing save owns this directory.
            }
        }
        throw new IOException("No unused nearby-export folder is available");
    }
}
