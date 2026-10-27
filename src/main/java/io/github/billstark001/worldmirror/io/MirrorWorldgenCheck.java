package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, read-only generator checks; cached entries never retain world NBT trees. */
public final class MirrorWorldgenCheck {
    public enum State { CURRENT, LEGACY, FUTURE, UNRECOGNIZED, UNREADABLE }
    public record Result(State state, String problem) { }
    private record Cached(FileProof.Version version, Result result) { }
    private static final int MAX_ENTRIES = 128;
    private static final Map<Path, Cached> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final Object[] LOCKS = new Object[32];
    static { Arrays.setAll(LOCKS, ignored -> new Object()); }

    private static final class FutureData extends IOException { }

    private MirrorWorldgenCheck() { }

    public static Result inspect(Path world) { return inspect(world, false); }

    /** A migration must inspect the bytes again after acquiring its native world lease. */
    public static Result inspectFresh(Path world) { return inspect(world, true); }

    private static Result inspect(Path world, boolean fresh) {
        Path file = WorldStructureApi.worldgenDataPath(world).toAbsolutePath().normalize();
        synchronized (LOCKS[Math.floorMod(file.hashCode(), LOCKS.length)]) {
            try {
                FileProof.Version before = FileProof.version(file);
                if (!fresh) {
                    synchronized (CACHE) {
                        Cached cached = CACHE.get(file);
                        if (cached != null && cached.version().equals(before)) return cached.result();
                    }
                }
                Result result;
                try { result = classify(readSettings(file)); }
                catch (FutureData future) { result = new Result(State.FUTURE, "worldgen_future_data_version"); }
                if (!before.equals(FileProof.version(file))) {
                    return new Result(State.UNREADABLE, "worldgen_changed_during_read");
                }
                synchronized (CACHE) {
                    CACHE.put(file, new Cached(before, result));
                    while (CACHE.size() > MAX_ENTRIES) CACHE.remove(CACHE.keySet().iterator().next());
                }
                return result;
            } catch (IOException | RuntimeException failure) {
                io.github.billstark001.worldmirror.util.WMLogger.warnRateLimited(
                        "worldgen-read-" + file, 30_000L, "Cannot inspect mirror world generation: " + file, failure);
                return new Result(State.UNREADABLE, "worldgen_unreadable");
            }
        }
    }

    private static CompoundTag readSettings(Path file) throws IOException {
        return extractSettings(NbtReadSupport.readWorldData(file));
    }

    private static CompoundTag extractSettings(CompoundTag root) throws IOException {
        requireSupportedVersion(root);
        CompoundTag settings = WorldStructureApi.worldgenData(root);
        if (settings == null) throw new IOException("Missing world generation settings");
        return settings;
    }

    /** Applies to both the level.dat and saved-data wrappers touched by a generator repair. */
    static void requireSupportedVersion(CompoundTag root) throws IOException {
        int dataVersion = root.getIntOr("DataVersion",
                root.getCompoundOrEmpty("Data").getIntOr("DataVersion", 0));
        if (dataVersion > net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version()) {
            throw new FutureData();
        }
    }

    private static Result classify(CompoundTag settings) {
        if (!(settings.get("dimensions") instanceof CompoundTag dimensions)) {
            return new Result(State.UNREADABLE, "worldgen_dimensions_missing");
        }
        CompoundTag legacy = WorldStructureCreator.createMirrorWorldGenSettings(false)
                .getCompoundOrEmpty("dimensions");
        CompoundTag flat = legacyFlatGenerator();
        boolean outdated = false;
        for (var definition : MirrorWorldgenDefinition.DIMENSIONS) {
            String id = definition.dimensionType();
            if (!(dimensions.get(id) instanceof CompoundTag dimension)
                    || !id.equals(dimension.getStringOr("type", ""))
                    || !(dimension.get("generator") instanceof CompoundTag generator)) {
                return new Result(State.UNRECOGNIZED, "worldgen_unrecognized_dimension:" + id);
            }
            boolean knownLegacy = generator.equals(legacy.getCompoundOrEmpty(id).get("generator"))
                    || generator.equals(flat);
            boolean readable = decodes(generator, definition.biome());
            if (knownLegacy && !readable) {
                outdated = true;
            } else if (!readable) {
                return new Result(State.UNRECOGNIZED, "worldgen_unrecognized_generator:" + id);
            }
        }
        return new Result(outdated ? State.LEGACY : State.CURRENT, null);
    }

    private static boolean decodes(CompoundTag generator, String biome) {
        if (!"minecraft:noise".equals(generator.getStringOr("type", ""))
                || !(generator.get("biome_source") instanceof CompoundTag source)
                || !"minecraft:fixed".equals(source.getStringOr("type", ""))
                || !biome.equals(source.getStringOr("biome", ""))
                || !(generator.get("settings") instanceof CompoundTag settings)) return false;
        return NoiseGeneratorSettings.DIRECT_CODEC.parse(NbtOps.INSTANCE, settings).isSuccess();
    }

    /** Replaces only recognized obsolete generators, retaining other dimensions and world options. */
    static CompoundTag prepareUpdate(CompoundTag root) throws IOException {
        CompoundTag settings = extractSettings(root);
        Result result = classify(settings);
        if (result.state() != State.CURRENT && result.state() != State.LEGACY) {
            throw new IOException("Refusing to replace unknown world generation: " + result.problem());
        }
        if (result.state() == State.CURRENT) return settings;
        CompoundTag dimensions = settings.getCompoundOrEmpty("dimensions");
        CompoundTag current = WorldStructureCreator.createMirrorWorldGenSettings()
                .getCompoundOrEmpty("dimensions");
        for (var definition : MirrorWorldgenDefinition.DIMENSIONS) {
            CompoundTag dimension = dimensions.getCompoundOrEmpty(definition.dimensionType());
            CompoundTag generator = dimension.getCompoundOrEmpty("generator");
            if (!decodes(generator, definition.biome())) {
                dimension.put("generator", current.getCompoundOrEmpty(definition.dimensionType())
                        .getCompoundOrEmpty("generator"));
            }
        }
        if (classify(settings).state() != State.CURRENT) throw new IOException("Invalid replacement world generation");
        return settings;
    }

    /** Exact data compatibility for the released one-air-layer generator, not arbitrary flat worlds. */
    private static CompoundTag legacyFlatGenerator() {
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
