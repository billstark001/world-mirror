package io.github.billstark001.worldmirror.io;

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

/** Minecraft 1.21.11 translation for legacy level.dat and save layout. */
final class WorldStructureApi {
    private WorldStructureApi() { }

    static Path worldgenDataPath(Path worldFolder) {
        return worldFolder.resolve("level.dat");
    }

    static CompoundTag worldgenData(CompoundTag root) {
        return root.getCompoundOrEmpty("Data").get("WorldGenSettings") instanceof CompoundTag settings
                ? settings : null;
    }

    static int dataPackFormat() {
        return SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
    }

    static String[] worldSubdirectories() {
        return new String[] {
                "region", "entities", "poi",
                "DIM-1/region", "DIM-1/entities", "DIM-1/poi",
                "DIM1/region", "DIM1/entities", "DIM1/poi",
                "playerdata", "advancements", "stats", "data",
                "datapacks", "resourcepacks"
        };
    }

    static Path playerDataPath(Path worldFolder, UUID playerId) {
        return worldFolder.resolve("playerdata/" + playerId + ".dat");
    }

    static void createInitialWorld(Path worldFolder, String levelName, UUID playerId,
                                   CompoundTag worldGenSettings,
                                   WorldSettingsSnapshot settings) throws Exception {
        writeLevelData(worldFolder.resolve("level.dat"),
                createLevelData(levelName, 0, 80, 0, worldGenSettings, settings));
    }

    static void writeSpawnedLevelData(Path worldFolder, String levelName, UUID playerId,
                                      ResourceKey<Level> spawnDimension,
                                      int spawnX, int spawnY, int spawnZ,
                                      CompoundTag worldGenSettings,
                                      WorldSettingsSnapshot settings) throws Exception {
        CompoundTag data = createLevelData(levelName, spawnX, spawnY, spawnZ, worldGenSettings, settings);
        data.put("spawn", createSpawnSettings(spawnDimension, spawnX, spawnY, spawnZ));
        data.put("Player", WorldStructureCreator.createPlayerData(spawnDimension, spawnX, spawnY, spawnZ));
        writeLevelData(worldFolder.resolve("level.dat"), data);
    }

    static void updateOwnedLevelData(Path worldFolder) throws Exception {
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
    }

    static WorldSettingsSnapshot captureWorldSettings(ClientLevel world) {
        return new WorldSettingsSnapshot(
                world.getGameTime(), world.getDayTime(),
                world.getRainLevel(1.0F) > 0.0F,
                world.getThunderLevel(1.0F) > 0.0F,
                world.getDifficulty().getId());
    }

    static void syncWorldSetting(Path worldFolder, WorldSettingsSnapshot settings,
                                 WorldStructureCreator.Setting setting) throws Exception {
        Path levelDat = worldFolder.resolve("level.dat");
        CompoundTag root = NbtReadSupport.readWorldData(levelDat);
        CompoundTag data = root.getCompoundOrEmpty("Data");
        applyWorldSetting(data, settings, setting);
        root.put("Data", data);
        WorldStructureCreator.writeVerifiedWorldData(levelDat, root);
    }

    private static CompoundTag createLevelData(String levelName, int spawnX, int spawnY,
                                               int spawnZ, CompoundTag worldGenSettings,
                                               WorldSettingsSnapshot settings) {
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

    private static void applyWorldSetting(CompoundTag data, WorldSettingsSnapshot settings,
                                          WorldStructureCreator.Setting setting) {
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
    }
}
