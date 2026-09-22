package io.github.billstark001.worldmirror.io;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Locale;

/** JSON document models installed into a mirror world's generated data pack. */
final class MirrorWorldgenJson {
    private static final EmptyObject EMPTY = new EmptyObject();
    private static final String WATER_COLOR = "#3f76e4";
    private static final String WATER_FOG_COLOR = "#032e3f";

    private MirrorWorldgenJson() { }

    static PackDocument packDocument(int format) {
        return new PackDocument(new PackSection(
                format, format, format, "World Mirror environment definitions"));
    }

    static AssetManifest assetManifest(int revision) {
        return new AssetManifest(revision);
    }

    static LegacyBiome legacyBiome(float temperature, float downfall,
                                   int fogColor, int skyColor) {
        return new LegacyBiome(
                true,
                temperature,
                downfall,
                new LegacyEffects(fogColor, skyColor, 4159204, 329011),
                List.of(),
                List.of(),
                EMPTY,
                EMPTY);
    }

    static ModernBiome modernBiome(float temperature, float downfall,
                                   int fogColor, int skyColor) {
        return new ModernBiome(
                true,
                temperature,
                downfall,
                new EnvironmentAttributes(
                        new NaturalMobSpawns(EMPTY, EMPTY),
                        color(fogColor),
                        color(skyColor),
                        WATER_FOG_COLOR),
                new ModernEffects(WATER_COLOR),
                List.of(),
                List.of());
    }

    private static String color(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb);
    }

    record PackDocument(PackSection pack) { }

    record PackSection(
            @SerializedName("pack_format") int packFormat,
            @SerializedName("min_format") int minFormat,
            @SerializedName("max_format") int maxFormat,
            String description) { }

    record AssetManifest(int assetRevision) { }

    record LegacyBiome(
            @SerializedName("has_precipitation") boolean hasPrecipitation,
            float temperature,
            float downfall,
            LegacyEffects effects,
            List<String> carvers,
            List<List<String>> features,
            EmptyObject spawners,
            @SerializedName("spawn_costs") EmptyObject spawnCosts) { }

    record LegacyEffects(
            @SerializedName("fog_color") int fogColor,
            @SerializedName("sky_color") int skyColor,
            @SerializedName("water_color") int waterColor,
            @SerializedName("water_fog_color") int waterFogColor) { }

    record ModernBiome(
            @SerializedName("has_precipitation") boolean hasPrecipitation,
            float temperature,
            float downfall,
            EnvironmentAttributes attributes,
            ModernEffects effects,
            List<String> carvers,
            List<List<String>> features) { }

    record EnvironmentAttributes(
            @SerializedName("minecraft:gameplay/natural_mob_spawns")
            NaturalMobSpawns naturalMobSpawns,
            @SerializedName("minecraft:visual/fog_color") String fogColor,
            @SerializedName("minecraft:visual/sky_color") String skyColor,
            @SerializedName("minecraft:visual/water_fog_color") String waterFogColor) { }

    record NaturalMobSpawns(
            @SerializedName("spawn_costs") EmptyObject spawnCosts,
            @SerializedName("spawns_by_category") EmptyObject spawnsByCategory) { }

    record ModernEffects(@SerializedName("water_color") String waterColor) { }

    static final class EmptyObject {
        private EmptyObject() { }
    }
}
