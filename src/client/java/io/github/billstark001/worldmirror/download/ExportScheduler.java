package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.ExportWork.*;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Client-thread export scheduling. Shutdown explicitly transfers ownership to the export worker. */
final class ExportScheduler {
    enum Dispatch { STARTED, IDLE, BUSY, WAITING_CAPTURE, WAITING_SOURCE, WAITING_WORLD, PREPARATION_FAILED, CLOSED }
    final Pending pending = new Pending();
    private final Map<Source, Integer> retryAttempts = new HashMap<>();
    private ModConfig.DownloadPipelineMode mode = ModConfig.DownloadPipelineMode.STABLE_PERIODIC;
    private long lastStarted, nextAttempt = Long.MIN_VALUE, lastPressureAttempt = Long.MIN_VALUE;
    private long maximumLatencyMs = 30_000, adaptiveCooldownMs = 10_000;
    private int highWatermark = 512;
    private boolean highWatermarkArmed = true;

    ModConfig.DownloadPipelineMode mode() { return mode; }
    void configure(ModConfig config) {
        maximumLatencyMs = (long) config.syncIntervalSeconds * 1_000;
        adaptiveCooldownMs = (long) config.performance.adaptiveExportCooldownSeconds * 1_000;
        highWatermark = Math.max(1, config.performance.adaptiveDirtyHighWatermark);
    }
    void reset(ModConfig config, long now) {
        configure(config);
        mode = config.pipelineMode;
        lastStarted = now;
        nextAttempt = lastPressureAttempt = Long.MIN_VALUE;
        highWatermarkArmed = true;
    }

    Trigger automatic(long now, int dirty, boolean hasWork, boolean resourcePressure) {
        if (dirty <= Math.max(1, highWatermark / 2)) highWatermarkArmed = true;
        if (!hasWork || (nextAttempt != Long.MIN_VALUE && now - nextAttempt < 0)) return null;
        if (resourcePressure && (lastPressureAttempt == Long.MIN_VALUE || now - lastPressureAttempt >= 5_000)) {
            return Trigger.RESOURCE_PRESSURE;
        }
        long elapsed = now - lastStarted;
        if (mode == ModConfig.DownloadPipelineMode.EXPERIMENTAL_ADAPTIVE
                && ((highWatermarkArmed && dirty >= highWatermark) || dirty >= (long) highWatermark * 4)
                && elapsed >= adaptiveCooldownMs) return Trigger.ADAPTIVE_HIGH_WATERMARK;
        if (elapsed < maximumLatencyMs) return null;
        return mode == ModConfig.DownloadPipelineMode.STABLE_PERIODIC ? Trigger.PERIODIC : Trigger.ADAPTIVE_MAX_LATENCY;
    }

    void accepted(long now, int dirty) {
        lastStarted = now;
        nextAttempt = Long.MIN_VALUE;
        if (dirty >= highWatermark) highWatermarkArmed = false;
    }
    void attempted(Trigger trigger, Dispatch result, long now) {
        if (trigger == Trigger.RESOURCE_PRESSURE) lastPressureAttempt = now;
        if (trigger.automatic() && result != Dispatch.STARTED) nextAttempt = now + 1_000;
    }
    void completed(Frozen work, boolean successful, long now) {
        Source source = work.context().source();
        if (successful) {
            if (!pending.hasEntitiesForSource(source)) retryAttempts.remove(source);
        } else {
            int attempt = retryAttempts.merge(source, 1, Integer::sum);
            long delayMs = Math.min(900L, 10L << Math.min(7, attempt - 1)) * 1_000;
            pending.retry(work.retry(), now + delayMs);
        }
    }

    private static Intent mergeIntents(Intent previous, Intent next) {
        Trigger trigger = next.context().trigger().priority() >= previous.context().trigger().priority()
                ? next.context().trigger() : previous.context().trigger();
        Context context = new Context(trigger, previous.context().notifyPlayer() || next.context().notifyPlayer(), next.context().source());
        CapturePhase phase = previous.capture() == CapturePhase.READY && next.capture() == CapturePhase.READY
                ? CapturePhase.READY : CapturePhase.REQUIRED;
        return new Intent(context, phase, next.entityScope(), previous.captureNearby() || next.captureNearby(),
                next.containers().isPresent() ? next.containers() : previous.containers(), next.settings());
    }
    /** Source FIFO is enforced independently for terrain and entity revisions; owned by this scheduler. */
    static final class Pending {
        private static final class Scheduled {
            private Frozen work;
            private long readyAtMs;
            Scheduled(Frozen work, long readyAtMs) { this.work = work; this.readyAtMs = readyAtMs; }
        }
        private final ArrayDeque<Scheduled> frozen = new ArrayDeque<>();
        private Intent live;
        void enqueue(Frozen work) { frozen.addLast(new Scheduled(work, Long.MIN_VALUE)); }
        boolean request(Intent intent) {
            boolean coalesced = live != null && live.context().source().equals(intent.context().source());
            live = coalesced ? mergeIntents(live, intent) : intent;
            return coalesced;
        }
        ExportWork poll(boolean capturesPending, long nowMs) {
            Set<Source> blockedTerrain = new HashSet<>(), blockedEntities = new HashSet<>();
            Iterator<Scheduled> iterator = frozen.iterator();
            while (iterator.hasNext()) {
                Scheduled scheduled = iterator.next();
                Frozen work = scheduled.work;
                Source source = work.context().source();
                boolean ready = scheduled.readyAtMs == Long.MIN_VALUE || nowMs - scheduled.readyAtMs >= 0;
                if (ready && !(work.hasTerrain() && blockedTerrain.contains(source))) {
                    if (work.hasEntities() && blockedEntities.contains(source)) {
                        if (work.hasTerrain()) { scheduled.work = work.entitiesOnly(); return work.terrainOnly(); }
                    } else { iterator.remove(); return work; }
                }
                if (work.hasTerrain()) blockedTerrain.add(source);
                if (work.hasEntities()) blockedEntities.add(source);
            }
            if (live == null || blockedTerrain.contains(live.context().source())
                    || (capturesPending && live.captureNearby() && live.capture() == CapturePhase.READY)) return null;
            Intent work = live;
            live = null;
            return blockedEntities.contains(work.context().source()) ? work.terrainOnly() : work;
        }
        void retry(Frozen work, long readyAtMs) { frozen.addFirst(new Scheduled(work, readyAtMs)); }
        boolean hasTerrainForSource(Source source) { return frozen.stream().anyMatch(item -> source.equals(item.work.context().source()) && item.work.hasTerrain()); }
        boolean hasEntitiesForSource(Source source) { return frozen.stream().anyMatch(item -> source.equals(item.work.context().source()) && item.work.hasEntities()); }
        void clearLive() { live = null; }
        void prepareForShutdown() { frozen.forEach(item -> item.readyAtMs = Long.MIN_VALUE); clearLive(); }
        int frozenCount() { return frozen.size(); }
        void collectRetained(Set<Long> terrain, Set<Long> entities) {
            frozen.forEach(item -> item.work.collectRevisions(terrain, entities));
        }
        void retryNow(Source source) {
            frozen.stream().filter(item -> item.work.context().source().equals(source))
                    .forEach(item -> item.readyAtMs = Long.MIN_VALUE);
        }
    }

}
