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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses real SQLite writes with injected JDBC failures after a batch has run. */
class ChunkIndexTransactionTest {
    @Test
    void openingAnOldIndexRegistersBothSourcesOnDiskAndPreservesConfiguredRules() throws Exception {
        try (Connection connection = open(); var sql = connection.createStatement()) {
            var index = new ChunkIndexStore(connection, "server:test");
            sql.executeUpdate(
                    "DELETE FROM update_sources WHERE update_source IN ('replay_import', 'seed_generated')");
            sql.executeUpdate(
                    "UPDATE update_sources SET priority=2, apply_to_dimension='minecraft:the_nether' "
                    + "WHERE update_source='world_mirror'");
            assertFalse(
                    index.sourcePriorities("minecraft:overworld").containsKey(ChunkIndexStore.REPLAY_IMPORT));

            ChunkIndexStore.initialize(connection);
            ChunkIndexStore.initialize(connection);

            assertTrue(index.hasRegisteredSource(ChunkIndexStore.REPLAY_IMPORT));
            assertTrue(index.hasRegisteredSource(ChunkIndexStore.SEED_GENERATED));
            assertEquals(
                    10, index.sourcePriorities("minecraft:overworld").get(ChunkIndexStore.REPLAY_IMPORT));
            assertEquals(
                    100, index.sourcePriorities("minecraft:overworld").get(ChunkIndexStore.SEED_GENERATED));
            assertEquals(2, index.sourcePriorities("minecraft:the_nether").get("world_mirror"));
            assertFalse(index.sourcePriorities("minecraft:overworld").containsKey("world_mirror"));
        }
    }

    @Test
    void seedRowsAreWeakInvisibleAndGuardedAgainstOverwritingOtherClaims() throws Exception {
        String dimension = "minecraft:overworld";
        String seed = ChunkIndexStore.SEED_GENERATED;
        var generated = new ChunkCoordinate(1, 2);
        var capture = new ChunkCoordinate(3, 4);
        try (Connection connection = open()) {
            var index = new ChunkIndexStore(connection, "server:test");
            index.recordUpdates(dimension, Map.of(capture, 10L), "world_mirror");
            index.recordSeedGeneratedIfMissing(dimension, java.util.Set.of(generated, capture));
            index.recordUpdates(dimension, Map.of(capture, 0L), seed);
            assertThrows(IllegalArgumentException.class,
                    () -> index.recordUpdates(dimension, Map.of(generated, 1L), seed));
            assertThrows(IllegalArgumentException.class,
                    ()
                            -> index.recordUpdates(
                                    dimension, Map.of(generated, 0L), seed, java.util.Set.of(generated)));
            assertFalse(index.shouldSkipUpdate(dimension, 1, 2, ChunkIndexStore.REPLAY_IMPORT, 1L));
            assertEquals(2, index.countAll());
            assertEquals(1, index.countVisible());
            assertEquals(List.of(new ChunkIndexStore.ChunkRecord(3, 4, 10L, "world_mirror")),
                    index.queryVisible(dimension));

            index.removeSeedGenerated(dimension, java.util.Set.of(generated, capture));

            assertEquals(1, index.countAll());
            assertEquals("world_mirror", index.queryAll(dimension).getFirst().updateSource());
        }
    }

    @Test
    void boundsRegionQueriesByBothCoordinatesSourceAndDimension() throws Exception {
        try (Connection connection = open()) {
            String dimension = "minecraft:overworld";
            var index = new ChunkIndexStore(connection, "server:test");
            var corner = new ChunkCoordinate(-32, 32);
            index.recordUpdates(dimension,
                    Map.of(corner, 100L, new ChunkCoordinate(-33, 32), 200L, new ChunkCoordinate(-32, 64),
                            300L),
                    "world_mirror");
            index.recordUpdates("minecraft:the_nether", Map.of(corner, 400L), "world_mirror");
            new ChunkIndexStore(connection, "server:other")
                    .recordUpdates(dimension, Map.of(corner, 500L), "world_mirror");

            assertEquals(List.of(new ChunkIndexStore.ChunkRecord(-32, 32, 100L, "world_mirror")),
                    index.queryRegion(dimension, -1, 1));
        }
    }

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
                if (method.equals("executeBatch"))
                    throw new SQLException("write failed");
                if (method.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                    throw new SQLException("reset failed");
                }
            });
            SQLException failure = assertThrows(
                    SQLException.class, () -> write(new ChunkIndexStore(wrapper, "server:test")));

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
                if (method.equals("executeBatch"))
                    throw new SQLException("write failed");
                if (method.equals("rollback"))
                    throw new SQLException("rollback failed");
                if (method.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                    attemptedRestore.set(true);
                }
            });
            assertThrows(SQLException.class, () -> write(new ChunkIndexStore(wrapper, "server:test")));

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
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    // Rollback/reset failures must happen before touching the actual connection.
                    if (method.getName().equals("rollback") || method.getName().equals("setAutoCommit")) {
                        hook.after(method.getName(), args);
                    }
                    Object result = invoke(delegate, method, args);
                    if (result instanceof PreparedStatement statement) {
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[] {PreparedStatement.class},
                                (statementProxy, operation, parameters) -> {
                                    Object value = invoke(statement, operation, parameters);
                                    hook.after(operation.getName(), parameters);
                                    return value;
                                });
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
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
            try (var sql = connection.createStatement()) {
                sql.execute("DROP TABLE update_sources");
            }
            assertThrows(SQLException.class,
                    () -> store.shouldSkipUpdate("minecraft:overworld", 0, 0, "world_mirror", 200L));
            store.removeUnreadableUpdates("minecraft:overworld", java.util.Set.of(pos));
            assertEquals("player", store.queryAll("minecraft:overworld").getFirst().updateSource());
        }
    }
}
