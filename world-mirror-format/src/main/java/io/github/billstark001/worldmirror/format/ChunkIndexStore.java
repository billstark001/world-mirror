package io.github.billstark001.worldmirror.format;

import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.IndexStatus;
import io.github.billstark001.worldmirror.format.internal.JdbcTransaction;
import io.github.billstark001.worldmirror.legacy.LegacyChunkTimes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * JDBC operations for the World Mirror chunk durability index. The host owns the
 * connection and must serialize its use. Mutations require auto-commit mode and
 * roll back on checked and unchecked failures. If rollback or connection-state
 * restoration fails, discard the connection rather than reusing it.
 */
public final class ChunkIndexStore {
    public static final String REPLAY_IMPORT = "replay_import";
    public static final String SEED_GENERATED = "seed_generated";
    public static final int SEED_GENERATED_PRIORITY = 100;
    public static final long SEED_GENERATED_TIME = 0L;

    private static final Map<String, Integer> DEFAULT_SOURCES =
            Map.of("player", 0, "world_mirror", 10, "game_events", 20, "map_hp", 30, "map_lp", 50,
                    REPLAY_IMPORT, 10, SEED_GENERATED, SEED_GENERATED_PRIORITY);
    private static final String UPSERT = "INSERT INTO chunks "
            + "(source, dimension, x, y, update_time, update_source) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT(source, dimension, x, y) DO UPDATE SET "
            + "update_time=excluded.update_time, update_source=excluded.update_source "
            + "WHERE excluded.update_time >= chunks.update_time OR ?";

    private static final String SELECT_PRIORITIES = "SELECT update_source, "
            + "CASE WHEN (apply_to_source IS NULL OR apply_to_source=?) "
            + "AND (apply_to_dimension IS NULL OR apply_to_dimension=?) THEN priority END "
            + "FROM update_sources";
    private static final String SELECT_CHUNKS = "SELECT x, y, update_time, update_source FROM chunks "
            + "WHERE source=? AND dimension=?";
    private static final int REGION_SIZE = 32;
    private static final String SELECT_REGION = SELECT_CHUNKS + " AND x IN ("
            + String.join(", ", Collections.nCopies(REGION_SIZE, "?")) + ") AND y BETWEEN ? AND ?";

    private final Connection connection;
    private final String sourceId;

    public record ChunkCoordinate(int x, int z) {}
    public record ChunkRecord(int x, int z, long updateTime, String updateSource) {}

    public ChunkIndexStore(Connection connection, String sourceId) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    }

    /** Initialize or upgrade a writable index without changing previously registered source rules. */
    public static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
        }
        JdbcTransaction.run(connection, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS chunks ("
                        + "source TEXT NOT NULL, dimension TEXT NOT NULL, x INTEGER NOT NULL, "
                        + "y INTEGER NOT NULL, update_time INTEGER NOT NULL DEFAULT 0, "
                        + "update_source TEXT NOT NULL DEFAULT 'world_mirror', "
                        + "PRIMARY KEY(source, dimension, x, y))");
                statement.execute("CREATE TABLE IF NOT EXISTS update_sources ("
                        + "update_source TEXT PRIMARY KEY, priority INTEGER NOT NULL, "
                        + "apply_to_source TEXT, apply_to_dimension TEXT)");
            }
            registerDefaultSources(connection);
        });
    }

    private static void registerDefaultSources(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO update_sources "
                     + "(update_source, priority, apply_to_source, apply_to_dimension) "
                     + "VALUES (?, ?, NULL, NULL)")) {
            for (var entry : DEFAULT_SOURCES.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setInt(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public boolean shouldSkipUpdate(String dimension, int x, int z, String incomingSource, long incomingTime)
            throws SQLException {
        var incoming = new ChunkUpdate(dimension, new ChunkCoordinate(x, z), incomingTime, incomingSource);
        ChunkRecord existing = queryChunk(dimension, x, z);
        if (existing == null)
            return false;
        if (incomingTime <= existing.updateTime())
            return true;
        try (PreparedStatement statement =
                        connection.prepareStatement(SELECT_PRIORITIES + " WHERE update_source IN (?, ?)")) {
            statement.setString(3, existing.updateSource());
            statement.setString(4, incomingSource);
            return ChunkUpdatePolicy.assess(incoming, existing, readPriorities(statement, dimension))
                    == IndexStatus.BLOCKED;
        }
    }

    /** Read the exact durability claim for one coordinate, or null when it has no claim. */
    public ChunkRecord queryChunk(String dimension, int x, int z) throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement("SELECT update_time, update_source FROM chunks "
                                + "WHERE source=? AND dimension=? AND x=? AND y=?")) {
            statement.setString(1, sourceId);
            statement.setString(2, dimension);
            statement.setInt(3, x);
            statement.setInt(4, z);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next())
                    return null;
                return new ChunkRecord(
                        x, z, result.getLong("update_time"), result.getString("update_source"));
            }
        }
    }

    /** Resolve the source priorities applicable to this target and dimension for a batch. */
    public Map<String, Integer> sourcePriorities(String dimension) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_PRIORITIES)) {
            return Map.copyOf(readPriorities(statement, dimension));
        }
    }

    /** Whether the index contains a registered source, independent of its applicable scope. */
    public boolean hasRegisteredSource(String updateSource) throws SQLException {
        return hasRegisteredSource(connection, updateSource);
    }

    public static boolean hasRegisteredSource(Connection connection, String updateSource)
            throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement("SELECT 1 FROM update_sources WHERE update_source=?")) {
            statement.setString(1, updateSource);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private Map<String, Integer> readPriorities(PreparedStatement statement, String dimension)
            throws SQLException {
        statement.setString(1, sourceId);
        statement.setString(2, dimension);
        Map<String, Integer> priorities = new HashMap<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String source = result.getString(1);
                if (source == null)
                    continue;
                int priority = result.getInt(2);
                if (!result.wasNull()) {
                    priorities.put(source, priority);
                }
            }
        }
        return priorities;
    }

    /** Record the capture time represented by each already verified MCA entry. */
    public void recordUpdates(String dimension, Map<ChunkCoordinate, Long> times, String updateSource)
            throws SQLException {
        recordUpdates(dimension, times, updateSource, Set.of());
    }

    /** Replace outdated claims for verified writes over missing or physically empty entries. */
    public void recordUpdates(String dimension, Map<ChunkCoordinate, Long> times, String updateSource,
            Set<ChunkCoordinate> replaced) throws SQLException {
        if (!times.keySet().containsAll(replaced)) {
            throw new IllegalArgumentException("Cannot replace an index claim without a verified write");
        }
        if (SEED_GENERATED.equals(updateSource)) {
            if (!replaced.isEmpty()
                    || times.values().stream().anyMatch(
                            time -> !Long.valueOf(SEED_GENERATED_TIME).equals(time))) {
                throw new IllegalArgumentException(
                        "Seed-generated chunks require timestamp zero and cannot replace an index claim");
            }
            recordSeedGeneratedIfMissing(dimension, times.keySet());
            return;
        }
        if (times.isEmpty())
            return;
        JdbcTransaction.run(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                for (Map.Entry<ChunkCoordinate, Long> entry : times.entrySet()) {
                    bindKey(statement, dimension, entry.getKey());
                    statement.setLong(5, entry.getValue());
                    statement.setString(6, updateSource);
                    statement.setBoolean(7, replaced.contains(entry.getKey()));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        });
    }

    /** Record observed seed terrain without replacing an existing durability claim. */
    public void recordSeedGeneratedIfMissing(String dimension, Set<ChunkCoordinate> chunks)
            throws SQLException {
        if (chunks.isEmpty())
            return;
        JdbcTransaction.run(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO chunks "
                         + "(source, dimension, x, y, update_time, update_source) VALUES (?, ?, ?, ?, ?, ?) "
                         + "ON CONFLICT(source, dimension, x, y) DO NOTHING")) {
                for (ChunkCoordinate chunk : chunks) {
                    bindKey(statement, dimension, chunk);
                    statement.setLong(5, SEED_GENERATED_TIME);
                    statement.setString(6, SEED_GENERATED);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        });
    }

    /** Remove only the seed-generation claim for coordinates whose terrain is absent or changed. */
    public void removeSeedGenerated(String dimension, Set<ChunkCoordinate> chunks) throws SQLException {
        removeBySource(dimension, chunks, SEED_GENERATED);
    }

    /** Invalidate this writer's durability claims without erasing other sources' overwrite authority. */
    public void removeUnreadableUpdates(String dimension, Set<ChunkCoordinate> chunks) throws SQLException {
        removeBySource(dimension, chunks, "world_mirror");
    }

    private void removeBySource(String dimension, Set<ChunkCoordinate> chunks, String updateSource)
            throws SQLException {
        if (chunks.isEmpty())
            return;
        JdbcTransaction.run(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM chunks WHERE source=? AND dimension=? AND x=? AND y=? AND update_source=?")) {
                for (ChunkCoordinate chunk : chunks) {
                    bindKey(statement, dimension, chunk);
                    statement.setString(5, updateSource);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        });
    }

    /** Imports pre-SQLite timestamps through the versioned format reader. */
    public int migrateLegacyTimes(Map<String, Long> legacyTimes) throws SQLException {
        return LegacyChunkTimes.importInto(connection, sourceId, legacyTimes);
    }

    public List<ChunkRecord> queryAll(String dimension) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_CHUNKS)) {
            return readRecords(statement, dimension);
        }
    }

    /** Seed generation is durability evidence and is omitted from map projections. */
    public List<ChunkRecord> queryVisible(String dimension) throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(SELECT_CHUNKS + " AND update_source<>?")) {
            statement.setString(3, SEED_GENERATED);
            return readRecords(statement, dimension);
        }
    }

    /** Read only the records in the region being inspected, using the index's coordinate key. */
    public List<ChunkRecord> queryRegion(String dimension, int regionX, int regionZ) throws SQLException {
        long minX = (long) regionX * REGION_SIZE;
        long minZ = (long) regionZ * REGION_SIZE;
        try (PreparedStatement statement = connection.prepareStatement(SELECT_REGION)) {
            int parameter = 3;
            for (int offset = 0; offset < REGION_SIZE; offset++) {
                statement.setLong(parameter++, minX + offset);
            }
            statement.setLong(parameter++, minZ);
            statement.setLong(parameter, minZ + REGION_SIZE - 1);
            return readRecords(statement, dimension);
        }
    }

    private List<ChunkRecord> readRecords(PreparedStatement statement, String dimension) throws SQLException {
        statement.setString(1, sourceId);
        statement.setString(2, dimension);
        List<ChunkRecord> records = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                records.add(new ChunkRecord(result.getInt("x"), result.getInt("y"),
                        result.getLong("update_time"), result.getString("update_source")));
            }
        }
        return records;
    }

    /** Count durable chunks across every dimension for this source. */
    public int countAll() throws SQLException {
        return count(null);
    }

    public int countVisible() throws SQLException {
        return count(SEED_GENERATED);
    }

    private int count(String excludedSource) throws SQLException {
        String filter = excludedSource == null ? "" : " AND update_source<>?";
        try (PreparedStatement statement =
                        connection.prepareStatement("SELECT COUNT(*) FROM chunks WHERE source=?" + filter)) {
            statement.setString(1, sourceId);
            if (excludedSource != null)
                statement.setString(2, excludedSource);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private void bindKey(PreparedStatement statement, String dimension, ChunkCoordinate chunk)
            throws SQLException {
        statement.setString(1, sourceId);
        statement.setString(2, dimension);
        statement.setInt(3, chunk.x());
        statement.setInt(4, chunk.z());
    }
}
