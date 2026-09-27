package io.github.billstark001.worldmirror.format;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** JDBC operations for the World Mirror chunk durability index. The host owns the connection. */
public final class ChunkIndexStore {
    private static final String UPSERT = "INSERT INTO chunks "
            + "(source, dimension, x, y, update_time, update_source) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT(source, dimension, x, y) DO UPDATE SET "
            + "update_time=excluded.update_time, update_source=excluded.update_source "
            + "WHERE excluded.update_time >= chunks.update_time";

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
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT update_time, update_source FROM chunks "
                        + "WHERE source=? AND dimension=? AND x=? AND y=?")) {
            statement.setString(1, sourceId);
            statement.setString(2, dimension);
            statement.setInt(3, x);
            statement.setInt(4, z);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                long existingTime = result.getLong("update_time");
                if (incomingTime <= existingTime) return true;
                int existingPriority = priority(result.getString("update_source"), dimension);
                int incomingPriority = priority(incomingSource, dimension);
                return ChunkUpdatePolicy.shouldSkip(existingTime, existingPriority,
                        incomingTime, incomingPriority);
            }
        }
    }

    /** Unknown sources and failed priority lookups receive the weakest priority. */
    private int priority(String updateSource, String dimension) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT priority FROM update_sources WHERE update_source=? "
                        + "AND (apply_to_source IS NULL OR apply_to_source=?) "
                        + "AND (apply_to_dimension IS NULL OR apply_to_dimension=?)")) {
            statement.setString(1, updateSource);
            statement.setString(2, sourceId);
            statement.setString(3, dimension);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) return result.getInt("priority");
            }
        } catch (SQLException ignored) {
            // Matches World Mirror's conservative fallback for a priority lookup.
        }
        return Integer.MAX_VALUE;
    }

    /** Record the capture time represented by each already verified MCA entry. */
    public void recordUpdates(String dimension, Map<ChunkCoordinate, Long> times,
                              String updateSource) throws SQLException {
        if (times.isEmpty()) return;
        inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                for (Map.Entry<ChunkCoordinate, Long> entry : times.entrySet()) {
                    bindKey(statement, dimension, entry.getKey());
                    statement.setLong(5, entry.getValue());
                    statement.setString(6, updateSource);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        });
    }

    public void removeUnreadableUpdates(String dimension, Set<ChunkCoordinate> chunks)
            throws SQLException {
        if (chunks.isEmpty()) return;
        inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM chunks WHERE source=? AND dimension=? AND x=? AND y=?")) {
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
                    int separator = key.lastIndexOf('|');
                    if (separator < 0 || entry.getValue() == null) continue;
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
        List<ChunkRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT x, y, update_time, update_source FROM chunks "
                        + "WHERE source=? AND dimension=?")) {
            statement.setString(1, sourceId);
            statement.setString(2, dimension);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    records.add(new ChunkRecord(result.getInt("x"), result.getInt("y"),
                            result.getLong("update_time"), result.getString("update_source")));
                }
            }
        }
        return records;
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
        try {
            action.run();
            connection.commit();
        } catch (SQLException failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
