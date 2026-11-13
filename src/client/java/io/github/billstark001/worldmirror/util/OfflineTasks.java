package io.github.billstark001.worldmirror.util;

import java.util.function.Consumer;
import java.util.function.Function;

/** Coordinates UI completion even when background file work fails. */
public final class OfflineTasks {
    @FunctionalInterface
    public interface Work<T> {
        T run() throws Exception;
    }

    private OfflineTasks() {}

    public static <T> void start(String name, Work<T> work, Function<Exception, T> failed,
            Consumer<Runnable> clientExecutor, Consumer<T> completed, Runnable cleanup) {
        start(name, work, failed, clientExecutor, completed, cleanup, cleanup);
    }

    public static <T> void start(String name, Work<T> work, Function<Exception, T> failed,
            Consumer<Runnable> clientExecutor, Consumer<T> completed, Runnable cleanup, Runnable aborted) {
        Thread worker = new Thread(() -> {
            T result;
            boolean ready = false;
            try {
                try {
                    result = work.run();
                } catch (Exception failure) {
                    WMLogger.warn("Offline operation failed: " + name, failure);
                    result = failed.apply(failure);
                }
                ready = true;
            } finally {
                // Even a fatal VM error must not leave an ordinary busy/progress flag stuck.
                if (!ready)
                    deliver(clientExecutor, aborted);
            }
            T outcome = result;
            deliver(clientExecutor, () -> {
                runCallback(cleanup);
                completed.accept(outcome);
            });
        }, name);
        worker.setDaemon(false);
        try {
            worker.start();
        } catch (RuntimeException | Error failure) {
            runCallback(aborted);
            throw failure;
        }
    }

    private static void deliver(Consumer<Runnable> executor, Runnable callback) {
        try {
            executor.accept(() -> runCallback(callback));
        } catch (RuntimeException unavailable) {
            WMLogger.warn("Cannot schedule offline operation UI completion", unavailable);
        }
    }

    private static void runCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException failure) {
            WMLogger.warn("Offline operation UI callback failed", failure);
        }
    }
}
