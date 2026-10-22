package io.github.billstark001.worldmirror.core;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ContainerTrackerStateTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clear() {
        ContainerTracker.clear();
        ChunkListener.clear();
    }

    @Test
    void callersCannotMutateTheStoredInventoryThroughQueries() {
        ContainerTracker.rememberOverlay(Level.OVERWORLD, BlockPos.ZERO, overlay(1));
        ContainerTracker.getContainerData(Level.OVERWORLD, BlockPos.ZERO).putInt("version", 9);
        assertEquals(1, ContainerTracker.getContainerData(Level.OVERWORLD, BlockPos.ZERO).getIntOr("version", -1));
    }

    @Test
    void anOlderExportCannotEvictANewerInventoryOverlay() {
        ContainerTracker.rememberOverlay(Level.OVERWORLD, BlockPos.ZERO, overlay(1));
        var exported = ContainerTracker.snapshotSavedData();
        ContainerTracker.rememberOverlay(Level.OVERWORLD, BlockPos.ZERO, overlay(2));
        var chunks = Map.of(Level.OVERWORLD, Set.of(new ChunkPos(0, 0)));

        ContainerTracker.evictForChunks(chunks, exported);
        assertEquals(2, ContainerTracker.getContainerData(Level.OVERWORLD, BlockPos.ZERO).getIntOr("version", -1));
        ContainerTracker.evictForChunks(chunks, ContainerTracker.snapshotSavedData());
        assertNull(ContainerTracker.getContainerData(Level.OVERWORLD, BlockPos.ZERO));
    }

    @Test
    void anOverlayAbsentFromTheExportIsRetained() {
        ContainerTracker.rememberOverlay(Level.OVERWORLD, BlockPos.ZERO, overlay(1));
        ContainerTracker.evictForChunks(Map.of(Level.OVERWORLD, Set.of(new ChunkPos(0, 0))), Map.of());
        assertNotNull(ContainerTracker.getContainerData(Level.OVERWORLD, BlockPos.ZERO));
    }

    private static CompoundTag overlay(int version) {
        CompoundTag data = new CompoundTag();
        data.putInt("version", version);
        return data;
    }
}
