package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.OperationFailures;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/** Version-neutral orchestration for creating and updating playable mirror saves. */
@Environment(EnvType.CLIENT)
public final class WorldStructureCreator {
    public enum Setting { TIME, WEATHER, DIFFICULTY }
    public enum GeneratorEncoding { BEFORE_26_3, FROM_26_3 }

    @FunctionalInterface
    public interface SeedSurface {
        int height(int blockX, int blockZ);
    }

    private WorldStructureCreator() {}

    public static WorldSettingsSnapshot captureWorldSettings(ClientLevel world) {
        if (world == null)
            return WorldSettingsSnapshot.defaults();
        try {
            return WorldStructureApi.captureWorldSettings(world);
        } catch (Exception e) {
            WMLogger.warnRateLimited("world-settings-capture", 30_000L,
                    "Could not capture source world settings; using defaults", e);
            return WorldSettingsSnapshot.defaults();
        }
    }

    public static WorldSettingsSnapshot resolveNewWorldSettings(WorldSettingsSnapshot current) {
        WorldSettingsSnapshot defaults = WorldSettingsSnapshot.defaults();
        ModConfig config = ModConfig.get();
        return new WorldSettingsSnapshot(
                config.newWorldTime == ModConfig.NewWorldSettingBehavior.FOLLOW_CURRENT ? current.gameTime()
                                                                                        : defaults.gameTime(),
                config.newWorldTime == ModConfig.NewWorldSettingBehavior.FOLLOW_CURRENT ? current.dayTime()
                                                                                        : defaults.dayTime(),
                config.newWorldWeather == ModConfig.NewWorldSettingBehavior.FOLLOW_CURRENT
                        && current.raining(),
                config.newWorldWeather == ModConfig.NewWorldSettingBehavior.FOLLOW_CURRENT
                        && current.thundering(),
                config.newWorldDifficulty == ModConfig.NewWorldSettingBehavior.FOLLOW_CURRENT
                        ? current.difficultyId()
                        : defaults.difficultyId());
    }

    public static CompoundTag createMirrorWorldGenSettings() {
        GeneratorEncoding encoding =
                WorldStructureApi.dataPackFormat() >= MirrorWorldgenAssets.ENVIRONMENT_ATTRIBUTE_BIOME_FORMAT
                ? GeneratorEncoding.FROM_26_3
                : GeneratorEncoding.BEFORE_26_3;
        return createMirrorWorldGenSettings(encoding);
    }

    /** Encodes the same generator for the selected native Minecraft settings format. */
    public static CompoundTag createMirrorWorldGenSettings(GeneratorEncoding encoding) {
        CompoundTag worldGenSettings = new CompoundTag();
        CompoundTag dimensions = new CompoundTag();
        for (MirrorWorldgenDefinition.Dimension definition : MirrorWorldgenDefinition.DIMENSIONS) {
            CompoundTag dimension = new CompoundTag();
            dimension.put("generator",
                    createVoidNoiseGenerator(definition.biome(), definition.minY(), definition.height(),
                            definition.seaLevel(), definition.horizontalSize(), definition.verticalSize(),
                            encoding == GeneratorEncoding.FROM_26_3));
            dimension.putString("type", definition.dimensionType());
            dimensions.put(definition.dimensionType(), dimension);
        }
        worldGenSettings.put("dimensions", dimensions);
        worldGenSettings.putByte("bonus_chest", (byte) 0);
        worldGenSettings.putByte(WorldStructureApi.structureGenerationKey(), (byte) 0);
        worldGenSettings.putLong("seed", 0L);
        return worldGenSettings;
    }

    /** The native paths a transaction must stage before converting generation. */
    public static List<Path> generationFiles(Path worldFolder) {
        Path level = Path.of("level.dat");
        Path settings = worldFolder.relativize(WorldStructureApi.worldgenDataPath(worldFolder));
        return settings.equals(level) ? List.of(level) : List.of(level, settings);
    }

    public static OptionalLong parseSeed(String text) {
        return net.minecraft.world.level.levelgen.WorldOptions.parseSeed(text);
    }

    public static long readSeed(Path worldFolder) throws IOException {
        return SeededWorldgen.seed(SeededWorldgen.readSettings(worldFolder));
    }

    public static SeedSurface seedSurface(long seed) {
        return SeededWorldgen.surface(seed);
    }

    /** Installs a native normal preset in a staging tree; the caller removes the live owned pack transactionally. */
    public static String stageSeededGeneration(Path stagedWorld, long seed) throws IOException {
        if (!hasCanonicalVoidGeneration(stagedWorld))
            throw new IOException("Seed conversion requires the current, unmodified World Mirror generator");
        return writeStagedSeededSettings(stagedWorld, SeededWorldgen.settings(seed));
    }

    /** Replaces only the seed in a previously verified native normal preset. */
    public static String stageReseededGeneration(Path stagedWorld, String existingDigest, long seed)
            throws IOException {
        CompoundTag settings = SeededWorldgen.readSettings(stagedWorld);
        if (!matchesSeededSettings(stagedWorld, settings, existingDigest))
            throw new IOException("Assigned world generation changed before reseeding");
        SeededWorldgen.seed(settings);
        settings.putLong("seed", seed);
        return writeStagedSeededSettings(stagedWorld, settings);
    }

    private static String writeStagedSeededSettings(Path stagedWorld, CompoundTag settings)
            throws IOException {
        WorldStructureApi.replaceGeneration(stagedWorld, settings, false);
        CompoundTag verified = SeededWorldgen.readSettings(stagedWorld);
        if (!settings.equals(verified) || WorldStructureApi.hasMirrorDataPack(stagedWorld))
            throw new IOException("Staged normal world generation did not round-trip");
        return SeededWorldgen.digest(verified);
    }

    /** Installs the owned void generator and its data pack in a staging tree. */
    public static void stageVoidGeneration(Path stagedWorld) throws IOException {
        MirrorWorldgenAssets.install(stagedWorld, WorldStructureApi.dataPackFormat());
        WorldStructureApi.replaceGeneration(stagedWorld, createMirrorWorldGenSettings(), true);
        if (MirrorWorldgenCheck.inspectFresh(stagedWorld).state() != MirrorWorldgenCheck.State.CURRENT
                || !WorldStructureApi.hasMirrorDataPack(stagedWorld))
            throw new IOException("Staged void world generation did not round-trip");
    }

    public static boolean hasCanonicalVoidGeneration(Path worldFolder) throws IOException {
        return WorldStructureApi.matchesOwnedVoidSettings(
                       SeededWorldgen.readSettings(worldFolder), createMirrorWorldGenSettings())
                && WorldStructureApi.hasMirrorDataPack(worldFolder);
    }

    public static boolean hasUnmodifiedVoidAssets(Path worldFolder) throws IOException {
        return MirrorWorldgenAssets.matchesInstalled(worldFolder, WorldStructureApi.dataPackFormat());
    }

    /** Checks the stored generator digest without reading player data or the SQLite index. */
    public static boolean matchesSeededGeneration(Path worldFolder, String digest) throws IOException {
        return matchesSeededSettings(worldFolder, SeededWorldgen.readSettings(worldFolder), digest);
    }

    private static boolean matchesSeededSettings(Path worldFolder, CompoundTag settings, String digest)
            throws IOException {
        return settings.get(WorldStructureApi.structureGenerationKey()) instanceof ByteTag
                && digest.equals(SeededWorldgen.digest(settings))
                && !WorldStructureApi.hasMirrorDataPack(worldFolder);
    }

    public static boolean createLoadableWorldWithSpawn(Path worldFolder, String levelName, int spawnX,
            int spawnY, int spawnZ, WorldSettingsSnapshot settings) {
        return createLoadableWorldWithSpawn(
                worldFolder, levelName, Level.OVERWORLD, spawnX, spawnY, spawnZ, settings);
    }

    /** Creates a nearby save whose initial player and world spawn use the captured dimension. */
    public static boolean createLoadableWorldWithSpawn(Path worldFolder, String levelName,
            ResourceKey<Level> spawnDimension, int spawnX, int spawnY, int spawnZ,
            WorldSettingsSnapshot settings) {
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            if (!createVoidWorld(worldFolder, levelName, settings))
                return false;
            UUID playerId = singleplayerUuid(levelName);
            WorldStructureApi.writeSpawnedLevelData(worldFolder, levelName, playerId, spawnDimension, spawnX,
                    spawnY, spawnZ, createMirrorWorldGenSettings(), settings);
            writeVerifiedWorldData(WorldStructureApi.playerDataPath(worldFolder, playerId),
                    createPlayerData(spawnDimension, spawnX, spawnY, spawnZ));
            WMLogger.debug("Nearby-export world created at: " + worldFolder.toAbsolutePath());
            return true;
        } catch (Exception e) {
            WMLogger.warn("Nearby export world structure creation failed path=" + worldFolder, e);
            return false;
        }
    }

    /** Initializes a new mirror with the current owned void generator. */
    public static boolean createVoidWorld(
            Path worldFolder, String levelName, WorldSettingsSnapshot settings) {
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            Path level = worldFolder.resolve("level.dat");
            if (!Files.notExists(level, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Cannot create a mirror over existing world data: " + level);
            ensureWorldDirectories(worldFolder);
            UUID playerId = singleplayerUuid(levelName);
            Path playerData = WorldStructureApi.playerDataPath(worldFolder, playerId);
            FileProof.requirePlainPath(worldFolder, playerData);
            FileProof.requirePlainPath(worldFolder,
                    worldFolder.resolve("datapacks").resolve(MirrorWorldgenAssets.PACK_DIRECTORY));
            MirrorWorldgenAssets.install(worldFolder, WorldStructureApi.dataPackFormat());
            WorldStructureApi.createInitialWorld(
                    worldFolder, levelName, playerId, createMirrorWorldGenSettings(), settings);
            writeVerifiedWorldData(playerData, createPlayerData(0, 80, 0));
            WMLogger.debug("World structure created at: " + worldFolder.toAbsolutePath()
                    + " (name: " + resolvedLevelName(levelName) + ")");
            return true;
        } catch (Exception e) {
            WMLogger.warn("Mirror world structure creation failed path=" + worldFolder, e);
            return false;
        }
    }

    /** Refreshes the generator and pack for a verified void mirror or migration stage. */
    public static boolean upgradeVoidWorld(Path stagedWorld, String levelName) {
        try (WorldWriteLock ignored = WorldWriteLock.acquire(stagedWorld)) {
            requireWorldData(stagedWorld);
            ensureWorldDirectories(stagedWorld);
            repairOptionalSavedData(stagedWorld, levelName);
            MirrorWorldgenAssets.install(stagedWorld, WorldStructureApi.dataPackFormat());
            WorldStructureApi.updateOwnedLevelData(stagedWorld);
            return true;
        } catch (Exception e) {
            WMLogger.warn("Mirror world generation upgrade failed path=" + stagedWorld, e);
            return false;
        }
    }

    /** Repairs optional saved data while leaving the selected generator and its packs alone. */
    public static boolean maintainWorld(Path worldFolder, String levelName) {
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            requireWorldData(worldFolder);
            ensureWorldDirectories(worldFolder);
            repairOptionalSavedData(worldFolder, levelName);
            return true;
        } catch (Exception e) {
            WMLogger.warn("Mirror world maintenance failed path=" + worldFolder, e);
            return false;
        }
    }

    private static void ensureWorldDirectories(Path worldFolder) {
        for (String directory : WorldStructureApi.worldSubdirectories()) {
            Path path = worldFolder.resolve(directory);
            try {
                FileProof.requirePlainPath(worldFolder, path);
                Files.createDirectories(path);
            } catch (IOException failure) {
                // Each writer validates its own destination; unrelated directories can still be used.
                WMLogger.warnRateLimited("prepare-world-directory-" + path, 30_000L,
                        "Cannot prepare world directory: " + path, failure);
            }
        }
    }

    private static void requireWorldData(Path worldFolder) throws IOException {
        Path level = worldFolder.resolve("level.dat");
        FileProof.requirePlainFile(level);
    }

    private static void repairOptionalSavedData(Path worldFolder, String levelName) {
        try {
            WorldStructureApi.repairOwnedSavedData(worldFolder);
            OperationFailures.recovered(levelName, "optional_saved_data");
        } catch (Exception repairFailure) {
            OperationFailures.report(levelName, "optional_saved_data", worldFolder, repairFailure);
        }
    }

    public static boolean syncWorldSetting(
            MirrorTarget target, WorldSettingsSnapshot settings, Setting setting) {
        Path worldFolder = target.folder();
        if (!Files.isRegularFile(worldFolder.resolve("level.dat")))
            return false;
        try (WorldWriteLock ignored = WorldWriteLock.acquire(worldFolder)) {
            target.requireCurrent();
            WorldStructureApi.syncWorldSetting(worldFolder, settings, setting);
            return true;
        } catch (Exception e) {
            WMLogger.warn("Could not sync mirror world setting=" + setting + " path=" + worldFolder, e);
            return false;
        }
    }

    static String resolvedLevelName(String levelName) {
        return levelName != null && !levelName.isEmpty() ? levelName : "Downloaded World";
    }

    /** Replaces a world-data file only after its compressed NBT round-trips without loss. */
    public static void writeVerifiedWorldData(Path file, CompoundTag tag) throws IOException {
        file = file.toAbsolutePath().normalize();
        try (var transaction =
                        new io.github.billstark001.worldmirror.util.FileTransaction(file.getParent())) {
            Path staged = transaction.stagingDirectory().resolve(file.getFileName());
            try (var raw = Files.newOutputStream(staged);
                    var compressed =
                            net.minecraft.world.level.chunk.storage.RegionFileVersion.VERSION_GZIP.wrap(raw);
                    var output = new java.io.DataOutputStream(new java.io.BufferedOutputStream(compressed))) {
                NbtIo.writeUnnamedTag(tag, output);
            }
            if (!tag.equals(NbtReadSupport.readWorldData(staged))) {
                throw new IOException("World-data staging verification failed: " + file);
            }
            transaction.commit(java.util.Map.of(file.getFileName(), staged));
        }
    }

    static CompoundTag createPlayerData(int x, int y, int z) {
        return createPlayerData(Level.OVERWORLD, x, y, z);
    }

    static CompoundTag createPlayerData(ResourceKey<Level> dimension, int x, int y, int z) {
        CompoundTag player = new CompoundTag();
        NbtUtils.addCurrentDataVersion(player);
        player.putString("Dimension", dimension.identifier().toString());

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x + 0.5D));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z + 0.5D));
        player.put("Pos", pos);

        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(0.0F));
        rotation.add(FloatTag.valueOf(0.0F));
        player.put("Rotation", rotation);

        ListTag motion = new ListTag();
        motion.add(DoubleTag.valueOf(0.0D));
        motion.add(DoubleTag.valueOf(0.0D));
        motion.add(DoubleTag.valueOf(0.0D));
        player.put("Motion", motion);

        player.putFloat("Health", 20.0F);
        player.putInt("playerGameType", 1);
        player.putBoolean("OnGround", true);
        player.putInt("Score", 0);
        player.putShort("Air", (short) 300);
        player.putShort("Fire", (short) -20);
        player.put("Inventory", new ListTag());
        player.put("EnderItems", new ListTag());
        return player;
    }

    private static UUID singleplayerUuid(String levelName) {
        return UUID.nameUUIDFromBytes(("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
    }

    private static CompoundTag createVoidNoiseGenerator(String biome, int minY, int height, int seaLevel,
            int horizontalSize, int verticalSize, boolean modern) {
        CompoundTag generator = new CompoundTag();
        CompoundTag settings = new CompoundTag();
        CompoundTag noise = new CompoundTag();
        noise.putInt("min_y", minY);
        noise.putInt("height", height);
        if (!modern) {
            noise.putInt("size_horizontal", horizontalSize);
            noise.putInt("size_vertical", verticalSize);
        }
        settings.put("noise", noise);
        settings.put("default_block", blockState("minecraft:air", modern));
        settings.put("default_fluid", blockState("minecraft:air", modern));
        settings.putInt("sea_level", seaLevel);
        settings.putBoolean("disable_mob_generation", true);
        if (!modern) {
            settings.putBoolean("aquifers_enabled", false);
            settings.putBoolean("ore_veins_enabled", false);
        }
        settings.putBoolean("legacy_random_source", false);
        settings.put("spawn_target", new ListTag());
        CompoundTag router = new CompoundTag();
        for (String field : modern ? MirrorWorldgenDefinition.MODERN_ZERO_NOISE_ROUTER_FIELDS
                                   : MirrorWorldgenDefinition.ZERO_NOISE_ROUTER_FIELDS) {
            router.putDouble(field, 0.0D);
        }
        router.putDouble("final_density", MirrorWorldgenDefinition.VOID_FINAL_DENSITY);
        settings.put("noise_router", router);
        CompoundTag rule = new CompoundTag();
        rule.putString("type", "minecraft:block");
        rule.put("result_state", blockState("minecraft:air", modern));
        settings.put(modern ? "material_rule" : "surface_rule", rule);
        generator.put("settings", settings);
        CompoundTag biomeSource = new CompoundTag();
        biomeSource.putString("type", "minecraft:fixed");
        biomeSource.putString("biome", biome);
        generator.put("biome_source", biomeSource);
        generator.putString("type", "minecraft:noise");
        return generator;
    }

    private static Tag blockState(String block, boolean modern) {
        if (modern)
            return StringTag.valueOf(block);
        CompoundTag state = new CompoundTag();
        state.putString("Name", block);
        return state;
    }
}
