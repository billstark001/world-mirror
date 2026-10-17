package io.github.billstark001.worldmirror.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ModConfigDefaultsTest {
    @Test
    void successfulExportsInvalidateChunkCacheByDefault() {
        assertTrue(new ModConfig().cache.invalidateAfterExport);
    }

    @Test
    void worldOverlaySwitchesAreIndependent() {
        ModConfig.ChunkMapConfig config = new ModConfig.ChunkMapConfig();
        assertFalse(config.isWorldChunkOverlayEnabled(false));
        assertFalse(config.isWorldChunkOverlayEnabled(true));

        config.toggleWorldChunkOverlay(false);
        assertTrue(config.isWorldChunkOverlayEnabled(false));
        assertFalse(config.isWorldChunkOverlayEnabled(true));

        config.toggleWorldChunkOverlay(true);
        assertTrue(config.isWorldChunkOverlayEnabled(false));
        assertTrue(config.isWorldChunkOverlayEnabled(true));

        config.toggleWorldChunkOverlay(false);
        assertFalse(config.isWorldChunkOverlayEnabled(false));
        assertTrue(config.isWorldChunkOverlayEnabled(true));
    }

    @Test
    void worldOverlayStartsHiddenUnderF1With128ChunkRadius() {
        ModConfig.ChunkMapConfig config = new ModConfig.ChunkMapConfig();
        assertFalse(config.showWorldChunkOverlayWithHiddenGui);
        assertEquals(128, config.worldChunkOverlayRenderDistance);
    }
}
