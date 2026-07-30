package io.github.billstark001.worldmirror.io;

import java.util.List;

/** Version-neutral description consumed by the Minecraft-specific NBT adapters. */
public final class MirrorWorldgenDefinition {

    /**
     * A positive density selects {@code default_block} (air) instead of the
     * aquifer/fluid branch, whose vanilla fallback places lava below Y=-54.
     */
    public static final double VOID_FINAL_DENSITY = 1.0D;

    public static final List<String> ZERO_NOISE_ROUTER_FIELDS = List.of(
            "barrier", "fluid_level_floodedness", "fluid_level_spread", "lava",
            "temperature", "vegetation", "continents", "erosion", "depth", "ridges",
            "preliminary_surface_level", "vein_toggle", "vein_ridged", "vein_gap");

    public static final List<Dimension> DIMENSIONS = List.of(
            new Dimension("minecraft:overworld", "worldmirror:mirror_overworld", -64, 384, 63, 1, 2),
            new Dimension("minecraft:the_end", "worldmirror:mirror_end", 0, 128, 0, 2, 1),
            new Dimension("minecraft:the_nether", "worldmirror:mirror_nether", 0, 128, 32, 1, 2));

    private MirrorWorldgenDefinition() {}

    public record Dimension(String dimensionType, String biome, int minY, int height,
                            int seaLevel, int horizontalSize, int verticalSize) {}
}
