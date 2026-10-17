package io.github.billstark001.worldmirror.config;

import com.google.gson.Gson;
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

    @Test
    void existingConfigsGainSessionColorsWithoutOverwritingTheirHeight() {
        Gson gson = new Gson();
        ModConfig defaults = gson.fromJson("{}", ModConfig.class);
        assertEquals(96, defaults.chunkMap.worldChunkOverlayHeight);
        assertEquals(ModConfig.WorldChunkOverlayColorMode.SESSION,
                defaults.chunkMap.worldChunkOverlayColorMode);

        ModConfig existing = gson.fromJson(
                "{\"chunkMap\":{\"worldChunkOverlayHeight\":0}}", ModConfig.class);
        assertEquals(0, existing.chunkMap.worldChunkOverlayHeight);
        assertEquals(ModConfig.WorldChunkOverlayColorMode.SESSION,
                existing.chunkMap.worldChunkOverlayColorMode);
    }

    @Test
    void bothOverlayColorModesSurviveConfigSavingAndLoading() {
        Gson gson = new Gson();
        for (ModConfig.WorldChunkOverlayColorMode mode : ModConfig.WorldChunkOverlayColorMode.values()) {
            ModConfig config = new ModConfig();
            config.chunkMap.worldChunkOverlayColorMode = mode;
            config.chunkMap.worldChunkOverlayHeight = 98;
            ModConfig restored = gson.fromJson(gson.toJson(config), ModConfig.class);
            assertEquals(mode, restored.chunkMap.worldChunkOverlayColorMode);
            assertEquals(98, restored.chunkMap.worldChunkOverlayHeight);
        }
    }
}
