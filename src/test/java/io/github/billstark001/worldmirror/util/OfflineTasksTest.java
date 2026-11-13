package io.github.billstark001.worldmirror.util;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class OfflineTasksTest {
    @Test
    void cleanupAndCompletionRunInOneClientTask() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        AtomicBoolean cleaned = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        OfflineTasks.start(
                "WM-TestOfflineCompletion", () -> true, failure -> false, callbacks::add, result -> {
                    assertTrue(cleaned.get());
                    completed.set(result);
                }, () -> cleaned.set(true));

        Runnable callback = callbacks.poll(5, TimeUnit.SECONDS);
        assertNotNull(callback);
        assertFalse(cleaned.get());
        callback.run();
        assertTrue(completed.get());
        assertTrue(callbacks.isEmpty());
    }

    @Test
    void unexpectedWorkAndProgressFailuresStillCompleteTheUiLifecycle() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean cleaned = new AtomicBoolean();
        OfflineTasks.<Boolean>start("WM-TestOfflineFailure",
                ()
                        -> {
                    throw new java.io.UncheckedIOException(
                            new java.io.IOException("injected iteration failure"));
                },
                failure
                -> false,
                Runnable::run,
                result
                -> {
                    assertFalse(result);
                    assertTrue(cleaned.get());
                    completed.countDown();
                },
                () -> {
                    cleaned.set(true);
                    throw new IllegalStateException("progress close failure");
                });
        assertTrue(completed.await(5, TimeUnit.SECONDS));
    }
}
