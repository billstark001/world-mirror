package io.github.billstark001.worldmirror.download;

import java.util.OptionalLong;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

abstract class DownloadExportFixture {
    @AfterEach
    void clearCache() {
        ChunkListener.clear();
        EntityTracker.clear();
    }

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static ExportWork.Intent intent(ExportWork.Trigger trigger, boolean notifyPlayer, String source) {
        return intent(trigger, notifyPlayer, ExportTestTargets.session(source));
    }

    static ExportWork.Intent intent(
            ExportWork.Trigger trigger, boolean notifyPlayer, CaptureSession session) {
        return new ExportWork.Intent(new ExportWork.Context(trigger, notifyPlayer, session),
                ExportWork.CapturePhase.REQUIRED, ExportWork.EntityScope.LIVE, notifyPlayer,
                java.util.Optional.empty());
    }

    static ExportWork.Frozen prepared(String source) {
        UUID uuid = new UUID(1L, 2L);
        var entity = new EntityTracker.EntityRecord(uuid, Set.of(uuid), new CompoundTag());
        var update = new EntityTracker.ChunkUpdate(
                4L, EntityTracker.Observation.PARTIAL, Map.of(uuid, entity), Set.of());
        return new ExportWork.Frozen(
                new ExportWork.Context(ExportWork.Trigger.STOP, false, ExportTestTargets.session(source)),
                ChunkListener.snapshotOf(Map.of(Level.OVERWORLD,
                        Map.of(new ChunkPos(0, 0),
                                new ChunkListener.CapturedChunk(new CompoundTag(), 100L, 3L, false)))),
                Map.of(Level.OVERWORLD, Map.of(new ChunkPos(0, 0), update)), Map.of(), 0L,
                OptionalLong.empty());
    }

    static Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers(CompoundTag value) {
        return Map.of(Level.OVERWORLD, Map.of(BlockPos.ZERO, value));
    }
}
