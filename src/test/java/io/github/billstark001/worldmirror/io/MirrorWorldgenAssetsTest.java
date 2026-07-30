package io.github.billstark001.worldmirror.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static java.nio.file.Files.readString;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorWorldgenAssetsTest {

    @Test
    void installsACompleteFeaturelessBiomePack(@TempDir Path world) throws Exception {
        MirrorWorldgenAssets.install(world, 99);

        Path pack = world.resolve("datapacks").resolve(MirrorWorldgenAssets.PACK_DIRECTORY);
        assertTrue(readString(pack.resolve("pack.mcmeta")).contains("\"pack_format\": 99"));
        assertTrue(readString(pack.resolve("worldmirror_manifest.json"))
                .contains("\"assetRevision\": " + MirrorWorldgenAssets.ASSET_REVISION));
        for (String biome : new String[] {"mirror_overworld", "mirror_nether", "mirror_end"}) {
            String contents = readString(pack.resolve("data/worldmirror/worldgen/biome/" + biome + ".json"));
            assertTrue(contents.contains("\"features\": []"));
            assertTrue(contents.contains("\"carvers\": []"));
            assertTrue(contents.contains("\"spawners\": {}"));
        }
    }
}
