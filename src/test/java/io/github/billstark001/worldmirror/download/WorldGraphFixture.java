package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.util.JsonSupport;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class WorldGraphFixture {
    static final String SOURCE = "server:Example.org:25565";
    static final String ENDPOINT = "example.org";
    static final String REMOTE = WorldGraphConfig.deterministicId("remote", ENDPOINT);
    protected static final String MIRROR_UUID = "a0866b26-53bd-42f1-97be-753132b30794";
    protected static final String MIRROR = "mirror:" + MIRROR_UUID;
    @TempDir protected Path game;

    protected WorldGraphService service() {
        return new WorldGraphService(game, configDirectory());
    }

    protected Path configDirectory() {
        return game.resolve("config/worldmirror");
    }

    WorldGraphService.Source remote() {
        return new WorldGraphService.Source(null, ENDPOINT, ENDPOINT);
    }

    protected Path folder(String root, String name) throws IOException {
        return Files.createDirectories(game.resolve(root).resolve(name));
    }

    static void writeMirror(Path folder, String id, String parent) {
        assertTrue(WorldMetadata.create(id, WorldGraph.Kind.MIRROR, parent, WorldGraph.Role.SYNC, SOURCE)
                        .save(folder));
    }

    Path legacyConfig(String name, ModConfig.SaveLocation location) throws IOException {
        Files.createDirectories(configDirectory());
        Path file = configDirectory().resolve("mirrors.json");
        JsonSupport.writeDocument(file,
                Map.of("entries", Map.of(SOURCE, name), "resolvedFolderNames",
                        Map.of(SOURCE + '\0' + location.name(), name), "perWorldSaveLocation",
                        Map.of(SOURCE, location), "perWorldConflictStrategy",
                        Map.of(SOURCE, ModConfig.ConflictStrategy.MANUAL)));
        return file;
    }
}
