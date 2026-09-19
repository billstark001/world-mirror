package io.github.billstark001.worldmirror.io;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static java.nio.file.Files.readString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(readString(pack.resolve("pack.mcmeta")).contains("\"pack_format\": 99"));
        assertTrue(readString(pack.resolve("pack.mcmeta")).contains("\"min_format\": 99"));
        assertTrue(readString(pack.resolve("pack.mcmeta")).contains("\"max_format\": 99"));
        assertTrue(readString(pack.resolve("worldmirror_manifest.json"))
                .contains("\"assetRevision\": " + MirrorWorldgenAssets.ASSET_REVISION));
        for (String biome : new String[] {"mirror_overworld", "mirror_nether", "mirror_end"}) {
            String contents = readString(pack.resolve("data/worldmirror/worldgen/biome/" + biome + ".json"));
            assertTrue(contents.contains("\"features\": []"));
            assertTrue(contents.contains("\"carvers\": []"));
            assertTrue(contents.contains("\"spawners\": {}"));
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
        String contents = readString(biome);
        assertTrue(contents.contains("\"minecraft:gameplay/natural_mob_spawns\""));
        assertTrue(contents.contains("\"spawns_by_category\": {}"));
        assertTrue(contents.contains("\"minecraft:visual/sky_color\": \"#78a7ff\""));
        assertTrue(contents.contains("\"features\": []"));
        assertFalse(contents.contains("\"spawners\""));
        if (SharedConstants.DATA_PACK_FORMAT_MAJOR
                >= MirrorWorldgenAssets.ENVIRONMENT_ATTRIBUTE_BIOME_FORMAT) {
            Biome.DIRECT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(contents))
                    .getOrThrow();
        }
    }
}
