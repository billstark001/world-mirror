package io.github.billstark001.worldmirror.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Version-independent contents of the data pack embedded in every mirror world.
 *
 * <p>The version-specific world writers only enable this pack and reference its
 * biomes.  Keeping the files here makes the pack revision explicit and prevents
 * the three Minecraft targets from slowly acquiring different semantics.</p>
 */
public final class MirrorWorldgenAssets {

    public static final int ASSET_REVISION = 1;
    public static final String PACK_DIRECTORY = "worldmirror_environment";
    public static final String PACK_ID = "file/" + PACK_DIRECTORY;

    private MirrorWorldgenAssets() {}

    /** Installs (or refreshes) the vanilla-readable data pack for a world. */
    public static void install(Path worldFolder, int dataPackFormat) throws IOException {
        Path pack = worldFolder.resolve("datapacks").resolve(PACK_DIRECTORY);
        write(pack.resolve("pack.mcmeta"), """
                {
                  "pack": {
                    "pack_format": %d,
                    "description": "World Mirror environment definitions"
                  }
                }
                """.formatted(dataPackFormat));
        write(pack.resolve("worldmirror_manifest.json"), """
                {
                  "assetRevision": %d
                }
                """.formatted(ASSET_REVISION));
        writeBiome(pack, "mirror_overworld", 0.8F, 0.4F, 12638463, 7907327);
        writeBiome(pack, "mirror_nether", 2.0F, 0.0F, 3344392, 7254527);
        writeBiome(pack, "mirror_end", 0.5F, 0.5F, 10518688, 0);
    }

    private static void writeBiome(Path pack, String name, float temperature, float downfall,
                                   int fogColor, int skyColor) throws IOException {
        write(pack.resolve("data/worldmirror/worldgen/biome/" + name + ".json"), """
                {
                  "has_precipitation": true,
                  "temperature": %s,
                  "downfall": %s,
                  "effects": {
                    "fog_color": %d,
                    "sky_color": %d,
                    "water_color": 4159204,
                    "water_fog_color": 329011
                  },
                  "carvers": [],
                  "features": [],
                  "spawners": {},
                  "spawn_costs": {}
                }
                """.formatted(temperature, downfall, fogColor, skyColor));
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }
}
