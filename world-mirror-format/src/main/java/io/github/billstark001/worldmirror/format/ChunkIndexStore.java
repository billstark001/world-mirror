package io.github.billstark001.worldmirror.format;

import io.github.billstark001.worldmirror.format.ChunkUpdatePolicy.IndexStatus;

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
    private static final int REPLAY_IMPORT_PRIORITY = 10;
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

    public record ChunkCoordinate(int x, int z) { }
    public record ChunkRecord(int x, int z, long updateTime, String updateSource) { }

    public ChunkIndexStore(Connection connection, String sourceId) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
    }

    /** Initialize an existing or new index without changing previously registered source rules. */
    public static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("CREATE TABLE IF NOT EXISTS chunks ("
                    + "source TEXT NOT NULL, dimension TEXT NOT NULL, x INTEGER NOT NULL, "
                    + "y INTEGER NOT NULL, update_time INTEGER NOT NULL DEFAULT 0, "
                    + "update_source TEXT NOT NULL DEFAULT 'world_mirror', "
                    + "PRIMARY KEY(source, dimension, x, y))");
            statement.execute("CREATE TABLE IF NOT EXISTS update_sources ("
                    + "update_source TEXT PRIMARY KEY, priority INTEGER NOT NULL, "
                    + "apply_to_source TEXT, apply_to_dimension TEXT)");
        }
        seedSource(connection, "player", 0);
        seedSource(connection, "world_mirror", 10);
        seedSource(connection, "game_events", 20);
        seedSource(connection, "map_hp", 30);
        seedSource(connection, "map_lp", 50);
        seedSource(connection, REPLAY_IMPORT, REPLAY_IMPORT_PRIORITY);
    }

    private static void seedSource(Connection connection, String source, int priority) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO update_sources "
                        + "(update_source, priority, apply_to_source, apply_to_dimension) "
                        + "VALUES (?, ?, NULL, NULL)")) {
            statement.setString(1, source);
            statement.setInt(2, priority);
            statement.executeUpdate();
        }
    }

    public boolean shouldSkipUpdate(String dimension, int x, int z,
                                    String incomingSource, long incomingTime) throws SQLException {
        var incoming = new ChunkUpdate(dimension, new ChunkCoordinate(x, z), incomingTime, incomingSource);
        ChunkRecord existing;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT update_time, update_source FROM chunks "
                        + "WHERE source=? AND dimension=? AND x=? AND y=?")) {
            statement.setString(1, sourceId);
            statement.setString(2, dimension);
            statement.setInt(3, x);
            statement.setInt(4, z);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                existing = new ChunkRecord(x, z, result.getLong("update_time"), result.getString("update_source"));
            }
        }
        if (incomingTime <= existing.updateTime()) return true;
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_PRIORITIES + " WHERE update_source IN (?, ?)")) {
            statement.setString(3, existing.updateSource());
            statement.setString(4, incomingSource);
            return ChunkUpdatePolicy.assess(incoming, existing, readPriorities(statement, dimension))
                    == IndexStatus.BLOCKED;
        }
    }

    /** Resolve the source priorities applicable to this target and dimension for a batch. */
    public Map<String, Integer> sourcePriorities(String dimension) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_PRIORITIES)) {
            return Map.copyOf(readPriorities(statement, dimension));
        }
    }

    private Map<String, Integer> readPriorities(PreparedStatement statement, String dimension) throws SQLException {
        statement.setString(1, sourceId);
        statement.setString(2, dimension);
        Map<String, Integer> priorities = new HashMap<>();
        // A read-only plan against an existing save can predate source registration.
        priorities.put(REPLAY_IMPORT, REPLAY_IMPORT_PRIORITY);
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String source = result.getString(1);
                if (source == null) continue;
                int priority = result.getInt(2);
                if (result.wasNull()) {
                    priorities.remove(source);
                } else {
                    priorities.put(source, priority);
                }
            }
        }
        return priorities;
    }

    /** Record the capture time represented by each already verified MCA entry. */
    public void recordUpdates(String dimension, Map<ChunkCoordinate, Long> times,
                              String updateSource) throws SQLException {
        recordUpdates(dimension, times, updateSource, Set.of());
    }

    /** Replace outdated claims for verified writes over missing or physically empty entries. */
    public void recordUpdates(String dimension, Map<ChunkCoordinate, Long> times,
                              String updateSource, Set<ChunkCoordinate> replaced) throws SQLException {
        if (!times.keySet().containsAll(replaced)) {
            throw new IllegalArgumentException("Cannot replace an index claim without a verified write");
        }
        if (times.isEmpty()) return;
        inTransaction(() -> {
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

    /** Invalidate this writer's durability claims without erasing other sources' overwrite authority. */
    public void removeUnreadableUpdates(String dimension, Set<ChunkCoordinate> chunks)
            throws SQLException {
        if (chunks.isEmpty()) return;
        inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM chunks WHERE source=? AND dimension=? AND x=? AND y=? AND update_source='world_mirror'")) {
                for (ChunkCoordinate chunk : chunks) {
                    bindKey(statement, dimension, chunk);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        });
    }

    /** Import pre-SQLite timestamps without replacing records already written by another source. */
    public int migrateLegacyTimes(Map<String, Long> legacyTimes) throws SQLException {
        if (legacyTimes == null || legacyTimes.isEmpty()) return 0;
        int[] count = {0};
        inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO chunks "
                            + "(source, dimension, x, y, update_time, update_source) "
                            + "VALUES (?, ?, ?, ?, ?, 'world_mirror')")) {
                for (Map.Entry<String, Long> entry : legacyTimes.entrySet()) {
                    String key = entry.getKey();
                    if (key == null || entry.getValue() == null) continue;
                    int separator = key.lastIndexOf('|');
                    if (separator < 0) continue;
                    String[] coords = key.substring(separator + 1).split(",", 2);
                    if (coords.length != 2) continue;
                    try {
                        ChunkCoordinate chunk = new ChunkCoordinate(
                                Integer.parseInt(coords[0].trim()),
                                Integer.parseInt(coords[1].trim()));
                        bindKey(statement, key.substring(0, separator), chunk);
                        statement.setLong(5, entry.getValue());
                        statement.addBatch();
                        count[0]++;
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed legacy keys, as World Mirror has always done.
                    }
                }
                statement.executeBatch();
            }
        });
        return count[0];
    }

    public List<ChunkRecord> queryAll(String dimension) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_CHUNKS)) {
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
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM chunks WHERE source=?")) {
            statement.setString(1, sourceId);
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

    @FunctionalInterface
    private interface SqlAction { void run() throws SQLException; }

    private void inTransaction(SqlAction action) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (!originalAutoCommit) {
            throw new SQLException("ChunkIndexStore requires a connection outside another transaction");
        }
        connection.setAutoCommit(false);
        Throwable failure = null;
        boolean safeToRestore = true;
        try {
            action.run();
            connection.commit();
        } catch (SQLException | RuntimeException | Error writeFailure) {
            failure = writeFailure;
            try {
                connection.rollback();
            } catch (SQLException | RuntimeException | Error rollback) {
                writeFailure.addSuppressed(rollback);
                // JDBC commits the current transaction when auto-commit is
                // enabled. An unsuccessful rollback must never take that path.
                safeToRestore = false;
            }
            throw writeFailure;
        } finally {
            if (safeToRestore) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException | RuntimeException | Error restore) {
                    if (failure == null) throw restore;
                    failure.addSuppressed(restore);
                }
            }
        }
    }
}
