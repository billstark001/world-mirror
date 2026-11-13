package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongConsumer;

/** Live commands may be coalesced; frozen revisions are independent, non-null durability bundles. */
sealed interface ExportWork permits ExportWork.Intent, ExportWork.Frozen {
    enum Trigger {
        MANUAL(false, 2),
        STOP(false, 3),
        RETRY(false, 1),
        PERIODIC(true, 0),
        RESOURCE_PRESSURE(true, 0),
        ADAPTIVE_HIGH_WATERMARK(true, 0),
        ADAPTIVE_MAX_LATENCY(true, 0);
        private final boolean automatic;
        private final int priority;
        Trigger(boolean automatic, int priority) {
            this.automatic = automatic;
            this.priority = priority;
        }
        boolean automatic() {
            return automatic;
        }
        int priority() {
            return priority;
        }
    }
    record Context(Trigger trigger, boolean notifyPlayer, CaptureSession session) {
        public Context {
            Objects.requireNonNull(trigger);
            Objects.requireNonNull(session);
        }
    }
    enum CapturePhase { REQUIRED, READY }
    enum EntityScope { LIVE, NONE }
    Context context();

    record Intent(Context context, CapturePhase capture, EntityScope entityScope, boolean captureNearby,
            Optional<Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>>> containers) implements ExportWork {
        public Intent {
            Objects.requireNonNull(context);
            Objects.requireNonNull(capture);
            Objects.requireNonNull(entityScope);
            containers = Objects.requireNonNull(containers).map(ExportWork::freezeMap);
        }
        Intent ready() {
            return new Intent(context, CapturePhase.READY, entityScope, captureNearby, containers);
        }
        Intent withContainers(Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers) {
            return new Intent(context, capture, entityScope, captureNearby, Optional.of(containers));
        }
        Intent terrainOnly() {
            return new Intent(context, capture, EntityScope.NONE, captureNearby, containers);
        }
    }

    record Frozen(Context context, ChunkListener.DirtySnapshot terrain,
            Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entities,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers, long durableCaptureTime,
            OptionalLong liveEntitySignal) implements ExportWork {
        public Frozen {
            Objects.requireNonNull(context);
            Objects.requireNonNull(terrain);
            entities = freezeMap(entities);
            containers = freezeMap(containers);
            Objects.requireNonNull(liveEntitySignal);
        }
        int terrainCount() {
            return terrain.chunks().values().stream().mapToInt(Map::size).sum();
        }
        boolean hasTerrain() {
            return terrainCount() > 0;
        }
        boolean hasEntities() {
            return entities.values().stream().anyMatch(updates -> !updates.isEmpty());
        }
        Frozen terrainOnly() {
            return new Frozen(
                    context, terrain, Map.of(), containers, durableCaptureTime, OptionalLong.empty());
        }
        void collectRevisions(LongConsumer terrainRevisions, LongConsumer entityRevisions) {
            terrain.chunks().values().forEach(
                    chunks -> chunks.values().forEach(chunk -> terrainRevisions.accept(chunk.revision())));
            entities.values().forEach(
                    chunks -> chunks.values().forEach(update -> entityRevisions.accept(update.revision())));
        }
        Frozen entitiesOnly() {
            return new Frozen(context, ChunkListener.snapshotOf(Map.of()), entities, Map.of(),
                    durableCaptureTime, OptionalLong.empty());
        }
        Frozen retry() {
            return new Frozen(new Context(Trigger.RETRY, context.notifyPlayer(), context.session()), terrain,
                    entities, containers, durableCaptureTime, OptionalLong.empty());
        }

        /** Release only exact receipts. Remaining revisions keep their original frozen lighting. */
        Frozen remaining(ChunkExporter.ExportResult result,
                Map<ResourceKey<Level>, Map<ChunkPos, Long>> durableWritten, long durableTime) {
            Map<ResourceKey<Level>, Map<ChunkPos, Long>> receipts = new HashMap<>();
            result.settledRevisions().forEach(
                    (dimension, revisions) -> receipts.put(dimension, new HashMap<>(revisions)));
            durableWritten.forEach((dimension, revisions)
                                           -> receipts.computeIfAbsent(dimension, ignored -> new HashMap<>())
                                                   .putAll(revisions));
            ChunkListener.DirtySnapshot remainingTerrain = terrain.withoutAcknowledged(receipts);
            Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> remainingEntities =
                    new HashMap<>();
            entities.forEach((dimension, updates) -> {
                Map<ChunkPos, EntityTracker.ChunkUpdate> pending = new HashMap<>();
                updates.forEach((pos, update) -> {
                    if (!Long.valueOf(update.revision())
                                    .equals(result.entityRevisions()
                                                    .getOrDefault(dimension, Map.of())
                                                    .get(pos)))
                        pending.put(pos, update);
                });
                if (!pending.isEmpty())
                    remainingEntities.put(dimension, pending);
            });
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> remainingContainers = new HashMap<>();
            containers.forEach((dimension, overlays) -> {
                Map<BlockPos, CompoundTag> pending = new HashMap<>();
                overlays.forEach((pos, nbt) -> {
                    if (remainingTerrain.chunks()
                                    .getOrDefault(dimension, Map.of())
                                    .containsKey(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4)))
                        pending.put(pos, nbt);
                });
                if (!pending.isEmpty())
                    remainingContainers.put(dimension, pending);
            });
            return new Frozen(context, remainingTerrain, remainingEntities, remainingContainers,
                    Math.max(durableCaptureTime, durableTime), OptionalLong.empty());
        }
    }

    private static <K, V> Map<ResourceKey<Level>, Map<K, V>> freezeMap(
            Map<ResourceKey<Level>, Map<K, V>> input) {
        Map<ResourceKey<Level>, Map<K, V>> result = new HashMap<>();
        input.forEach((dimension, values) -> result.put(dimension, Map.copyOf(values)));
        return Map.copyOf(result);
    }
}
