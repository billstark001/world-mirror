package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkCoordinate;
import io.github.billstark001.worldmirror.format.MirrorFormat;
import io.github.billstark001.worldmirror.util.FileProof;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.world.level.ChunkPos;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.io.Closeable;
import java.io.IOException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

/** Minecraft and SQLite-driver adapter for the shared World Mirror chunk index. */
public class ChunkDatabase implements Closeable {
    private static final Object SQLITE_INIT_LOCK = new Object();
    private static final ReentrantReadWriteLock[] REPLACEMENT_LOCKS =
            IntStream.range(0, 64)
                    .mapToObj(ignored -> new ReentrantReadWriteLock(true))
                    .toArray(ReentrantReadWriteLock[] ::new);
    private static volatile boolean sqliteNativeDirectoryConfigured;
    private static volatile boolean sqliteDriverLoaded;

    private final Connection connection;
    private final ChunkIndexStore store;

    private ChunkDatabase(Connection connection, String sourceId) {
        this.connection = connection;
        this.store = new ChunkIndexStore(connection, sourceId);
    }

    /** Keeps cooperative status readers out of a database and sidecar file replacement. */
    public static ReplacementLease lockForReplacement(Path worldFolder) {
        return lock(replacementLock(worldFolder).writeLock());
    }

    static ReplacementLease lockForReplacement(Path worldFolder, Runnable checkpoint) throws IOException {
        Lock lock = replacementLock(worldFolder).writeLock();
        try {
            do {
                checkpoint.run();
            } while (!lock.tryLock(100, TimeUnit.MILLISECONDS));
            return new ReplacementLease(lock);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the chunk index", interrupted);
        }
    }

    private static ReplacementLease lock(Lock lock) {
        lock.lock();
        return new ReplacementLease(lock);
    }

    public static final class ReplacementLease implements AutoCloseable {
        private final Lock lock;

        private ReplacementLease(Lock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private static ReentrantReadWriteLock replacementLock(Path worldFolder) {
        return REPLACEMENT_LOCKS[Math.floorMod(
                worldFolder.toAbsolutePath().normalize().hashCode(), REPLACEMENT_LOCKS.length)];
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
        Connection connection = openWritableConnection(worldFolder);
        try {
            ChunkIndexStore.initialize(connection);
            return new ChunkDatabase(connection, sourceId);
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    /** Opens a checked save-local connection. The caller owns initialization and closure. */
    public static Connection openWritableConnection(Path worldFolder) throws SQLException {
        Path database = worldFolder.resolve(MirrorFormat.DATABASE_FILE);
        try {
            requireSafeFiles(worldFolder);
            Files.createDirectories(database.getParent());
        } catch (IOException failure) {
            throw new SQLException("Cannot open chunk index: " + database, failure);
        }
        return openConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    /** Bring an existing save index up to date. The caller holds the save's native write lease. */
    public static void upgradeExisting(Path worldFolder) throws SQLException, IOException {
        try (var ignored = lockForReplacement(worldFolder)) {
            requireSafeFiles(worldFolder);
            Path database = worldFolder.resolve(MirrorFormat.DATABASE_FILE);
            if (Files.notExists(database))
                return;
            var writable = new SQLiteConfig();
            writable.setOpenMode(SQLiteOpenMode.READWRITE);
            writable.resetOpenMode(SQLiteOpenMode.CREATE);
            try (Connection connection = openConnection(
                         "jdbc:sqlite:" + database.toAbsolutePath(), writable.toProperties())) {
                if (!ChunkIndexStore.hasRegisteredSource(connection, ChunkIndexStore.REPLAY_IMPORT)
                        || !ChunkIndexStore.hasRegisteredSource(connection, ChunkIndexStore.SEED_GENERATED)) {
                    ChunkIndexStore.initialize(connection);
                    WMLogger.info("Mirror chunk index source registrations upgraded world=" + worldFolder);
                }
            }
        }
    }

    /** Copy one consistent SQLite snapshot into an empty staging path, including committed WAL rows. */
    public static boolean snapshot(Path sourceWorld, Path stagingWorld) throws IOException, SQLException {
        requireSafeFiles(sourceWorld);
        Path source = sourceWorld.resolve(MirrorFormat.DATABASE_FILE).toAbsolutePath().normalize();
        if (Files.notExists(source))
            return false;
        requireSafeFiles(stagingWorld);
        Path target = stagingWorld.resolve(MirrorFormat.DATABASE_FILE).toAbsolutePath().normalize();
        if (!Files.notExists(target))
            throw new IOException("Chunk index staging destination already exists: " + target);
        Files.createDirectories(target.getParent());
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        try (Connection connection = openConnection("jdbc:sqlite:" + source, readOnly.toProperties());
                var statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, target.toString());
            statement.execute();
        }
        return true;
    }

    /** SQLite works in a short, private path before its closed copy joins the save transaction. */
    static boolean stageWithoutSeedGenerated(Path world, Path staged, String sourceId,
            Map<String, Set<ChunkPos>> removed) throws IOException, SQLException {
        if (removed.isEmpty())
            return false;
        Path workspace = Files.createTempDirectory("wm-index-");
        try {
            if (!snapshot(world, workspace))
                return false;
            try (var db = open(workspace, sourceId)) {
                for (var entry : removed.entrySet()) db.removeSeedGenerated(entry.getKey(), entry.getValue());
            }
            Path copy = workspace.resolve(MirrorFormat.DATABASE_FILE);
            if (Files.exists(copy.resolveSibling(copy.getFileName() + "-wal"))
                    || Files.exists(copy.resolveSibling(copy.getFileName() + "-journal")))
                throw new IOException("Staged SQLite index did not checkpoint");
            Path result = staged.resolve(MirrorFormat.DATABASE_FILE);
            Files.createDirectories(result.getParent());
            FileProof.copyVerified(copy, result);
            return true;
        } finally {
            Path copy = workspace.resolve(MirrorFormat.DATABASE_FILE);
            for (String suffix : List.of("-journal", "-wal", "-shm", ""))
                Files.deleteIfExists(copy.resolveSibling(copy.getFileName() + suffix));
            Files.deleteIfExists(copy.getParent());
            Files.delete(workspace);
        }
    }

    private static void requireSafeFiles(Path worldFolder) throws IOException {
        Path database = worldFolder.resolve(MirrorFormat.DATABASE_FILE);
        FileProof.requirePlainDirectory(worldFolder);
        FileProof.requirePlainDirectory(database.getParent());
        for (String suffix : List.of("", "-wal", "-shm", "-journal")) {
            FileProof.requirePlainFileIfPresent(database.resolveSibling(database.getFileName() + suffix));
        }
    }

    /** Unavailable durability/priority evidence must defer writes, not authorize them. */
    public boolean shouldSkipUpdate(String dimension, int x, int z, String newUpdateSource, long newTimestamp)
            throws SQLException {
        return store.shouldSkipUpdate(dimension, x, z, newUpdateSource, newTimestamp);
    }

    public boolean shouldSkipUpdate(
            String dimension, ChunkPos chunk, String newUpdateSource, long newTimestamp) throws SQLException {
        ChunkCoordinate position = coordinate(chunk);
        return shouldSkipUpdate(dimension, position.x(), position.z(), newUpdateSource, newTimestamp);
    }

    /** A database row is recorded only after the region entry has been verified. */
    public boolean recordUpdates(String dimension, Map<ChunkPos, Long> timestamps, String updateSource) {
        if (timestamps.isEmpty())
            return true;
        Map<ChunkCoordinate, Long> coordinates = new HashMap<>();
        timestamps.forEach((chunk, time) -> coordinates.put(coordinate(chunk), time));
        try {
            store.recordUpdates(dimension, coordinates, updateSource);
            return true;
        } catch (SQLException e) {
            WMLogger.warn(
                    "Chunk durability commit failed dimension=" + dimension + " updates=" + timestamps.size(),
                    e);
            return false;
        }
    }

    public boolean removeUnreadableUpdates(String dimension, Set<ChunkPos> chunks) {
        if (chunks.isEmpty())
            return true;
        try {
            store.removeUnreadableUpdates(dimension, coordinates(chunks));
            return true;
        } catch (SQLException e) {
            WMLogger.warn("Unreadable chunk durability cleanup failed dimension=" + dimension
                            + " chunks=" + chunks.size(),
                    e);
            return false;
        }
    }

    public void recordSeedGeneratedIfMissing(String dimension, Set<ChunkPos> chunks) throws SQLException {
        store.recordSeedGeneratedIfMissing(dimension, coordinates(chunks));
    }

    public void removeSeedGenerated(String dimension, Set<ChunkPos> chunks) throws SQLException {
        store.removeSeedGenerated(dimension, coordinates(chunks));
    }

    private static Set<ChunkCoordinate> coordinates(Set<ChunkPos> chunks) {
        return chunks.stream().map(ChunkDatabase::coordinate).collect(Collectors.toSet());
    }

    private static ChunkCoordinate coordinate(ChunkPos chunk) {
        // Region and local accessors are stable across the ChunkPos class/record
        // transition, and unlike block coordinates retain all 32 coordinate bits.
        return new ChunkCoordinate(chunk.getRegionX() * ChunkPos.REGION_SIZE + chunk.getRegionLocalX(),
                chunk.getRegionZ() * ChunkPos.REGION_SIZE + chunk.getRegionLocalZ());
    }

    public record ChunkRecord(int x, int z, long updateTime, String updateSource) {}

    public ChunkRecord queryChunk(String dimension, ChunkPos chunk) throws SQLException {
        var pos = coordinate(chunk);
        var row = store.queryChunk(dimension, pos.x(), pos.z());
        return row == null ? null : toChunkRecord(row);
    }

    public List<ChunkRecord> queryRegion(String dimension, int regionX, int regionZ) throws SQLException {
        return toChunkRecords(store.queryRegion(dimension, regionX, regionZ));
    }

    public List<ChunkRecord> queryAll(String dimension) throws SQLException {
        return toChunkRecords(store.queryAll(dimension));
    }

    private static List<ChunkRecord> toChunkRecords(List<ChunkIndexStore.ChunkRecord> records) {
        return records.stream().map(ChunkDatabase::toChunkRecord).toList();
    }

    private static ChunkRecord toChunkRecord(ChunkIndexStore.ChunkRecord record) {
        return new ChunkRecord(record.x(), record.z(), record.updateTime(), record.updateSource());
    }

    /** Missing indexes are empty; unreadable indexes have no trustworthy empty result. */
    public static List<ChunkRecord> queryAllReadOnly(Path worldFolder, String sourceId, String dimension)
            throws SQLException {
        return queryReadOnly(worldFolder, sourceId, dimension, false);
    }

    public static List<ChunkRecord> queryVisibleReadOnly(Path worldFolder, String sourceId, String dimension)
            throws SQLException {
        return queryReadOnly(worldFolder, sourceId, dimension, true);
    }

    private static List<ChunkRecord> queryReadOnly(
            Path worldFolder, String sourceId, String dimension, boolean visibleOnly) throws SQLException {
        return withReadOnlyIndex(worldFolder, sourceId, List.of(),
                index
                -> toChunkRecords(visibleOnly ? index.queryVisible(dimension) : index.queryAll(dimension)));
    }

    /** Returns -1 when the durable count cannot be read. */
    public static int countAllReadOnly(Path worldFolder, String sourceId) {
        return countReadOnly(worldFolder, sourceId, false);
    }

    public static int countVisibleReadOnly(Path worldFolder, String sourceId) {
        return countReadOnly(worldFolder, sourceId, true);
    }

    private static int countReadOnly(Path worldFolder, String sourceId, boolean visibleOnly) {
        try {
            return withReadOnlyIndex(
                    worldFolder, sourceId, 0, index -> visibleOnly ? index.countVisible() : index.countAll());
        } catch (SQLException e) {
            WMLogger.warnRateLimited(
                    "db-count-read-only", 30_000L, "Read-only chunk count failed world=" + worldFolder, e);
            return -1;
        }
    }

    @FunctionalInterface
    private interface Read<T> {
        T query(ChunkIndexStore index) throws SQLException;
    }

    private static <T> T withReadOnlyIndex(Path worldFolder, String sourceId, T missing, Read<T> read)
            throws SQLException {
        return withReadOnlyConnection(
                worldFolder, missing, connection -> read.query(new ChunkIndexStore(connection, sourceId)));
    }

    /** Returns the sole saved index namespace, or null if there are no index rows. */
    static String readOrphanNamespace(Path worldFolder) throws SQLException {
        return withReadOnlyConnection(worldFolder, null, connection -> {
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT DISTINCT source FROM chunks LIMIT 2")) {
                if (!rows.next())
                    return null;
                String source = rows.getString(1);
                if (source == null || source.isBlank() || source.chars().anyMatch(Character::isISOControl)
                        || rows.next())
                    throw new SQLException("Cannot identify one saved chunk index namespace");
                return source;
            }
        });
    }

    @FunctionalInterface
    private interface ReadConnection<T> {
        T query(Connection connection) throws SQLException;
    }

    private static <T> T withReadOnlyConnection(Path worldFolder, T missing, ReadConnection<T> read)
            throws SQLException {
        try (var ignored = lock(replacementLock(worldFolder).readLock())) {
            Path database = worldFolder.resolve(MirrorFormat.DATABASE_FILE);
            try {
                requireSafeFiles(worldFolder);
            } catch (IOException failure) {
                throw new SQLException("Cannot open chunk index: " + database, failure);
            }
            if (Files.notExists(database))
                return missing;
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            try (Connection connection = openConnection(
                         "jdbc:sqlite:" + database.toAbsolutePath(), config.toProperties())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA query_only=TRUE");
                }
                return read.query(connection);
            }
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
        if (sqliteDriverLoaded)
            return;
        try {
            Class.forName("org.sqlite.JDBC");
            sqliteDriverLoaded = true;
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver is not available", e);
        }
    }

    private static void configureSqliteNativeDirectoryLocked() {
        if (sqliteNativeDirectoryConfigured)
            return;
        if (System.getProperty("org.sqlite.tmpdir") == null) {
            Path nativeDir = Paths.get(System.getProperty("java.io.tmpdir"), "worldmirror-sqlite-native",
                    Long.toString(ProcessHandle.current().pid()));
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
