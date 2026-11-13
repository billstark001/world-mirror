package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.io.MirrorWorldgenDefinition;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.io.IOException;
import java.util.function.BiPredicate;

/** Exact recognition and conversion of earlier released World Mirror generator data. */
public final class LegacyWorldgen {
    /** Unversioned (v0) mirrors used the same one-air-layer flat generator in each dimension. */
    private static final CompoundTag FLAT_V0 = flatGenerator();
    /** Schema 1 before Minecraft 26.3 encoded void noise and block states in the old native form. */
    private static final CompoundTag NOISE_V1_BEFORE_26_3 = WorldStructureCreator
            .createMirrorWorldGenSettings(WorldStructureCreator.GeneratorEncoding.BEFORE_26_3)
            .getCompoundOrEmpty("dimensions");

    private LegacyWorldgen() {}

    public static boolean recognizes(CompoundTag generator, MirrorWorldgenDefinition.Dimension dimension) {
        return generator.equals(FLAT_V0)
                || generator.equals(NOISE_V1_BEFORE_26_3.getCompoundOrEmpty(dimension.dimensionType()).get("generator"));
    }

    /** Upgrades only the exact old generators rejected by the current Minecraft codec. */
    public static void upgrade(CompoundTag settings, CompoundTag current,
                               BiPredicate<CompoundTag, String> canDecode) throws IOException {
        CompoundTag dimensions = settings.getCompoundOrEmpty("dimensions");
        CompoundTag currentDimensions = current.getCompoundOrEmpty("dimensions");
        for (var definition : MirrorWorldgenDefinition.DIMENSIONS) {
            String id = definition.dimensionType();
            CompoundTag dimension = dimensions.getCompoundOrEmpty(id);
            CompoundTag generator = dimension.getCompoundOrEmpty("generator");
            if (canDecode.test(generator, definition.biome())) continue;
            if (!recognizes(generator, definition)) throw new IOException("Unrecognized generator for " + id);
            dimension.put("generator", currentDimensions.getCompoundOrEmpty(id).getCompoundOrEmpty("generator"));
        }
    }

    private static CompoundTag flatGenerator() {
        CompoundTag layer = new CompoundTag();
        layer.putString("block", "minecraft:air");
        layer.putInt("height", 1);
        ListTag layers = new ListTag();
        layers.add(layer);
        CompoundTag settings = new CompoundTag();
        settings.put("layers", layers);
        settings.put("structure_overrides", new ListTag());
        settings.putString("biome", "minecraft:the_void");
        CompoundTag generator = new CompoundTag();
        generator.putString("type", "minecraft:flat");
        generator.put("settings", settings);
        return generator;
    }
}
