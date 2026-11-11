package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import io.github.billstark001.worldmirror.ui.ExportStatusText;

import java.util.LinkedHashMap;
import java.util.Map;

/** One coalesced notice per source. Worker observations never wait for player delivery. */
public final class ExportNotifications {
    public static final long REMINDER_MS = 30_000L;
    private static final ExportNotifications GLOBAL = new ExportNotifications();
    public static ExportNotifications global() { return GLOBAL; }
    public record Notice(ExportStatus.Health health, boolean recovered, long merged) { }
    private static final class Episode {
        ExportStatus.Health health;
        boolean reported, reportedBlocked, hadFailure, recovery;
        long nextNotice, merged;
    }
    private final Map<String, Episode> episodes = new LinkedHashMap<>();
    private long nextDelivery = Long.MIN_VALUE;
    private volatile long wakeAt = Long.MAX_VALUE;

    public synchronized void observe(ExportStatus.Health health) {
        if (!health.degraded() && !episodes.containsKey(health.source())) return;
        Episode episode = episodes.computeIfAbsent(health.source(), ignored -> new Episode());
        episode.health = health;
        if (health.degraded()) {
            episode.hadFailure = true;
            episode.recovery = false;
            episode.merged++;
            String text = "Export requires attention source=" + health.source() + " stage=" + health.stage()
                    + " chunks=" + health.counts() + "; frozen work retained for retry";
            if (health.blocked()) WMLogger.errorRateLimited("export-blocked:" + health.source(), REMINDER_MS, text);
            else WMLogger.warnRateLimited("export-partial:" + health.source(), REMINDER_MS, text);
        } else episode.recovery = episode.hadFailure && health.settled();
        wakeAt = Long.MIN_VALUE;
    }

    synchronized void refresh(java.util.function.Function<String, ExportStatus.Health> current) {
        episodes.forEach((id, episode) -> {
            episode.health = current.apply(id);
            episode.recovery = episode.hadFailure && episode.health.settled();
        });
    }

    synchronized Notice poll(long now) {
        if (now < nextDelivery) return null;
        Episode selected = null;
        int selectedPriority = 0;
        for (Episode episode : episodes.values()) {
            var health = episode.health;
            int priority = episode.recovery ? 2 : !health.degraded() ? 0
                    : !episode.reported || health.blocked() && !episode.reportedBlocked ? 3
                    : now >= episode.nextNotice ? 1 : 0;
            if (priority > 0 && (selected == null || priority > selectedPriority
                    || priority == selectedPriority && episode.nextNotice < selected.nextNotice)) {
                selected = episode;
                selectedPriority = priority;
            }
        }
        if (selected == null) return null;
        var health = selected.health;
        Notice notice = new Notice(health, selected.recovery, selected.merged);
        selected.reported = !selected.recovery;
        selected.reportedBlocked = health.blocked();
        selected.nextNotice = now + REMINDER_MS;
        selected.merged = 0;
        if (selected.recovery) {
            selected.hadFailure = false;
            selected.recovery = false;
            WMLogger.info("Export recovered source=" + health.source());
            episodes.remove(health.source());
        }
        nextDelivery = now + 2_000L;
        return notice;
    }

    /** Event/deadline-driven policy: idle ticks perform one volatile comparison. */
    synchronized Notice next(long now, java.util.function.Function<String, ExportStatus.Health> current) {
        if (now < wakeAt) return null;
        refresh(current);
        Notice notice = poll(now);
        long deadline = Long.MAX_VALUE;
        for (var episode : episodes.values()) {
            if (episode.recovery || episode.health.degraded() && (!episode.reported
                    || episode.health.blocked() && !episode.reportedBlocked)) deadline = Long.MIN_VALUE;
            else if (episode.health.degraded()) deadline = Math.min(deadline, episode.nextNotice);
        }
        wakeAt = deadline == Long.MAX_VALUE ? deadline : Math.max(nextDelivery, deadline);
        return notice;
    }

    /** Called on the client thread even when downloading has stopped. */
    public void tick(Minecraft client, long now) {
        if (now < wakeAt || client.player == null) return;
        Notice notice = next(now, ExportStatusStore.global()::health);
        if (notice == null) return;
        var health = notice.health();
        String key = notice.recovered() ? "exportRecovered" : health.blocked() ? "exportFailed" : "exportPartial";
        Component title = Component.translatable("msg.worldmirror." + key)
                .withStyle(notice.recovered() ? ChatFormatting.GREEN : ChatFormatting.RED);
        Component body = Component.translatable("msg.worldmirror.exportHealth", health.source(),
                ExportStatusText.stage(health), Math.max(health.counts().unresolved(), health.worldFailure() ? 1 : 0),
                health.counts().queued() + health.counts().writing());
        try {
            WMPlayerMessages.sendSystemMessage(client.player, title.copy().append(" ").append(body));
            WMPlayerMessages.showExportToast(client, Component.translatable("msg.worldmirror.exportToast." + key),
                    Component.translatable("msg.worldmirror.exportToast.health", ExportStatusText.stage(health),
                            Math.max(health.counts().unresolved(), health.worldFailure() ? 1 : 0)));
        } catch (RuntimeException failure) {
            WMLogger.warnRateLimited("export-notice-delivery", REMINDER_MS,
                    "Export notice delivery failed; current health remains on the status screen", failure);
        }
    }
}
