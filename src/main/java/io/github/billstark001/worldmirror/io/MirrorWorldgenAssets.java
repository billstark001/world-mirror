package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.util.JsonSupport;

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

    public static final int ASSET_REVISION = 2;
    /** Minecraft 26.3 moved biome visuals and natural spawning into environment attributes. */
    static final int ENVIRONMENT_ATTRIBUTE_BIOME_FORMAT = 121;
    public static final String PACK_DIRECTORY = "worldmirror_environment";
    public static final String PACK_ID = "file/" + PACK_DIRECTORY;

    private MirrorWorldgenAssets() {}

    /** Installs (or refreshes) the vanilla-readable data pack for a world. */
    public static void install(Path worldFolder, int dataPackFormat) throws IOException {
        Path pack = worldFolder.resolve("datapacks").resolve(PACK_DIRECTORY);
        write(pack.resolve("pack.mcmeta"),
                MirrorWorldgenJson.packDocument(dataPackFormat));
        write(pack.resolve("worldmirror_manifest.json"),
                MirrorWorldgenJson.assetManifest(ASSET_REVISION));
        writeBiome(pack, "mirror_overworld", 0.8F, 0.4F, 12638463, 7907327,
                dataPackFormat);
        writeBiome(pack, "mirror_nether", 2.0F, 0.0F, 3344392, 7254527,
                dataPackFormat);
        writeBiome(pack, "mirror_end", 0.5F, 0.5F, 10518688, 0,
                dataPackFormat);
    }

    private static void writeBiome(Path pack, String name, float temperature, float downfall,
                                   int fogColor, int skyColor, int dataPackFormat)
            throws IOException {
        if (dataPackFormat >= ENVIRONMENT_ATTRIBUTE_BIOME_FORMAT) {
            writeModernBiome(pack, name, temperature, downfall, fogColor, skyColor);
            return;
        }
        write(pack.resolve("data/worldmirror/worldgen/biome/" + name + ".json"),
                MirrorWorldgenJson.legacyBiome(
                        temperature, downfall, fogColor, skyColor));
    }

    private static void writeModernBiome(Path pack, String name, float temperature,
                                         float downfall, int fogColor, int skyColor)
            throws IOException {
        write(pack.resolve("data/worldmirror/worldgen/biome/" + name + ".json"),
                MirrorWorldgenJson.modernBiome(
                        temperature, downfall, fogColor, skyColor));
    }

    private static void write(Path file, Object model) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, JsonSupport.toPrettyJson(model) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }
}
