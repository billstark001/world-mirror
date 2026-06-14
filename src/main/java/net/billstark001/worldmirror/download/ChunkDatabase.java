package net.billstark001.worldmirror.download;

import net.billstark001.worldmirror.util.WMLogger;
import net.minecraft.world.level.ChunkPos;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Map;
import java.util.Set;

/**
 * Manages the per-world SQLite database at {@code <worldFolder>/data/world_mirror.sqlite}.
 * <p>
 * Stores chunk update history (for dirty-check and priority enforcement) and the
 * configurable {@code update_sources} priority table.
 *
 * <h2>Schema</h2>
 * <pre>
 * chunks:
 *   source          TEXT  -- world/server identifier (sourceId)
 *   dimension       TEXT  -- dimension key, e.g. "minecraft:overworld"
 *   x               INTEGER -- chunk X coordinate
 *   y               INTEGER -- chunk Z coordinate (named "y" for external-tool compatibility)
 *   update_time     INTEGER -- Unix timestamp (ms) of last write
 *   update_source   TEXT  -- name of the update source, e.g. "world_mirror"
 *   PRIMARY KEY (source, dimension, x, y)
 *
 * update_sources:
 *   update_source      TEXT PRIMARY KEY
 *   priority           INTEGER  -- lower number = higher priority
 *   apply_to_source    TEXT     -- if non-null, rule only applies when source matches
 *   apply_to_dimension TEXT     -- if non-null, rule only applies in this dimension
 * </pre>
 *
 * <p>See {@code DATABASE.md} at the repository root for full documentation.
 */
public class ChunkDatabase implements Closeable {

    /** Default rows inserted into {@code update_sources} on first creation. */
    private static final String[] DEFAULT_SOURCES = {
            "INSERT OR IGNORE INTO update_sources (update_source, priority, apply_to_source, apply_to_dimension) VALUES ('player',       0,  NULL, NULL)",
            "INSERT OR IGNORE INTO update_sources (update_source, priority, apply_to_source, apply_to_dimension) VALUES ('world_mirror', 10, NULL, NULL)",
            "INSERT OR IGNORE INTO update_sources (update_source, priority, apply_to_source, apply_to_dimension) VALUES ('game_events',  20, NULL, NULL)",
            "INSERT OR IGNORE INTO update_sources (update_source, priority, apply_to_source, apply_to_dimension) VALUES ('map_hp',       30, NULL, NULL)",
            "INSERT OR IGNORE INTO update_sources (update_source, priority, apply_to_source, apply_to_dimension) VALUES ('map_lp',       50, NULL, NULL)"
    };

    private final Connection conn;
    private final String sourceId;

    private ChunkDatabase(Connection conn, String sourceId) {
        this.conn     = conn;
        this.sourceId = sourceId;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Opens (or creates) the database at {@code worldFolder/data/world_mirror.sqlite}.
     * Creates tables and seeds default {@code update_sources} entries if needed.
     *
     * @param worldFolder root directory of the mirror world
     * @param sourceId    world/server identifier used as the {@code source} column value
     * @return open database instance; caller must {@link #close()} it
     */
    public static ChunkDatabase open(Path worldFolder, String sourceId) throws SQLException {
        Path dataDir = worldFolder.resolve("data");
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            throw new SQLException("Cannot create data directory: " + e.getMessage(), e);
        }

        Path dbFile = dataDir.resolve("world_mirror.sqlite");
        String url  = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        Connection conn = DriverManager.getConnection(url);

        try (Statement st = conn.createStatement()) {
            // Use WAL for better concurrent read performance
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");

            st.execute(
                "CREATE TABLE IF NOT EXISTS chunks (" +
                "  source        TEXT    NOT NULL," +
                "  dimension     TEXT    NOT NULL," +
                "  x             INTEGER NOT NULL," +
                "  y             INTEGER NOT NULL," +
                "  update_time   INTEGER NOT NULL DEFAULT 0," +
                "  update_source TEXT    NOT NULL DEFAULT 'world_mirror'," +
                "  PRIMARY KEY (source, dimension, x, y)" +
                ")"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS update_sources (" +
                "  update_source      TEXT PRIMARY KEY," +
                "  priority           INTEGER NOT NULL," +
                "  apply_to_source    TEXT," +
                "  apply_to_dimension TEXT" +
                ")"
            );
        }

        // Seed default update_sources entries (idempotent via INSERT OR IGNORE)
        try (Statement st = conn.createStatement()) {
            for (String sql : DEFAULT_SOURCES) {
                st.execute(sql);
            }
        }

        return new ChunkDatabase(conn, sourceId);
    }

    // ── Core query / update ───────────────────────────────────────────────────

    /**
     * Returns {@code true} if the chunk update should be skipped.
     *
     * <p>Skips if either condition is true:
     * <ol>
     *   <li><b>Not dirty</b>: {@code newTimestamp} is not newer than the last
     *       recorded write time for this chunk.</li>
     *   <li><b>Higher-priority source</b>: the source that previously wrote this
     *       chunk has a strictly higher priority (lower number) than
     *       {@code newUpdateSource} in the current (sourceId, dimension) context.</li>
     * </ol>
     *
     * @param dimension       dimension key, e.g. {@code "minecraft:overworld"}
     * @param x               chunk X coordinate
     * @param y               chunk Z coordinate
     * @param newUpdateSource name of the update source requesting the write
     * @param newTimestamp    capture timestamp in milliseconds
     */
    public boolean shouldSkipUpdate(String dimension, int x, int y,
                                    String newUpdateSource, long newTimestamp) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT update_time, update_source FROM chunks " +
                "WHERE source=? AND dimension=? AND x=? AND y=?")) {
            ps.setString(1, sourceId);
            ps.setString(2, dimension);
            ps.setInt(3, x);
            ps.setInt(4, y);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false; // no existing entry → allow update

                long   lastWriteMs     = rs.getLong("update_time");
                String existingSource  = rs.getString("update_source");

                // Dirty check: skip if chunk has not changed since last export
                if (newTimestamp <= lastWriteMs) return true;

                // Priority check: skip if existing source has strictly higher priority
                int existingPriority = getPriority(existingSource, dimension);
                int newPriority      = getPriority(newUpdateSource, dimension);
                return existingPriority < newPriority;
            }
        } catch (SQLException e) {
            WMLogger.warn("ChunkDatabase.shouldSkipUpdate error: " + e.getMessage());
            return false; // fail-open: allow update on error
        }
    }

    /**
     * Batch-records that a set of chunks was written by {@code updateSource} at
     * the current time.  Executes inside a single transaction for efficiency.
     *
     * @param dimension    dimension key
     * @param positions    chunk positions that were written
     * @param updateSource name of the update source (e.g. {@code "world_mirror"})
     */
    public void recordUpdates(String dimension, Set<ChunkPos> positions, String updateSource) {
        if (positions.isEmpty()) return;
        long now = System.currentTimeMillis();
        String sql =
            "INSERT INTO chunks (source, dimension, x, y, update_time, update_source) " +
            "VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(source, dimension, x, y) DO UPDATE SET " +
            "  update_time=excluded.update_time, update_source=excluded.update_source";
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (ChunkPos pos : positions) {
                    ps.setString(1, sourceId);
                    ps.setString(2, dimension);
                    ps.setInt(3, pos.x());
                    ps.setInt(4, pos.z());
                    ps.setLong(5, now);
                    ps.setString(6, updateSource);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            WMLogger.warn("ChunkDatabase.recordUpdates error: " + e.getMessage());
            try { conn.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /**
     * Migrates legacy {@code chunkUpdateTimes} data from {@code worldmirror_meta.json}
     * into the {@code chunks} table.  Uses {@code INSERT OR IGNORE} so it is safe
     * to call multiple times without duplicating records.
     *
     * @param chunkUpdateTimes map from {@code "<dimension>|<chunkX>,<chunkZ>"} to
     *                         millisecond timestamp (as stored in old JSON metadata)
     */
    public void migrateFromChunkUpdateTimes(Map<String, Long> chunkUpdateTimes) {
        if (chunkUpdateTimes == null || chunkUpdateTimes.isEmpty()) return;
        String sql =
            "INSERT OR IGNORE INTO chunks " +
            "  (source, dimension, x, y, update_time, update_source) " +
            "VALUES (?, ?, ?, ?, ?, 'world_mirror')";
        int migrated = 0;
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Map.Entry<String, Long> entry : chunkUpdateTimes.entrySet()) {
                    String key       = entry.getKey();
                    long   timestamp = entry.getValue();
                    // key format: "minecraft:overworld|3,-5"
                    int pipeIdx = key.lastIndexOf('|');
                    if (pipeIdx < 0) continue;
                    String   dimension = key.substring(0, pipeIdx);
                    String[] coords    = key.substring(pipeIdx + 1).split(",", 2);
                    if (coords.length < 2) continue;
                    try {
                        int chunkX = Integer.parseInt(coords[0].trim());
                        int chunkZ = Integer.parseInt(coords[1].trim());
                        ps.setString(1, sourceId);
                        ps.setString(2, dimension);
                        ps.setInt(3, chunkX);
                        ps.setInt(4, chunkZ);
                        ps.setLong(5, timestamp);
                        ps.addBatch();
                        migrated++;
                    } catch (NumberFormatException ignored) {}
                }
                ps.executeBatch();
            }
            conn.commit();
            WMLogger.info("Migrated " + migrated + " chunk timestamps from JSON to SQLite.");
        } catch (SQLException e) {
            WMLogger.warn("ChunkDatabase migration failed: " + e.getMessage());
            try { conn.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            WMLogger.warn("ChunkDatabase.close error: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the effective priority of {@code updateSource} for the current
     * (sourceId, dimension) context.  Returns {@link Integer#MAX_VALUE} if no
     * matching row exists, treating the source as having the lowest possible priority.
     */
    private int getPriority(String updateSource, String dimension) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT priority FROM update_sources " +
                "WHERE update_source=? " +
                "  AND (apply_to_source IS NULL    OR apply_to_source=?) " +
                "  AND (apply_to_dimension IS NULL OR apply_to_dimension=?)")) {
            ps.setString(1, updateSource);
            ps.setString(2, sourceId);
            ps.setString(3, dimension);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("priority");
            }
        } catch (SQLException e) {
            WMLogger.warn("ChunkDatabase.getPriority error: " + e.getMessage());
        }
        return Integer.MAX_VALUE;
    }
}
