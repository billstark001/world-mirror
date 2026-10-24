package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.download.DownloadExportCoordinator.Trigger;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
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

/** Live commands may be coalesced; frozen revisions are independent, non-null durability bundles. */
sealed interface ExportWork permits ExportWork.Intent, ExportWork.Frozen {
    record Source(String id, String type) {
        static final Source UNKNOWN = new Source("unknown", "server");
        public Source {
            id = id == null || id.isBlank() ? "unknown" : id;
            type = type == null || type.isBlank() ? "server" : type;
        }
    }
    record Context(Trigger trigger, boolean notifyPlayer, Source source) {
        public Context { Objects.requireNonNull(trigger); Objects.requireNonNull(source); }
    }
    enum CapturePhase { REQUIRED, READY }
    enum EntityScope { LIVE, NONE }
    Context context();
    boolean hasTerrain();
    boolean hasEntities();
    ExportWork terrainOnly();

    record Intent(Context context, CapturePhase capture, EntityScope entityScope,
                  Optional<Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>>> containers,
                  Optional<WorldSettingsSnapshot> settings) implements ExportWork {
        public Intent {
            Objects.requireNonNull(context); Objects.requireNonNull(capture); Objects.requireNonNull(entityScope);
            containers = Objects.requireNonNull(containers).map(ExportWork::freezeMap);
            Objects.requireNonNull(settings);
        }
        static Intent live(Trigger trigger, boolean notifyPlayer) {
            return new Intent(new Context(trigger, notifyPlayer, Source.UNKNOWN), CapturePhase.REQUIRED,
                    EntityScope.LIVE, Optional.empty(), Optional.empty());
        }
        Intent ready() { return new Intent(context, CapturePhase.READY, entityScope, containers, settings); }
        Intent forSource(Source source) {
            return new Intent(new Context(context.trigger(), context.notifyPlayer(), source), capture, entityScope, containers, settings);
        }
        Intent withSettings(WorldSettingsSnapshot settings) { return new Intent(context, capture, entityScope, containers, Optional.of(settings)); }
        Intent withContainers(Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers) {
            return new Intent(context, capture, entityScope, Optional.of(containers), settings);
        }
        @Override public boolean hasTerrain() { return true; }
        @Override public boolean hasEntities() { return entityScope == EntityScope.LIVE; }
        @Override public Intent terrainOnly() { return new Intent(context, capture, EntityScope.NONE, containers, settings); }
    }

    record Frozen(Context context, ChunkListener.DirtySnapshot terrain,
                  Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entities,
                  Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers,
                  WorldSettingsSnapshot settings, long durableCaptureTime,
                  OptionalLong liveEntitySignal) implements ExportWork {
        public Frozen {
            Objects.requireNonNull(context); Objects.requireNonNull(terrain); Objects.requireNonNull(settings);
            entities = freezeMap(entities);
            containers = freezeMap(containers);
            Objects.requireNonNull(liveEntitySignal);
        }
        int terrainCount() { return terrain.chunks().values().stream().mapToInt(Map::size).sum(); }
        @Override public boolean hasTerrain() { return terrainCount() > 0; }
        @Override public boolean hasEntities() { return entities.values().stream().anyMatch(updates -> !updates.isEmpty()); }
        @Override public Frozen terrainOnly() { return new Frozen(context, terrain, Map.of(), containers, settings, durableCaptureTime, OptionalLong.empty()); }
        Frozen entitiesOnly() { return new Frozen(context, ChunkListener.snapshotOf(Map.of()), entities, Map.of(), settings, durableCaptureTime, OptionalLong.empty()); }
        Frozen retry() {
            return new Frozen(new Context(Trigger.RETRY, context.notifyPlayer(), context.source()), terrain, entities,
                    containers, settings, durableCaptureTime, OptionalLong.empty());
        }

        /** Release only exact receipts. Remaining revisions keep their original frozen lighting. */
        Frozen remaining(ChunkExporter.ExportResult result,
                         Map<ResourceKey<Level>, Map<ChunkPos, Long>> durableWritten, long durableTime) {
            Map<ResourceKey<Level>, Map<ChunkPos, Long>> receipts = new HashMap<>();
            result.settledRevisions().forEach((dimension, revisions) -> receipts.put(dimension, new HashMap<>(revisions)));
            durableWritten.forEach((dimension, revisions) -> receipts.computeIfAbsent(dimension, ignored -> new HashMap<>()).putAll(revisions));
            ChunkListener.DirtySnapshot remainingTerrain = terrain.withoutAcknowledged(receipts);
            Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> remainingEntities = new HashMap<>();
            entities.forEach((dimension, updates) -> {
                Map<ChunkPos, EntityTracker.ChunkUpdate> pending = new HashMap<>();
                updates.forEach((pos, update) -> {
                    if (!Long.valueOf(update.revision()).equals(result.entityRevisions().getOrDefault(dimension, Map.of()).get(pos))) pending.put(pos, update);
                });
                if (!pending.isEmpty()) remainingEntities.put(dimension, pending);
            });
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> remainingContainers = new HashMap<>();
            containers.forEach((dimension, overlays) -> {
                Map<BlockPos, CompoundTag> pending = new HashMap<>();
                overlays.forEach((pos, nbt) -> {
                    if (remainingTerrain.chunks().getOrDefault(dimension, Map.of()).containsKey(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4))) pending.put(pos, nbt);
                });
                if (!pending.isEmpty()) remainingContainers.put(dimension, pending);
            });
            return new Frozen(context, remainingTerrain, remainingEntities, remainingContainers, settings,
                    Math.max(durableCaptureTime, durableTime), OptionalLong.empty());
        }
    }

    private static <K, V> Map<ResourceKey<Level>, Map<K, V>> freezeMap(Map<ResourceKey<Level>, Map<K, V>> input) {
        Map<ResourceKey<Level>, Map<K, V>> result = new HashMap<>();
        input.forEach((dimension, values) -> result.put(dimension, Map.copyOf(values)));
        return Map.copyOf(result);
    }
}
