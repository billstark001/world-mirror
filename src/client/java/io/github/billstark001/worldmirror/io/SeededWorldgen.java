package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;

/** Encodes and samples the native normal preset using the shipped vanilla registries. */
final class SeededWorldgen {
    private SeededWorldgen() {}

    private static final class NormalPreset {
#if MC_VERSION >= 260300
        static final HolderLookup.Provider REGISTRIES = VanillaRegistries.createWorldLookup();
#else
        static final HolderLookup.Provider REGISTRIES = VanillaRegistries.createLookup();
#endif
        static final CompoundTag SETTINGS = encode();

        private static CompoundTag encode() {
            var settings = new WorldGenSettings(
                    new WorldOptions(0L, true, false), WorldPresets.createNormalWorldDimensions(REGISTRIES));
            var result =
                    WorldGenSettings.CODEC
                            .encodeStart(REGISTRIES.createSerializationContext(NbtOps.INSTANCE), settings)
                            .getOrThrow();
            if (!(result instanceof CompoundTag compound))
                throw new IllegalStateException("Native normal world settings must encode to a compound");
            return compound;
        }
    }

    static CompoundTag settings(long seed) {
        CompoundTag result = NormalPreset.SETTINGS.copy();
        result.putLong("seed", seed);
        return result;
    }

    static CompoundTag readSettings(Path worldFolder) throws IOException {
        Path file = WorldStructureApi.worldgenDataPath(worldFolder);
        FileProof.requirePlainPath(worldFolder, file);
        var root = NbtReadSupport.readWorldData(file);
        MirrorWorldgenCheck.requireSupportedVersion(root);
        CompoundTag settings = WorldStructureApi.worldgenData(root);
        if (settings == null)
            throw new IOException("Missing native world generation settings");
        return settings;
    }

    static long seed(CompoundTag settings) throws IOException {
        if (!(settings.get("seed") instanceof LongTag seed))
            throw new IOException("Missing native seed");
        return seed.value();
    }

    static String digest(CompoundTag settings) {
        // The native name of this option changed between 1.21.11 and 26.x. Keep one stable
        // representation for the digest, while leaving each save in its native representation.
#if MC_VERSION < 260102
        if (settings.contains("generate_features") && !settings.contains("generate_structures")) {
            settings = settings.copy();
            settings.put("generate_structures", settings.remove("generate_features"));
        }
#endif
        // Vanilla sorts compound keys recursively when writing SNBT; list ordering remains meaningful.
        byte[] bytes = settings.toString().getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(FileProof.sha256().digest(bytes));
    }

    static WorldStructureCreator.SeedSurface surface(long seed) {
        var generator =
                (NoiseBasedChunkGenerator) WorldPresets.createNormalWorldDimensions(NormalPreset.REGISTRIES)
                        .overworld();
        var noise = NormalPreset.REGISTRIES.lookupOrThrow(Registries.NOISE);
#if MC_VERSION >= 260300
        var random = RandomState.create(noise, seed, generator.generatorSettings().value());
#else
        var random = RandomState.create(generator.generatorSettings().value(), noise, seed);
#endif
        var height = LevelHeightAccessor.create(generator.getMinY(), generator.getGenDepth());
        return (x, z) -> generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
    }
}
