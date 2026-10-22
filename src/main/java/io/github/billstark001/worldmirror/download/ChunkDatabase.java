package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkCoordinate;
import io.github.billstark001.worldmirror.format.MirrorFormat;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.world.level.ChunkPos;
import org.sqlite.SQLiteConfig;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Minecraft and SQLite-driver adapter for the shared World Mirror chunk index. */
public class ChunkDatabase implements Closeable {
    private static final Object SQLITE_INIT_LOCK = new Object();
    private static volatile boolean sqliteNativeDirectoryConfigured;
    private static volatile boolean sqliteDriverLoaded;

    private final Connection connection;
    private final ChunkIndexStore store;

    private ChunkDatabase(Connection connection, String sourceId) {
        this.connection = connection;
        this.store = new ChunkIndexStore(connection, sourceId);
    }

    public static void configureSqliteNativeDirectory() {
        synchronized (SQLITE_INIT_LOCK) {
            configureSqliteNativeDirectoryLocked();
        }
    }

    /** Opens an index and seeds the shared contract's default update sources. */
    public static ChunkDatabase open(Path worldFolder, String sourceId) throws SQLException {
        Objects.requireNonNull(worldFolder, "worldFolder");
        Objects.requireNonNull(sourceId, "sourceId");
        Path database = worldFolder.resolve(MirrorFormat.DATABASE_FILE);
        try {
            Files.createDirectories(database.getParent());
        } catch (Exception e) {
            throw new SQLException("Cannot create data directory: " + e.getMessage(), e);
        }
        Connection connection = openConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try {
            ChunkIndexStore.initialize(connection);
            return new ChunkDatabase(connection, sourceId);
        } catch (SQLException failure) {
            try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    /** Preserve the mod's fail-open behavior on a failed chunk-row lookup. */
    public boolean shouldSkipUpdate(String dimension, int x, int y,
                                    String newUpdateSource, long newTimestamp) {
        try {
            return store.shouldSkipUpdate(dimension, x, y, newUpdateSource, newTimestamp);
        } catch (SQLException e) {
            WMLogger.warnRateLimited("db-dirty-check", 30_000L,
                    "Chunk durability lookup failed; allowing writes until recovery", e);
            return false;
        }
    }

    public boolean shouldSkipUpdate(String dimension, ChunkPos chunk,
                                    String newUpdateSource, long newTimestamp) {
        ChunkCoordinate position = coordinate(chunk);
        return shouldSkipUpdate(dimension, position.x(), position.z(), newUpdateSource, newTimestamp);
    }

    /** A database row is recorded only after the region entry has been verified. */
    public boolean recordUpdates(String dimension, Map<ChunkPos, Long> timestamps,
                                 String updateSource) {
        if (timestamps.isEmpty()) return true;
        Map<ChunkCoordinate, Long> coordinates = new HashMap<>();
        timestamps.forEach((chunk, time) -> coordinates.put(coordinate(chunk), time));
        try {
            store.recordUpdates(dimension, coordinates, updateSource);
            return true;
        } catch (SQLException e) {
            WMLogger.warn("Chunk durability commit failed dimension=" + dimension
                    + " updates=" + timestamps.size(), e);
            return false;
        }
    }

    public boolean removeUnreadableUpdates(String dimension, Set<ChunkPos> chunks) {
        if (chunks.isEmpty()) return true;
        Set<ChunkCoordinate> coordinates = chunks.stream()
                .map(ChunkDatabase::coordinate).collect(Collectors.toSet());
        try {
            store.removeUnreadableUpdates(dimension, coordinates);
            return true;
        } catch (SQLException e) {
            WMLogger.warn("Unreadable chunk durability cleanup failed dimension="
                    + dimension + " chunks=" + chunks.size(), e);
            return false;
        }
    }

    /** Returns true only after the legacy timestamps have committed to SQLite. */
    public boolean migrateFromChunkUpdateTimes(Map<String, Long> chunkUpdateTimes) {
        try {
            int migrated = store.migrateLegacyTimes(chunkUpdateTimes);
            if (migrated > 0) {
                WMLogger.debug("Migrated " + migrated + " chunk timestamps from JSON to SQLite.");
            }
            return true;
        } catch (SQLException e) {
            WMLogger.warn("Legacy chunk timestamp migration failed", e);
            return false;
        }
    }

    private static ChunkCoordinate coordinate(ChunkPos chunk) {
        // Region and local accessors are stable across the ChunkPos class/record
        // transition, and unlike block coordinates retain all 32 coordinate bits.
        return new ChunkCoordinate(
                chunk.getRegionX() * ChunkPos.REGION_SIZE + chunk.getRegionLocalX(),
                chunk.getRegionZ() * ChunkPos.REGION_SIZE + chunk.getRegionLocalZ());
    }

    public record ChunkRecord(int x, int z, long updateTime, String updateSource) { }

    public List<ChunkRecord> queryAll(String dimension) {
        try {
            return store.queryAll(dimension).stream()
                    .map(record -> new ChunkRecord(record.x(), record.z(),
                            record.updateTime(), record.updateSource()))
                    .toList();
        } catch (SQLException e) {
            WMLogger.warnRateLimited("db-query-all", 30_000L,
                    "Chunk database query failed dimension=" + dimension, e);
            return List.of();
        }
    }

    public static List<ChunkRecord> queryAllReadOnly(
            Path worldFolder, String sourceId, String dimension) {
        Path database = worldFolder.resolve(MirrorFormat.DATABASE_FILE);
        if (!Files.isRegularFile(database)) return List.of();
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection connection = openConnection(
                "jdbc:sqlite:" + database.toAbsolutePath(), config.toProperties())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=TRUE");
            }
            return new ChunkDatabase(connection, sourceId).queryAll(dimension);
        } catch (SQLException e) {
            WMLogger.warnRateLimited("db-query-read-only", 30_000L,
                    "Read-only chunk database query failed dimension=" + dimension, e);
            return List.of();
        }
    }

    /** Returns -1 when the durable count cannot be read. */
    public static int countAllReadOnly(Path worldFolder, String sourceId) {
        Path database = worldFolder.resolve(MirrorFormat.DATABASE_FILE);
        if (!Files.isRegularFile(database)) return 0;
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection connection = openConnection(
                "jdbc:sqlite:" + database.toAbsolutePath(), config.toProperties())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=TRUE");
            }
            return new ChunkIndexStore(connection, sourceId).countAll();
        } catch (SQLException e) {
            WMLogger.warnRateLimited("db-count-read-only", 30_000L,
                    "Read-only chunk count failed world=" + worldFolder, e);
            return -1;
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            WMLogger.warn("Chunk database close failed", e);
        }
    }

    private static Connection openConnection(String url) throws SQLException {
        synchronized (SQLITE_INIT_LOCK) {
            ensureSqliteDriverReadyLocked();
            return DriverManager.getConnection(url);
        }
    }

    private static Connection openConnection(String url, Properties properties) throws SQLException {
        synchronized (SQLITE_INIT_LOCK) {
            ensureSqliteDriverReadyLocked();
            return DriverManager.getConnection(url, properties);
        }
    }

    private static void ensureSqliteDriverReadyLocked() throws SQLException {
        configureSqliteNativeDirectoryLocked();
        if (sqliteDriverLoaded) return;
        try {
            Class.forName("org.sqlite.JDBC");
            sqliteDriverLoaded = true;
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver is not available", e);
        }
    }

    private static void configureSqliteNativeDirectoryLocked() {
        if (sqliteNativeDirectoryConfigured) return;
        if (System.getProperty("org.sqlite.tmpdir") == null) {
            Path nativeDir = Paths.get(System.getProperty("java.io.tmpdir"),
                    "worldmirror-sqlite-native", Long.toString(ProcessHandle.current().pid()));
            try {
                Files.createDirectories(nativeDir);
                System.setProperty("org.sqlite.tmpdir", nativeDir.toAbsolutePath().toString());
                WMLogger.debug("SQLite native temp dir: " + nativeDir.toAbsolutePath());
            } catch (Exception e) {
                WMLogger.warn("SQLite native temp directory setup failed path=" + nativeDir, e);
            }
        }
        sqliteNativeDirectoryConfigured = true;
    }
}
