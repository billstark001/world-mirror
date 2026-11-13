package io.github.billstark001.worldmirror.format.internal;

import java.sql.Connection;
import java.sql.SQLException;

/** Transaction ownership shared by current index writes and timestamp import. */
public final class JdbcTransaction {
    private JdbcTransaction() {}

    @FunctionalInterface
    public interface Action { void run() throws SQLException; }

    public static void run(Connection connection, Action action) throws SQLException {
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
