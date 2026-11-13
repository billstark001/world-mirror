package io.github.billstark001.worldmirror.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.billstark001.worldmirror.config.ModConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadConfigDiagnosticsTest {
    @Test
    void globalSnapshotIncludesEveryConfigurationGroup() {
        ModConfig config = new ModConfig();
        JsonObject json =
                JsonParser.parseString(DownloadConfigDiagnostics.globalJson(config)).getAsJsonObject();

        assertEquals(config.defaultSaveLocation.name(), json.get("defaultSaveLocation").getAsString());
        assertEquals(config.syncIntervalSeconds, json.get("syncIntervalSeconds").getAsInt());
        assertTrue(json.has("pipelineMode"));
        assertTrue(json.has("defaultConflictStrategy"));
        assertTrue(json.has("newWorldTime"));
        assertTrue(json.has("newWorldWeather"));
        assertTrue(json.has("newWorldDifficulty"));
        assertTrue(json.getAsJsonObject("cache").has("invalidateAfterExport"));
        assertTrue(json.getAsJsonObject("performance").has("diagnosticPerformanceLogging"));
        assertTrue(json.getAsJsonObject("chunkMap").has("xaeroWorldMapOverlayMaxCells"));
        assertTrue(json.getAsJsonObject("lifecycle").has("exportAllCachedOnStop"));
    }

    @Test
    void localSnapshotKeepsGraphIdentityAndIndexNamespaceSeparate() {
        var target = new WorldGraphService.Target(
                "mirror:copy", "remote:origin", "server:example.test", java.nio.file.Path.of("save"));
        JsonObject json = JsonParser
                                  .parseString(DownloadConfigDiagnostics.localJson(
                                          target, ModConfig.ConflictStrategy.MANUAL))
                                  .getAsJsonObject();

        assertEquals("mirror:copy", json.get("nodeId").getAsString());
        assertEquals("remote:origin", json.get("parentNodeId").getAsString());
        assertEquals("server:example.test", json.get("indexSourceId").getAsString());
        assertEquals("MANUAL", json.get("conflictStrategy").getAsString());
    }
}
