package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.format.internal.JdbcTransaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/** Import of the timestamp map from mirror metadata before the SQLite index. */
public final class LegacyChunkTimes {
    private LegacyChunkTimes() {}

    /** Imports v0 JSON timestamps without replacing rows already present in SQLite. */
    public static int importInto(Connection connection, String sourceId, Map<String, Long> legacyTimes) throws SQLException {
        if (legacyTimes == null || legacyTimes.isEmpty()) return 0;
        int[] count = {0};
        JdbcTransaction.run(connection, () -> {
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
                        int x = Integer.parseInt(coords[0].trim());
                        int z = Integer.parseInt(coords[1].trim());
                        statement.setString(1, sourceId);
                        statement.setString(2, key.substring(0, separator));
                        statement.setInt(3, x);
                        statement.setInt(4, z);
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
}
