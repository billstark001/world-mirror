package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, coalesced reports for file operations outside per-chunk export receipts. */
public final class OperationFailures {
    private static final long REMINDER_MS = 60_000L;
    private static final int MAX_EPISODES = 64;
    private record Failure(String source, String stage, Path path, String reason, long nextNotice) {}
    private static final Map<String, Failure> FAILURES = new LinkedHashMap<>();
    private static volatile long wakeAt = Long.MAX_VALUE;

    private OperationFailures() {}

    public static synchronized void report(String source, String stage, Path path, Exception failure) {
        String key = source + ":" + stage;
        Failure previous = FAILURES.get(key);
        long next = previous == null ? 0 : previous.nextNotice();
        FAILURES.put(key, new Failure(source, stage, path, failure.toString(), next));
        while (FAILURES.size() > MAX_EPISODES) FAILURES.remove(FAILURES.keySet().iterator().next());
        wakeAt = Math.min(wakeAt, next);
        WMLogger.warnRateLimited("operation:" + key, REMINDER_MS,
                "Operation needs attention source=" + source + " stage=" + stage + " path=" + path, failure);
    }

    public static synchronized void recovered(String source, String stage) {
        FAILURES.remove(source + ":" + stage);
    }

    /** One volatile deadline comparison on idle ticks; delivery never runs on an IO worker. */
    public static void tick(Minecraft client, long now) {
        if (now < wakeAt || client.player == null) return;
        Failure selected = null;
        synchronized (OperationFailures.class) {
            for (var entry : FAILURES.entrySet()) {
                Failure failure = entry.getValue();
                if (failure.nextNotice() <= now) {
                    selected = failure;
                    entry.setValue(new Failure(failure.source(), failure.stage(), failure.path(),
                            failure.reason(), now + REMINDER_MS));
                    break;
                }
            }
            wakeAt = FAILURES.values().stream().mapToLong(Failure::nextNotice).min().orElse(Long.MAX_VALUE);
            if (selected != null) wakeAt = Math.max(wakeAt, now + 2_000L);
        }
        if (selected != null) {
            try {
                Component title = Component.translatable("msg.worldmirror.fileOperationFailed").withStyle(ChatFormatting.RED);
                Component body = Component.translatable("msg.worldmirror.fileOperationDetails",
                        selected.source(), selected.stage(), selected.path(), selected.reason());
                WMPlayerMessages.sendSystemMessage(client.player, title.copy().append(" ").append(body));
                WMPlayerMessages.showExportToast(client, title, Component.literal(selected.stage()));
            } catch (RuntimeException displayFailure) {
                WMLogger.warnRateLimited("operation-notice", REMINDER_MS, "Cannot display file failure notice", displayFailure);
            }
        }
    }
}
