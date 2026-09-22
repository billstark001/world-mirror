package io.github.billstark001.worldmirror.io;

import com.google.gson.JsonParser;
import io.github.billstark001.worldmirror.util.JsonSupport;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorWorldgenAssetsTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void voidDensityUsesTheAirDefaultBlockPath() {
        assertEquals(1.0D, MirrorWorldgenDefinition.VOID_FINAL_DENSITY);
    }

    @Test
    void installsACompleteFeaturelessBiomePack(@TempDir Path world) throws Exception {
        MirrorWorldgenAssets.install(world, 99);

        Path pack = world.resolve("datapacks").resolve(MirrorWorldgenAssets.PACK_DIRECTORY);
        MirrorWorldgenJson.PackSection packSection = readModel(
                pack.resolve("pack.mcmeta"), MirrorWorldgenJson.PackDocument.class).pack();
        assertEquals(99, packSection.packFormat());
        assertEquals(99, packSection.minFormat());
        assertEquals(99, packSection.maxFormat());
        assertEquals(MirrorWorldgenAssets.ASSET_REVISION, readModel(
                pack.resolve("worldmirror_manifest.json"),
                MirrorWorldgenJson.AssetManifest.class).assetRevision());
        for (String biome : new String[] {"mirror_overworld", "mirror_nether", "mirror_end"}) {
            MirrorWorldgenJson.LegacyBiome document = readModel(
                    pack.resolve("data/worldmirror/worldgen/biome/" + biome + ".json"),
                    MirrorWorldgenJson.LegacyBiome.class);
            assertTrue(document.features().isEmpty());
            assertTrue(document.carvers().isEmpty());
            assertNotNull(document.spawners());
            assertNotNull(document.spawnCosts());
        }
    }

    @Test
    void installsEnvironmentAttributeBiomesForMinecraft263(@TempDir Path world)
            throws Exception {
        MirrorWorldgenAssets.install(world,
                MirrorWorldgenAssets.ENVIRONMENT_ATTRIBUTE_BIOME_FORMAT);

        Path biome = world.resolve("datapacks")
                .resolve(MirrorWorldgenAssets.PACK_DIRECTORY)
                .resolve("data/worldmirror/worldgen/biome/mirror_overworld.json");
        MirrorWorldgenJson.ModernBiome document = readModel(
                biome, MirrorWorldgenJson.ModernBiome.class);
        assertNotNull(document.attributes().naturalMobSpawns());
        assertNotNull(document.attributes().naturalMobSpawns().spawnsByCategory());
        assertEquals("#78a7ff", document.attributes().skyColor());
        assertTrue(document.features().isEmpty());
        assertTrue(document.carvers().isEmpty());

        String contents = Files.readString(biome);
        assertFalse(contents.contains("\"spawners\""));
        if (SharedConstants.DATA_PACK_FORMAT_MAJOR
                >= MirrorWorldgenAssets.ENVIRONMENT_ATTRIBUTE_BIOME_FORMAT) {
            Biome.DIRECT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(contents))
                    .getOrThrow();
        }
    }

    private static <T> T readModel(Path file, Class<T> modelType) throws Exception {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonSupport.fromJson(reader, modelType);
        }
    }
}
