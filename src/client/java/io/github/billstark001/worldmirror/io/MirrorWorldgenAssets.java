package io.github.billstark001.worldmirror.io;

import com.google.gson.JsonParser;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.JsonSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Version-independent contents of the data pack embedded in every mirror world.
 *
 * <p>The version-specific world writers only enable this pack and reference its
 * biomes.  Keeping the files here makes the pack revision explicit and prevents
 * supported Minecraft targets from slowly acquiring different semantics.</p>
 */
public final class MirrorWorldgenAssets {
    /** Global, monotonically increasing revision shared by every Minecraft target. */
    public static final int ASSET_REVISION = 3;
    /** Minecraft 26.3 moved biome visuals and natural spawning into environment attributes. */
    static final int ENVIRONMENT_ATTRIBUTE_BIOME_FORMAT = 121;
    public static final String PACK_DIRECTORY = "worldmirror_environment";
    public static final String PACK_ID = "file/" + PACK_DIRECTORY;

    private MirrorWorldgenAssets() {}

    /** Installs (or refreshes) the vanilla-readable data pack for a world. */
    public static void install(Path worldFolder, int dataPackFormat) throws IOException {
        Path pack = worldFolder.resolve("datapacks").resolve(PACK_DIRECTORY);
        for (var entry : documents(dataPackFormat).entrySet())
            JsonSupport.writeDocument(pack.resolve(entry.getKey()), entry.getValue());
    }

    /** A metadata-free world can only be adopted with the complete, unmodified current pack. */
    static boolean matchesInstalled(Path worldFolder, int dataPackFormat) throws IOException {
        Path pack = worldFolder.resolve("datapacks").resolve(PACK_DIRECTORY);
        FileProof.requirePlainPath(worldFolder, pack);
        FileProof.requirePlainDirectory(pack);
        if (!Files.isDirectory(pack))
            return false;
        var expected = documents(dataPackFormat);
        int files = 0;
        try (var paths = Files.walk(pack)) {
            for (var item : paths.toList()) {
                FileProof.requirePlainPath(pack, item);
                if (Files.isDirectory(item))
                    continue;
                var model = expected.get(pack.relativize(item));
                if (model == null || FileProof.requirePlainFile(item).size() > 65536)
                    return false;
                var disk = JsonSupport.readDocument(item, com.google.gson.JsonObject.class);
                if (!JsonParser.parseString(JsonSupport.toPrettyJson(model)).equals(disk.value()))
                    return false;
                files++;
            }
        }
        return files == expected.size();
    }

    private static Map<Path, Object> documents(int format) {
        Map<Path, Object> files = new LinkedHashMap<>();
        files.put(Path.of("pack.mcmeta"), MirrorWorldgenJson.packDocument(format));
        files.put(Path.of("worldmirror_manifest.json"), MirrorWorldgenJson.assetManifest(ASSET_REVISION));
        files.put(biome("mirror_overworld"), biome(format, 0.8F, 0.4F, 12638463, 7907327));
        files.put(biome("mirror_nether"), biome(format, 2.0F, 0.0F, 3344392, 7254527));
        files.put(biome("mirror_end"), biome(format, 0.5F, 0.5F, 10518688, 0));
        return files;
    }

    private static Path biome(String name) {
        return Path.of("data/worldmirror/worldgen/biome", name + ".json");
    }

    private static Object biome(int format, float temperature, float downfall, int fogColor, int skyColor) {
        return format >= ENVIRONMENT_ATTRIBUTE_BIOME_FORMAT
                ? MirrorWorldgenJson.modernBiome(temperature, downfall, fogColor, skyColor)
                : MirrorWorldgenJson.biomeBeforeEnvironmentAttributes(
                          temperature, downfall, fogColor, skyColor);
    }
}
