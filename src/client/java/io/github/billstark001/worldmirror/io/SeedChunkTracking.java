package io.github.billstark001.worldmirror.io;

import com.mojang.serialization.Codec;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.format.MirrorFormat;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Integrated server provenance and the derived index of durably saved seed terrain. */
public final class SeedChunkTracking {
    private record Session(MinecraftServer server, Path worldFolder, String sourceId, UUID epoch,
            Set<TrackedChunk> hidden, Set<TrackedChunk> ownedBlending) {}

    private record TrackedChunk(ResourceKey<Level> dimension, ChunkPos pos) {}

    private static final AttachmentType<SeedChunkProvenance.Entry> PROVENANCE =
            AttachmentRegistry.<SeedChunkProvenance.Entry>builder()
                    .persistent(SeedChunkProvenance.CODEC)
                    .buildAndRegister(Identifier.parse(SeedTerrainDigest.ATTACHMENT));
    private static final AttachmentType<Boolean> ABSENT =
            AttachmentRegistry.<Boolean>builder().buildAndRegister(
                    Identifier.fromNamespaceAndPath("worldmirror", "seed_origin_absent"));
    private static final AttachmentType<Boolean> FRESH =
            AttachmentRegistry.<Boolean>builder().buildAndRegister(
                    Identifier.fromNamespaceAndPath("worldmirror", "seed_awaiting_postprocess"));
    private static final AttachmentType<Boolean> POSTPROCESSING =
            AttachmentRegistry.<Boolean>builder().buildAndRegister(
                    Identifier.fromNamespaceAndPath("worldmirror", "seed_postprocessing"));
    private static final AttachmentType<String> BLENDING =
            AttachmentRegistry.<String>builder()
                    .persistent(Codec.STRING)
                    .buildAndRegister(Identifier.parse(SeedTerrainDigest.BLENDING_ATTACHMENT));

    private static volatile Session current;

    private SeedChunkTracking() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            var metadata = WorldMetadata.loadIfPresent(folder).orElse(null);
            if (metadata == null)
                return;
            UUID epoch = null;
            if (metadata.generation.mode() == WorldMetadata.GenerationMode.SEEDED) {
                try {
                    if (WorldStructureCreator.matchesSeededGeneration(
                                folder, metadata.generation.settingsDigest()))
                        epoch = metadata.generation.seedEpoch();
                } catch (IOException | RuntimeException failure) {
                    WMLogger.warn("Could not verify the assigned seed generator for " + folder, failure);
                }
            }
            current = new Session(server, folder, metadata.sourceId, epoch, ConcurrentHashMap.newKeySet(),
                    ConcurrentHashMap.newKeySet());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            var session = current;
            if (session != null && session.server() == server)
                current = null;
        });
        AttachmentRegistry.<CompoundTag>builder()
                .persistent(CompoundTag.CODEC)
                .buildAndRegister(Identifier.parse(SeedTerrainDigest.CAPTURE_RECEIPT));
        ServerChunkEvents.CHUNK_GENERATE.register(SeedChunkTracking::generated);
#if MC_VERSION >= 260102
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> loaded(level, chunk));
#else
        ServerChunkEvents.CHUNK_LOAD.register(SeedChunkTracking::loaded);
#endif
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            var session = current;
            if (session != null && session.server() == level.getServer()) {
                var key = new TrackedChunk(level.dimension(), chunk.getPos());
                session.hidden().remove(key);
                session.ownedBlending().remove(key);
            }
        });
    }

    public static boolean tracks(ServerLevel level) {
        var session = current;
        return session != null && session.server() == level.getServer();
    }

    public static boolean recordsFreshGeneration(ServerLevel level) {
        var session = current;
        return session != null && session.epoch() != null && session.server() == level.getServer();
    }

    public static void markAbsent(ChunkAccess chunk) {
        ((AttachmentTarget) chunk).setAttached(ABSENT, true);
    }

    private static void generated(ServerLevel level, LevelChunk chunk) {
        var session = current;
        var attachments = (AttachmentTarget) chunk;
        if (session == null || session.epoch() == null || session.server() != level.getServer()
                || attachments.removeAttached(ABSENT) == null)
            return;
        if (recordBaseline(level, chunk, session, session.epoch()))
            attachments.setAttached(FRESH, true);
    }

    private static boolean recordBaseline(ServerLevel level, LevelChunk chunk, Session session, UUID epoch) {
        try {
            int version = SharedConstants.getCurrentVersion().dataVersion().version();
            var entry = SeedChunkProvenance.baseline(
                    SerializableChunkData.copyOf(level, chunk).write(), epoch, version);
            var attachments = (AttachmentTarget) chunk;
            attachments.setAttached(PROVENANCE, entry);
            session.hidden().add(new TrackedChunk(level.dimension(), chunk.getPos()));
            return true;
        } catch (RuntimeException failure) {
            WMLogger.warnRateLimited("seed-chunk-baseline", 30_000L,
                    "Could not verify generated chunk baseline " + chunk.getPos(), failure);
            return false;
        }
    }

    private static void loaded(ServerLevel level, LevelChunk chunk) {
        var session = current;
        if (session == null || session.server() != level.getServer())
            return;
        var attachments = (AttachmentTarget) chunk;
        var key = new TrackedChunk(level.dimension(), chunk.getPos());
        if (attachments.hasAttached(BLENDING))
            session.ownedBlending().add(key);
        var entry = attachments.getAttached(PROVENANCE);
        if (entry == null || entry.edited())
            return;
        if (matches(level, chunk, entry))
            session.hidden().add(key);
        else
            changed(chunk);
    }

    public static boolean beforePostprocess(ServerLevel level, LevelChunk chunk) {
        var target = (AttachmentTarget) chunk;
        if (target.removeAttached(FRESH) == null)
            return false;
        var entry = target.getAttached(PROVENANCE);
        if (entry != null && matches(level, chunk, entry)) {
            target.setAttached(POSTPROCESSING, true);
            return true;
        }
        changed(chunk);
        return false;
    }

    public static void afterPostprocess(ServerLevel level, LevelChunk chunk, boolean completed) {
        var target = (AttachmentTarget) chunk;
        target.removeAttached(POSTPROCESSING);
        var entry = target.getAttached(PROVENANCE);
        var session = current;
        if (!completed || entry == null || session == null || session.server() != level.getServer()
                || !recordBaseline(level, chunk, session, entry.epoch()))
            changed(chunk);
    }

    private static boolean matches(ServerLevel level, ChunkAccess chunk, SeedChunkProvenance.Entry entry) {
        try {
            return SeedChunkProvenance.isUnmodified(
                    SerializableChunkData.copyOf(level, chunk).write(), entry);
        } catch (RuntimeException failure) {
            WMLogger.warnRateLimited("seed-chunk-verification", 30_000L,
                    "Could not verify seed terrain at " + chunk.getPos(), failure);
            return false;
        }
    }

    public static void changed(LevelChunk chunk) {
        var session = current;
        if (!(chunk.getLevel() instanceof ServerLevel level) || session == null
                || session.server() != level.getServer())
            return;
        var attachments = (AttachmentTarget) chunk;
        var entry = attachments.getAttached(PROVENANCE);
        if (entry != null && !entry.edited() && !attachments.hasAttached(POSTPROCESSING)) {
            attachments.setAttached(PROVENANCE, entry.markEdited());
            attachments.removeAttached(FRESH);
            session.hidden().remove(new TrackedChunk(level.dimension(), chunk.getPos()));
        }
    }

    /** Suppresses passive capture of seed terrain but allows chunks promoted by server edits. */
    public static boolean hidden(ClientLevel level, ChunkAccess chunk) {
        return hidden(level, chunk.getPos());
    }

    public static boolean hidden(ClientLevel level, ChunkPos pos) {
        var session = clientSession(level);
        return session != null && session.hidden().contains(new TrackedChunk(level.dimension(), pos));
    }

    /** Owned blending metadata is scoped to the source world. */
    public static void stripCaptured(ClientLevel level, ChunkAccess source, CompoundTag captured) {
        var session = clientSession(level);
        if (session != null
                && session.ownedBlending().contains(new TrackedChunk(level.dimension(), source.getPos())))
            captured.remove(SeedTerrainDigest.BLENDING_DATA);
        SeedChunkProvenance.strip(captured);
    }

    private static Session clientSession(ClientLevel level) {
        var session = current;
        if (session == null)
            return null;
        var client = Minecraft.getInstance();
        return level == client.level && session.server() == client.getSingleplayerServer() ? session : null;
    }

    /** Reconciles the last native read or write per position after successful storage flush. */
    public static final class IndexWrites {
        private final Path folder;
        private final Map<ChunkPos, Boolean> pending = new HashMap<>();

        public IndexWrites(Path folder) {
            this.folder = folder.toAbsolutePath().normalize();
        }

        public void observe(ResourceKey<Level> dimension, ChunkPos pos, CompoundTag nbt) {
            var session = owner(dimension);
            if (session == null || SharedConstants.DEBUG_DONT_SAVE_WORLD)
                return;
            var entry = nbt == null ? null : SeedChunkProvenance.read(nbt);
            pending.put(pos, entry != null && SeedChunkProvenance.isUnmodified(nbt, entry));
        }

        public void flushed(ResourceKey<Level> dimension) {
            var session = owner(dimension);
            if (pending.isEmpty() || session == null)
                return;
            var generated = new HashSet<ChunkPos>();
            var other = new HashSet<ChunkPos>();
            pending.forEach((pos, pristine) -> (pristine ? generated : other).add(pos));
            try {
                var file = session.worldFolder().resolve(MirrorFormat.DATABASE_FILE);
                if (!generated.isEmpty() || !Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                    try (var db = ChunkDatabase.open(session.worldFolder(), session.sourceId())) {
                        var id = dimension.identifier().toString();
                        db.recordSeedGeneratedIfMissing(id, generated);
                        db.removeSeedGenerated(id, other);
                    }
                }
                pending.clear();
            } catch (SQLException | RuntimeException failure) {
                WMLogger.warnRateLimited("seed-chunk-index:" + folder, 30_000L,
                        "Could not refresh the derived seed terrain index for " + folder, failure);
            }
        }

        private Session owner(ResourceKey<Level> dimension) {
            var session = current;
            if (session == null)
                return null;
            try {
                return WorldSaveLayout.regionDirectory(session.worldFolder(), dimension).equals(folder)
                        ? session
                        : null;
            } catch (RuntimeException unsupportedLayout) {
                WMLogger.warnRateLimited("seed-chunk-layout:" + folder, 30_000L,
                        "Could not identify seed chunk storage in " + folder, unsupportedLayout);
                return null;
            }
        }
    }
}
