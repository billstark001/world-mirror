package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorWorldContextTest {

    @Test
    void recognizesLegacyMetadataWithoutAWorldgenSchema(@TempDir Path world) throws Exception {
        Files.writeString(world.resolve(WorldMetadata.FILE_NAME), """
                { "sourceId": "server:example.test", "sourceType": "server" }
                """);

        assertEquals(MirrorWorldContext.State.OUTDATED, MirrorWorldContext.inspect(world, 100).state());
    }

    @Test
    void rejectsNewerSchemasInsteadOfDowngradingThem(@TempDir Path world) {
        WorldMetadata metadata = metadata();
        metadata.worldgenSchema = WorldMetadata.CURRENT_WORLDGEN_SCHEMA + 1;
        metadata.worldgenAssetRevision = MirrorWorldgenAssets.ASSET_REVISION + 1;
        metadata.worldgenAssetDataVersion = 101;
        metadata.save(world);

        assertEquals(MirrorWorldContext.State.FUTURE, MirrorWorldContext.inspect(world, 100).state());
        assertTrue(metadata.hasFutureWorldgenSchema());
        assertTrue(metadata.hasFutureWorldgenAssets(100, MirrorWorldgenAssets.ASSET_REVISION));
        assertFalse(metadata.needsWorldgenAssetRefresh(100, MirrorWorldgenAssets.ASSET_REVISION));
    }

    private static WorldMetadata metadata() {
        WorldMetadata metadata = new WorldMetadata();
        metadata.sourceId = "server:example.test";
        metadata.sourceType = "server";
        return metadata;
    }
}
