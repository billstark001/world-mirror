package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.legacy.LegacyWorldClocks;
import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import java.io.IOException;
#if MC_VERSION >= 260300
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Minecraft 26.x translation for level.dat and the dimension-first save layout. */
final class WorldStructureApi {
    private static final WorldClockLookup WORLD_CLOCK_LOOKUP = new WorldClockLookup();
    private static final Map<ResourceKey<WorldClock>, Holder.Reference<WorldClock>> WORLD_CLOCKS = Map.of(
            WorldClocks.OVERWORLD,
            Holder.Reference.createStandAlone(WORLD_CLOCK_LOOKUP, WorldClocks.OVERWORLD), WorldClocks.THE_END,
            Holder.Reference.createStandAlone(WORLD_CLOCK_LOOKUP, WorldClocks.THE_END));
    private static final RegistryOps<Tag> WORLD_CLOCK_OPS =
            RegistryOps.create(NbtOps.INSTANCE, new RegistryOps.RegistryInfoLookup() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> Optional<HolderGetter<T>> lookup(
                        ResourceKey<? extends Registry<? extends T>> registryKey) {
                    if (!Registries.WORLD_CLOCK.equals(registryKey))
                        return Optional.empty();
                    return Optional.of((HolderGetter<T>) (HolderGetter<?>) WORLD_CLOCK_LOOKUP);
                }
            });

    private static final class WorldClockLookup implements HolderGetter<WorldClock>, HolderOwner<WorldClock> {
        @Override
        public Optional<Holder.Reference<WorldClock>> get(ResourceKey<WorldClock> resourceKey) {
            return Optional.ofNullable(WORLD_CLOCKS.get(resourceKey));
        }

        @Override
        public Optional<HolderSet.Named<WorldClock>> get(TagKey<WorldClock> tagKey) {
            return Optional.empty();
        }
    }

#elif MC_VERSION >= 260102
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Minecraft 26.x translation for level.dat and the dimension-first save layout. */
final class WorldStructureApi {
    private static final HolderOwner<WorldClock> WORLD_CLOCK_OWNER = new HolderOwner<>() {};
    private static final Map<ResourceKey<WorldClock>, Holder.Reference<WorldClock>> WORLD_CLOCKS = Map.of(
            WorldClocks.OVERWORLD,
            Holder.Reference.createStandAlone(WORLD_CLOCK_OWNER, WorldClocks.OVERWORLD), WorldClocks.THE_END,
            Holder.Reference.createStandAlone(WORLD_CLOCK_OWNER, WorldClocks.THE_END));
    private static final HolderGetter<WorldClock> WORLD_CLOCK_GETTER = new HolderGetter<>() {
        @Override
        public Optional<Holder.Reference<WorldClock>> get(ResourceKey<WorldClock> resourceKey) {
            return Optional.ofNullable(WORLD_CLOCKS.get(resourceKey));
        }

        @Override
        public Optional<HolderSet.Named<WorldClock>> get(TagKey<WorldClock> tagKey) {
            return Optional.empty();
        }
    };
    private static final RegistryOps.RegistryInfo<WorldClock> WORLD_CLOCK_REGISTRY =
            new RegistryOps.RegistryInfo<>(WORLD_CLOCK_OWNER, WORLD_CLOCK_GETTER, Lifecycle.stable());
    private static final RegistryOps<Tag> WORLD_CLOCK_OPS =
            RegistryOps.create(NbtOps.INSTANCE, new RegistryOps.RegistryInfoLookup() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> Optional<RegistryOps.RegistryInfo<T>> lookup(
                        ResourceKey<? extends Registry<? extends T>> registryKey) {
                    if (!Registries.WORLD_CLOCK.equals(registryKey))
                        return Optional.empty();
                    return Optional.of(
                            (RegistryOps.RegistryInfo<T>) (RegistryOps.RegistryInfo<?>) WORLD_CLOCK_REGISTRY);
                }
            });

#else
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.UUID;

/** Minecraft 1.21.11 translation for its native level.dat and save layout. */
final class WorldStructureApi {
#endif
    private WorldStructureApi() {}

    static Path worldgenDataPath(Path worldFolder) {
#if MC_VERSION >= 260102
        return worldFolder.resolve("data/minecraft/world_gen_settings.dat");
#else
        return worldFolder.resolve("level.dat");
#endif
    }

    static CompoundTag worldgenData(CompoundTag root) {
#if MC_VERSION >= 260102
        return root.get("data") instanceof CompoundTag settings ? settings : null;
#else
        return root.getCompoundOrEmpty("Data").get("WorldGenSettings") instanceof CompoundTag settings
                ? settings
                : null;
#endif
    }

    static String structureGenerationKey() {
#if MC_VERSION >= 260102
        return "generate_structures";
#else
        return "generate_features";
#endif
    }

    static boolean matchesOwnedVoidSettings(CompoundTag observed, CompoundTag expected) {
        CompoundTag normalized = observed.copy();
        for (var definition : MirrorWorldgenDefinition.DIMENSIONS) {
            var router = normalized.getCompoundOrEmpty("dimensions")
                                 .getCompoundOrEmpty(definition.dimensionType())
                                 .getCompoundOrEmpty("generator")
                                 .getCompoundOrEmpty("settings")
                                 .getCompoundOrEmpty("noise_router");
            // Vanilla serializes the constant density codec as floats on save.
            for (String field : router.keySet()) {
                if (router.get(field) instanceof FloatTag constant)
                    router.put(field, DoubleTag.valueOf(constant.value()));
            }
        }
        return normalized.equals(expected)
                || io.github.billstark001.worldmirror.legacy.LegacyWorldgen.matchesVoidStructureOptions(
                        normalized, expected, structureGenerationKey());
    }

    /** Writes native options and pack selection into a staged copy of the save. */
    static void replaceGeneration(Path worldFolder, CompoundTag settings, boolean mirrorPack)
            throws IOException {
        Path level = worldFolder.resolve("level.dat");
        CompoundTag levelRoot = NbtReadSupport.readWorldData(level);
        MirrorWorldgenCheck.requireSupportedVersion(levelRoot);
        if (!(levelRoot.get("Data") instanceof CompoundTag levelData))
            throw new IOException("Missing native level data");
        if (mirrorPack)
            enableDataPack(levelData);
        else
            removeMirrorDataPack(levelData);
#if MC_VERSION >= 260102
        Path generationFile = worldgenDataPath(worldFolder);
        CompoundTag generationRoot = NbtReadSupport.readWorldData(generationFile);
        MirrorWorldgenCheck.requireSupportedVersion(generationRoot);
        generationRoot.put("data", settings);
        NbtUtils.addCurrentDataVersion(generationRoot);
        WorldStructureCreator.writeVerifiedWorldData(generationFile, generationRoot);
#else
        levelData.put("WorldGenSettings", settings);
#endif
        WorldStructureCreator.writeVerifiedWorldData(level, levelRoot);
    }

    private static void removeMirrorDataPack(CompoundTag levelData) {
        CompoundTag packs = levelData.getCompoundOrEmpty("DataPacks");
        for (String list : new String[] {"Enabled", "Disabled"}) {
            ListTag entries = packs.getListOrEmpty(list);
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (MirrorWorldgenAssets.PACK_ID.equals(entries.getStringOr(i, "")))
                    entries.remove(i);
            }
            packs.put(list, entries);
        }
        levelData.put("DataPacks", packs);
    }

    static boolean hasMirrorDataPack(Path worldFolder) throws IOException {
        Path level = worldFolder.resolve("level.dat");
        FileProof.requirePlainFile(level);
        CompoundTag root = NbtReadSupport.readWorldData(level);
        MirrorWorldgenCheck.requireSupportedVersion(root);
        if (!(root.get("Data") instanceof CompoundTag data))
            throw new IOException("Missing native level data");
        CompoundTag packs = data.getCompoundOrEmpty("DataPacks");
        ListTag enabled = packs.getListOrEmpty("Enabled");
        for (int i = 0; i < enabled.size(); i++) {
            if (MirrorWorldgenAssets.PACK_ID.equals(enabled.getStringOr(i, "")))
                return true;
        }
        return false;
    }

    static int dataPackFormat() {
        return SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
    }

    static String[] worldSubdirectories() {
        return new String[] {
#if MC_VERSION >= 260102
                "dimensions/minecraft/overworld/region", "dimensions/minecraft/overworld/entities",
                "dimensions/minecraft/overworld/poi", "dimensions/minecraft/overworld/data/minecraft",
                "dimensions/minecraft/the_nether/region", "dimensions/minecraft/the_nether/entities",
                "dimensions/minecraft/the_nether/poi", "dimensions/minecraft/the_nether/data/minecraft",
                "dimensions/minecraft/the_end/region", "dimensions/minecraft/the_end/entities",
                "dimensions/minecraft/the_end/poi", "dimensions/minecraft/the_end/data/minecraft",
                "players/advancements", "players/data", "players/stats", "data/minecraft", "datapacks",
                "resourcepacks"
#else
                "region", "entities", "poi", "DIM-1/region", "DIM-1/entities", "DIM-1/poi", "DIM1/region",
                "DIM1/entities", "DIM1/poi", "playerdata", "advancements", "stats", "data", "datapacks",
                "resourcepacks"
#endif
        };
    }

    static Path playerDataPath(Path worldFolder, UUID playerId) {
#if MC_VERSION >= 260102
        return worldFolder.resolve("players/data/" + playerId + ".dat");
#else
        return worldFolder.resolve("playerdata/" + playerId + ".dat");
#endif
    }

    static void createInitialWorld(Path worldFolder, String levelName, UUID playerId,
            CompoundTag worldGenSettings, WorldSettingsSnapshot settings) throws Exception {
#if MC_VERSION >= 260102
        var minecraftData = worldFolder.resolve("data/minecraft");
        FileProof.requirePlainPath(worldFolder, worldgenDataPath(worldFolder));
        FileProof.requirePlainPath(worldFolder, minecraftData.resolve("game_rules.dat"));
        FileProof.requirePlainPath(worldFolder, minecraftData.resolve("weather.dat"));
        FileProof.requirePlainPath(worldFolder, minecraftData.resolve("world_clocks.dat"));
        PrimaryLevelData data = createLevelData(levelName, 0, 80, 0, settings);
        writeSavedData(worldgenDataPath(worldFolder), worldGenSettings);
        LevelStorageSource.writeGameRules(data, worldFolder, createGameRules(data.getDataConfiguration()));
        writeSavedData(minecraftData.resolve("weather.dat"), createWeatherData(settings));
        writeSavedData(minecraftData.resolve("world_clocks.dat"), createWorldClocksData(settings.dayTime()));
        writeLevelData(worldFolder.resolve("level.dat"), data, playerId);
#else
        writeLevelData(worldFolder.resolve("level.dat"),
                createLevelData(levelName, 0, 80, 0, worldGenSettings, settings));
#endif
    }

    static void writeSpawnedLevelData(Path worldFolder, String levelName, UUID playerId,
            ResourceKey<Level> spawnDimension, int spawnX, int spawnY, int spawnZ,
            CompoundTag worldGenSettings, WorldSettingsSnapshot settings) throws Exception {
#if MC_VERSION >= 260102
        PrimaryLevelData data = createLevelData(levelName, spawnX, spawnY, spawnZ, settings);
        data.setSpawn(
                LevelData.RespawnData.of(spawnDimension, new BlockPos(spawnX, spawnY, spawnZ), 0.0F, 0.0F));
        writeLevelData(worldFolder.resolve("level.dat"), data, playerId);
#else
        CompoundTag data = createLevelData(levelName, spawnX, spawnY, spawnZ, worldGenSettings, settings);
        data.put("spawn", createSpawnSettings(spawnDimension, spawnX, spawnY, spawnZ));
        data.put("Player", WorldStructureCreator.createPlayerData(spawnDimension, spawnX, spawnY, spawnZ));
        writeLevelData(worldFolder.resolve("level.dat"), data);
#endif
    }

    static void updateOwnedLevelData(Path worldFolder) throws Exception {
#if MC_VERSION >= 260102
        patchEnabledDataPack(worldFolder.resolve("level.dat"));
        Path file = worldgenDataPath(worldFolder);
        CompoundTag root = NbtReadSupport.readWorldData(file);
        root.put("data", MirrorWorldgenCheck.prepareUpdate(root));
        NbtUtils.addCurrentDataVersion(root);
        WorldStructureCreator.writeVerifiedWorldData(file, root);
    }

    static void repairOwnedSavedData(Path worldFolder) throws Exception {
        FileProof.requirePlainPath(worldFolder, worldFolder.resolve("data/minecraft/world_clocks.dat"));
        LegacyWorldClocks.repair(worldFolder, WorldStructureApi::roundTripWorldClocksData);
#else
        Path levelDat = worldFolder.resolve("level.dat");
        CompoundTag root = NbtReadSupport.readWorldData(levelDat);
        CompoundTag data = root.getCompoundOrEmpty("Data");
        data.put("WorldGenSettings", MirrorWorldgenCheck.prepareUpdate(root));
        enableDataPack(data);
        root.put("Data", data);
        WorldStructureCreator.writeVerifiedWorldData(levelDat, root);
    }

    static void repairOwnedSavedData(Path worldFolder) {
        // Minecraft 1.21.11 does not use data/minecraft/world_clocks.dat.
#endif
    }

    static WorldSettingsSnapshot captureWorldSettings(ClientLevel world) {
#if MC_VERSION >= 260300
        long dayTime = world.dimensionType()
                               .defaultClock()
                               .map(clock -> world.clockManager().getInstance(clock).totalTicks())
                               .orElse(world.getGameTime());
        return new WorldSettingsSnapshot(world.getGameTime(), dayTime,
#elif MC_VERSION >= 260102
        long dayTime = world.dimensionType()
                               .defaultClock()
                               .map(clock -> world.clockManager().getTotalTicks(clock))
                               .orElse(world.getGameTime());
        return new WorldSettingsSnapshot(world.getGameTime(), dayTime,
#else
        return new WorldSettingsSnapshot(world.getGameTime(), world.getDayTime(),
#endif
                world.getRainLevel(1.0F) > 0.0F, world.getThunderLevel(1.0F) > 0.0F,
                world.getDifficulty().getId());
    }

    static void syncWorldSetting(Path worldFolder, WorldSettingsSnapshot settings,
            WorldStructureCreator.Setting setting) throws Exception {
#if MC_VERSION >= 260102
        switch (setting) {
            case TIME -> {
                patchLevelData(worldFolder, data -> data.putLong("Time", settings.gameTime()));
                writeSavedDataAtomically(worldFolder.resolve("data/minecraft/world_clocks.dat"),
                        createWorldClocksData(settings.dayTime()));
            }
            case WEATHER ->
                writeSavedDataAtomically(
                        worldFolder.resolve("data/minecraft/weather.dat"), createWeatherData(settings));
            case DIFFICULTY ->
                patchLevelData(worldFolder, data -> {
                    CompoundTag difficultySettings = data.getCompoundOrEmpty("difficulty_settings");
                    difficultySettings.putString(
                            "difficulty", Difficulty.byId(settings.difficultyId()).getSerializedName());
                    data.put("difficulty_settings", difficultySettings);
                });
        }
    }

    private static PrimaryLevelData createLevelData(
            String levelName, int spawnX, int spawnY, int spawnZ, WorldSettingsSnapshot settings) {
        LevelSettings levelSettings = new LevelSettings(WorldStructureCreator.resolvedLevelName(levelName),
                GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.byId(settings.difficultyId()), false, false),
                true, WorldDataConfiguration.DEFAULT);
        PrimaryLevelData data = new PrimaryLevelData(
                levelSettings, PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable());
        data.setInitialized(true);
        data.setGameTime(settings.gameTime());
        data.setSpawn(
                LevelData.RespawnData.of(Level.OVERWORLD, new BlockPos(spawnX, spawnY, spawnZ), 0.0F, 0.0F));
        return data;
    }

    private static GameRules createGameRules(WorldDataConfiguration configuration) {
        GameRules rules = new GameRules(configuration.enabledFeatures());
        rules.set(GameRules.ADVANCE_TIME, false, null);
        rules.set(GameRules.SPAWN_MOBS, false, null);
        rules.set(GameRules.RANDOM_TICK_SPEED, 0, null);
        return rules;
    }

    private static void writeLevelData(Path file, PrimaryLevelData data, UUID playerId) throws Exception {
        CompoundTag root = new CompoundTag();
        CompoundTag levelData = data.createTag(playerId);
        enableDataPack(levelData);
        root.put("Data", levelData);
        WorldStructureCreator.writeVerifiedWorldData(file, root);
    }

    private static void patchEnabledDataPack(Path levelDat) throws Exception {
        CompoundTag root = NbtReadSupport.readWorldData(levelDat);
        MirrorWorldgenCheck.requireSupportedVersion(root);
        CompoundTag levelData = root.getCompoundOrEmpty("Data");
        enableDataPack(levelData);
        root.put("Data", levelData);
        WorldStructureCreator.writeVerifiedWorldData(levelDat, root);
    }

    private static void enableDataPack(CompoundTag levelData) {
        CompoundTag packs = levelData.getCompoundOrEmpty("DataPacks");
        ListTag enabled = packs.getListOrEmpty("Enabled");
        for (int i = 0; i < enabled.size(); i++) {
            if (MirrorWorldgenAssets.PACK_ID.equals(enabled.getStringOr(i, "")))
                return;
        }
        enabled.add(StringTag.valueOf(MirrorWorldgenAssets.PACK_ID));
        packs.put("Enabled", enabled);
        if (!packs.contains("Disabled"))
            packs.put("Disabled", new ListTag());
        levelData.put("DataPacks", packs);
    }

    private static void writeSavedData(Path file, CompoundTag data) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        WorldStructureCreator.writeVerifiedWorldData(file, root);
    }

    private static void writeSavedDataAtomically(Path file, CompoundTag data) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        WorldStructureCreator.writeVerifiedWorldData(file, root);
    }

    private static void patchLevelData(Path worldFolder, java.util.function.Consumer<CompoundTag> patch)
            throws Exception {
#else
#endif
        Path levelDat = worldFolder.resolve("level.dat");
        CompoundTag root = NbtReadSupport.readWorldData(levelDat);
        CompoundTag data = root.getCompoundOrEmpty("Data");
#if MC_VERSION >= 260102
        patch.accept(data);
#else
        applyWorldSetting(data, settings, setting);
#endif
        root.put("Data", data);
        WorldStructureCreator.writeVerifiedWorldData(levelDat, root);
    }

#if MC_VERSION >= 260102
    private static CompoundTag createWeatherData(WorldSettingsSnapshot settings) {
        CompoundTag weather = new CompoundTag();
        weather.putInt("clear_weather_time", settings.raining() ? 0 : 6_000);
        weather.putInt("rain_time", settings.raining() ? 6_000 : 0);
        weather.putInt("thunder_time", settings.thundering() ? 6_000 : 0);
        weather.putBoolean("raining", settings.raining());
        weather.putBoolean("thundering", settings.thundering());
        return weather;
    }

    private static CompoundTag createWorldClocksData(long dayTime) {
        CompoundTag states = new CompoundTag();
        states.put("minecraft:overworld", createClockState(dayTime));
        states.put("minecraft:the_end", createClockState(dayTime));
        return roundTripWorldClocksData(states);
    }

    private static CompoundTag createClockState(long totalTicks) {
        CompoundTag state = new CompoundTag();
        state.putLong("total_ticks", totalTicks);
        state.putFloat("partial_tick", 0.0F);
        state.putFloat("rate", 1.0F);
        state.putBoolean("paused", false);
        return state;
    }

    static CompoundTag roundTripWorldClocksData(CompoundTag data) {
        PackedClockStates decoded = PackedClockStates.CODEC.parse(WORLD_CLOCK_OPS, data).getOrThrow();
        Tag encoded = PackedClockStates.CODEC.encodeStart(WORLD_CLOCK_OPS, decoded).getOrThrow();
        if (!(encoded instanceof CompoundTag compound)) {
            throw new IllegalStateException("Packed clock codec did not produce a compound tag");
        }
        return compound;
#else
    private static CompoundTag createLevelData(String levelName, int spawnX, int spawnY, int spawnZ,
            CompoundTag worldGenSettings, WorldSettingsSnapshot settings) {
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        data.putString("LevelName", WorldStructureCreator.resolvedLevelName(levelName));
        data.putLong("RandomSeed", 0L);
        data.putInt("version", 19133);
        data.putBoolean("initialized", true);
        data.putInt("GameType", 1);
        data.putBoolean("allowCommands", true);
        data.putBoolean("hardcore", false);
        data.putByte("Difficulty", (byte) settings.difficultyId());
        data.putBoolean("DifficultyLocked", false);
        data.put("WorldGenSettings", worldGenSettings);
        data.put("DataPacks", createDataPacks());
        data.put("spawn", createSpawnSettings(spawnX, spawnY, spawnZ));
        data.putLong("Time", settings.gameTime());
        data.putLong("DayTime", settings.dayTime());
        applyWeather(data, settings);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.put("WorldBorder", createWorldBorder());
        data.put("game_rules", createGameRules());
        data.put("Player", WorldStructureCreator.createPlayerData(spawnX, spawnY, spawnZ));
        return data;
    }

    private static void applyWorldSetting(
            CompoundTag data, WorldSettingsSnapshot settings, WorldStructureCreator.Setting setting) {
        switch (setting) {
            case TIME -> {
                data.putLong("Time", settings.gameTime());
                data.putLong("DayTime", settings.dayTime());
            }
            case WEATHER -> applyWeather(data, settings);
            case DIFFICULTY -> data.putByte("Difficulty", (byte) settings.difficultyId());
        }
    }

    private static void applyWeather(CompoundTag data, WorldSettingsSnapshot settings) {
        data.putInt("clearWeatherTime", settings.raining() ? 0 : 6_000);
        data.putInt("rainTime", settings.raining() ? 6_000 : 0);
        data.putBoolean("raining", settings.raining());
        data.putInt("thunderTime", settings.thundering() ? 6_000 : 0);
        data.putBoolean("thundering", settings.thundering());
    }

    private static void writeLevelData(Path file, CompoundTag data) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        WorldStructureCreator.writeVerifiedWorldData(file, root);
    }

    private static CompoundTag createDataPacks() {
        CompoundTag packs = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf(MirrorWorldgenAssets.PACK_ID));
        packs.put("Enabled", enabled);
        packs.put("Disabled", new ListTag());
        return packs;
    }

    private static void enableDataPack(CompoundTag data) {
        CompoundTag packs = data.getCompoundOrEmpty("DataPacks");
        ListTag enabled = packs.getListOrEmpty("Enabled");
        for (int i = 0; i < enabled.size(); i++) {
            if (MirrorWorldgenAssets.PACK_ID.equals(enabled.getStringOr(i, ""))) {
                data.put("DataPacks", packs);
                return;
            }
        }
        enabled.add(StringTag.valueOf(MirrorWorldgenAssets.PACK_ID));
        packs.put("Enabled", enabled);
        data.put("DataPacks", packs);
    }

    private static CompoundTag createSpawnSettings(int x, int y, int z) {
        return createSpawnSettings(Level.OVERWORLD, x, y, z);
    }

    private static CompoundTag createSpawnSettings(ResourceKey<Level> dimension, int x, int y, int z) {
        CompoundTag spawn = new CompoundTag();
        spawn.putString("dimension", dimension.identifier().toString());
        spawn.putFloat("pitch", 0.0F);
        spawn.putFloat("yaw", 0.0F);
        spawn.put("pos", new IntArrayTag(new int[] {x, y, z}));
        return spawn;
    }

    private static CompoundTag createWorldBorder() {
        CompoundTag border = new CompoundTag();
        border.putDouble("BorderCenterX", 0.0D);
        border.putDouble("BorderCenterZ", 0.0D);
        border.putDouble("BorderSize", 5.9999968E7D);
        border.putDouble("BorderSizeLerpTarget", 5.9999968E7D);
        border.putLong("BorderSizeLerpTime", 0L);
        border.putDouble("BorderSafeZone", 5.0D);
        border.putDouble("BorderDamagePerBlock", 0.2D);
        border.putInt("BorderWarningBlocks", 5);
        border.putInt("BorderWarningTime", 15);
        return border;
    }

    private static CompoundTag createGameRules() {
        CompoundTag rules = new CompoundTag();
        rules.putString("doDaylightCycle", "false");
        rules.putString("doMobSpawning", "false");
        rules.putString("randomTickSpeed", "0");
        return rules;
#endif
    }
}
