package io.github.billstark001.worldmirror.util;

import java.util.function.Consumer;
import java.util.function.Function;

/** Coordinates UI completion even when background file work fails. */
public final class OfflineTasks {
    @FunctionalInterface
    public interface Work<T> { T run() throws Exception; }

    private OfflineTasks() {}

    public static <T> void start(String name, Work<T> work, Function<Exception, T> failed,
                                 Consumer<Runnable> clientExecutor, Consumer<T> completed, Runnable cleanup) {
        Thread worker = new Thread(() -> {
            T result;
            try {
                try { result = work.run(); }
                catch (Exception failure) {
                    WMLogger.warn("Offline operation failed: " + name, failure);
                    result = failed.apply(failure);
                }
            } finally {
                // Even a fatal VM error must not leave an ordinary busy/progress flag stuck.
                deliver(clientExecutor, cleanup);
            }
            T outcome = result;
            deliver(clientExecutor, () -> completed.accept(outcome));
        }, name);
        worker.setDaemon(false);
        worker.start();
    }

    private static void deliver(Consumer<Runnable> executor, Runnable callback) {
        try {
            executor.accept(() -> {
                try { callback.run(); }
                catch (RuntimeException failure) { WMLogger.warn("Offline operation UI callback failed", failure); }
            });
        } catch (RuntimeException unavailable) {
            WMLogger.warn("Cannot schedule offline operation UI completion", unavailable);
        }
    }
}
