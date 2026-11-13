package io.github.billstark001.worldmirror.ui;

import com.mojang.serialization.Dynamic;
import io.github.billstark001.worldmirror.download.WorldGraphConfig;
import io.github.billstark001.worldmirror.io.NbtReadSupport;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.util.PngInfo;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.level.storage.LevelVersion;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Loads picker details from the same save and server records that vanilla displays. */
final class SelectionRowData {
    private static final int ICON_SIZE = 64;
    private static final int MAX_ICON_BYTES = 256 * 1024;
    private static final int MAX_SERVER_LIST_BYTES = 16 * 1024 * 1024;
    private static final int MC_REGION_STORAGE_VERSION = 19132;

    record World(Component name, Component pathAndDate, Component info) {}
    record Server(Component name, byte[] icon) {}

    private SelectionRowData() {}

    static World world(Path game, Path folder, String displayPath) {
        Path file = new LevelStorageSource.LevelDirectory(folder).dataFile();
        try {
            FileProof.requirePlainPath(game, file);
            FileProof.requirePlainFile(file);
            var level = NbtReadSupport.readWorldData(file)
                                .getCompound(LevelStorageSource.TAG_DATA)
                                .orElseThrow(() -> new IOException("Missing world data"));
            // A picker can list the world currently in use. Read its summary without checking or taking its save lock.
            var data = DataFixTypes.LEVEL_SUMMARY.updateToCurrentVersion(DataFixers.getDataFixer(),
                    new Dynamic<>(NbtOps.INSTANCE, level), NbtUtils.getDataVersion(level));
            var configuration = LevelStorageSource.readDataConfig(data);
            var version = LevelVersion.parse(data);
            var settings = LevelSettings.parse(data, configuration);
            var summary = new LevelSummary(settings, version, folder.getFileName().toString(),
                    version.levelDataVersion() == MC_REGION_STORAGE_VERSION,
#if MC_VERSION >= 260102
                    false,
#endif
                    false, FeatureFlags.isExperimental(configuration.enabledFeatures()), null);
            return new World(Component.literal(summary.getLevelName()),
                    Component.literal(pathAndDate(displayPath, summary.getLastPlayed())), summary.getInfo());
        } catch (NoSuchFileException absent) {
            return null; // Empty destination reservations have no vanilla summary.
        } catch (IOException | RuntimeException failure) {
            WMLogger.warnRateLimited("picker.world-summary", 30_000,
                    "Cannot read world picker summary: " + displayPath, failure);
            return null;
        }
    }

    private static String pathAndDate(String path, long lastPlayed) {
        if (lastPlayed < 0)
            return path;
        var date = Instant.ofEpochMilli(lastPlayed).atZone(ZoneId.systemDefault());
        return path + " (" + WorldSelectionList.DATE_FORMAT.format(date) + ")";
    }

    static byte[] worldIcon(Path game, Path folder) {
        Path icon = new LevelStorageSource.LevelDirectory(folder).iconFile();
        try {
            FileProof.requirePlainPath(game, icon);
            FileProof.requirePlainFile(icon);
            try (var input = Files.newInputStream(icon)) {
                return readableIcon(input.readNBytes(MAX_ICON_BYTES + 1));
            }
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException | RuntimeException failure) {
            WMLogger.warnRateLimited(
                    "picker.world-icon", 30_000, "Cannot load world picker icon: " + icon, failure);
            return null;
        }
    }

    static Map<String, Server> servers(Path game, Set<String> addresses) {
        var servers = new HashMap<String, Server>();
        if (addresses.isEmpty())
            return servers;
        Path file = game.resolve("servers.dat");
        try {
            FileProof.requirePlainPath(game, file);
            if (FileProof.requirePlainFile(file).size() > MAX_SERVER_LIST_BYTES)
                throw new IOException("Server list exceeds picker input limit");
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                var data = NbtIo.read(input, NbtAccounter.create(MAX_SERVER_LIST_BYTES));
                data.getListOrEmpty("servers").compoundStream().forEach(
                        record -> addServer(servers, addresses, record));
            }
        } catch (NoSuchFileException absent) {
            return servers;
        } catch (IOException | RuntimeException failure) {
            WMLogger.warnRateLimited(
                    "picker.server-list", 30_000, "Cannot read saved server picker details", failure);
        }
        return servers;
    }

    private static void addServer(Map<String, Server> servers, Set<String> addresses, CompoundTag record) {
        try {
            if (record.getBooleanOr("hidden", false))
                return;
            String address = WorldGraphConfig.normalizeEndpoint(record.getStringOr("ip", ""));
            if (!addresses.contains(address) || servers.containsKey(address))
                return;
            var server = ServerData.read(record);
            byte[] icon = server.getIconBytes();
            if (icon != null) {
                try {
                    icon = readableIcon(icon);
                } catch (IOException invalid) {
                    WMLogger.warnRateLimited(
                            "picker.server-icon", 30_000, "Cannot load saved server picker icon", invalid);
                    icon = null;
                }
            }
            servers.put(address, new Server(Component.literal(server.name), icon));
        } catch (IllegalArgumentException invalid) {
            WMLogger.debug("Skipping an invalid saved server record in the world picker");
        }
    }

    private static byte[] readableIcon(byte[] bytes) throws IOException {
        if (bytes.length > MAX_ICON_BYTES)
            throw new IOException("Icon exceeds picker input limit");
        var png = PngInfo.fromBytes(bytes);
        if (png.width() != ICON_SIZE || png.height() != ICON_SIZE)
            throw new IOException("World and server icons must be 64×64 pixels");
        return bytes;
    }
}
