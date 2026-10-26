package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.format.ChunkIndexStore;
import io.github.billstark001.worldmirror.format.ChunkIndexStore.ChunkCoordinate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses real SQLite writes with injected JDBC failures after a batch has run. */
class ChunkIndexTransactionTest {
    @Test
    void rollsBackUncheckedDriverFailureAndLeavesConnectionUsable() throws Exception {
        try (Connection connection = open()) {
            AtomicBoolean failOnce = new AtomicBoolean(true);
            Connection wrapper = intercept(connection, (method, args) -> {
                if (method.equals("executeBatch") && failOnce.getAndSet(false)) {
                    throw new IllegalStateException("driver failure after write");
                }
            });
            ChunkIndexStore store = new ChunkIndexStore(wrapper, "server:test");

            assertThrows(IllegalStateException.class, () -> write(store));
            assertTrue(connection.getAutoCommit());
            assertEquals(0, store.countAll(), "an unchecked failure must not commit the batch");

            write(store);
            assertEquals(1, store.countAll());
        }
    }

    @Test
    void retainsTheOriginalFailureWhenRestoringAutoCommitAlsoFails() throws Exception {
        try (Connection connection = open()) {
            Connection wrapper = intercept(connection, (method, args) -> {
                if (method.equals("executeBatch")) throw new SQLException("write failed");
                if (method.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                    throw new SQLException("reset failed");
                }
            });
            SQLException failure = assertThrows(SQLException.class,
                    () -> write(new ChunkIndexStore(wrapper, "server:test")));

            assertEquals("write failed", failure.getMessage());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals("reset failed", failure.getSuppressed()[0].getMessage());
            assertEquals(0, new ChunkIndexStore(connection, "server:test").countAll());
        }
    }

    @Test
    void neverImplicitlyCommitsWhenRollbackFails() throws Exception {
        try (Connection connection = open()) {
            AtomicBoolean attemptedRestore = new AtomicBoolean();
            Connection wrapper = intercept(connection, (method, args) -> {
                if (method.equals("executeBatch")) throw new SQLException("write failed");
                if (method.equals("rollback")) throw new SQLException("rollback failed");
                if (method.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                    attemptedRestore.set(true);
                }
            });
            assertThrows(SQLException.class,
                    () -> write(new ChunkIndexStore(wrapper, "server:test")));

            assertFalse(attemptedRestore.get(), "enabling auto-commit would commit an uncertain batch");
            assertFalse(connection.getAutoCommit());
            connection.rollback();
            connection.setAutoCommit(true);
            assertEquals(0, new ChunkIndexStore(connection, "server:test").countAll());
        }
    }

    private static Connection open() throws Exception {
        ChunkDatabase.configureSqliteNativeDirectory();
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        ChunkIndexStore.initialize(connection);
        return connection;
    }

    private static void write(ChunkIndexStore store) throws SQLException {
        store.recordUpdates("minecraft:overworld", Map.of(new ChunkCoordinate(1, 2), 100L), "world_mirror");
    }

    @FunctionalInterface
    private interface FailureHook {
        void after(String method, Object[] args) throws SQLException;
    }

    private static Connection intercept(Connection delegate, FailureHook hook) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    // Rollback/reset failures must happen before touching the actual connection.
                    if (method.getName().equals("rollback") || method.getName().equals("setAutoCommit")) {
                        hook.after(method.getName(), args);
                    }
                    Object result = invoke(delegate, method, args);
                    if (result instanceof PreparedStatement statement) {
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (statementProxy, operation, parameters) -> {
                                    Object value = invoke(statement, operation, parameters);
                                    hook.after(operation.getName(), parameters);
                                    return value;
                                });
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
    @Test
    void unavailablePriorityRulesNeverAuthorizeAnOverwrite() throws Exception {
        try (Connection connection = open()) {
            ChunkIndexStore store = new ChunkIndexStore(connection, "server:test");
            var pos = new ChunkCoordinate(0, 0);
            store.recordUpdates("minecraft:overworld", Map.of(pos, 100L), "player");
            assertTrue(store.shouldSkipUpdate("minecraft:overworld", 0, 0, "world_mirror", 200L));
            try (var sql = connection.createStatement()) { sql.execute("DROP TABLE update_sources"); }
            assertThrows(SQLException.class, () -> store.shouldSkipUpdate(
                    "minecraft:overworld", 0, 0, "world_mirror", 200L));
            store.removeUnreadableUpdates("minecraft:overworld", java.util.Set.of(pos));
            assertEquals("player", store.queryAll("minecraft:overworld").getFirst().updateSource());
        }
    }

}
