package io.github.billstark001.worldmirror.io;

#if MC_VERSION >= 260102
#if MC_VERSION < 260300
import com.mojang.serialization.Lifecycle;
#endif
import io.github.billstark001.worldmirror.legacy.LegacyWorldClocks;
import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.TagKey;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Native saved-data clock encoding and repair for the 26.x save layout. */
final class WorldClockData {
#if MC_VERSION >= 260300
    private static final WorldClockLookup WORLD_CLOCK_LOOKUP = new WorldClockLookup();
    private static final Map<ResourceKey<WorldClock>, Holder.Reference<WorldClock>> WORLD_CLOCKS = Map.of(
            WorldClocks.OVERWORLD,
            Holder.Reference.createStandAlone(WORLD_CLOCK_LOOKUP, WorldClocks.OVERWORLD), WorldClocks.THE_END,
            Holder.Reference.createStandAlone(WORLD_CLOCK_LOOKUP, WorldClocks.THE_END));
    private static final RegistryOps<Tag> WORLD_CLOCK_OPS =
            RegistryOps.create(NbtOps.INSTANCE, new RegistryOps.RegistryInfoLookup() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> Optional<HolderGetter<T>> lookup(
                        ResourceKey<? extends Registry<? extends T>> registryKey) {
                    if (!Registries.WORLD_CLOCK.equals(registryKey))
                        return Optional.empty();
                    return Optional.of((HolderGetter<T>) (HolderGetter<?>) WORLD_CLOCK_LOOKUP);
                }
            });

    private static final class WorldClockLookup implements HolderGetter<WorldClock>, HolderOwner<WorldClock> {
        @Override
        public Optional<Holder.Reference<WorldClock>> get(ResourceKey<WorldClock> resourceKey) {
            return Optional.ofNullable(WORLD_CLOCKS.get(resourceKey));
        }

        @Override
        public Optional<HolderSet.Named<WorldClock>> get(TagKey<WorldClock> tagKey) {
            return Optional.empty();
        }
    }

#else
    private static final HolderOwner<WorldClock> WORLD_CLOCK_OWNER = new HolderOwner<>() {};
    private static final Map<ResourceKey<WorldClock>, Holder.Reference<WorldClock>> WORLD_CLOCKS = Map.of(
            WorldClocks.OVERWORLD,
            Holder.Reference.createStandAlone(WORLD_CLOCK_OWNER, WorldClocks.OVERWORLD), WorldClocks.THE_END,
            Holder.Reference.createStandAlone(WORLD_CLOCK_OWNER, WorldClocks.THE_END));
    private static final HolderGetter<WorldClock> WORLD_CLOCK_GETTER = new HolderGetter<>() {
        @Override
        public Optional<Holder.Reference<WorldClock>> get(ResourceKey<WorldClock> resourceKey) {
            return Optional.ofNullable(WORLD_CLOCKS.get(resourceKey));
        }

        @Override
        public Optional<HolderSet.Named<WorldClock>> get(TagKey<WorldClock> tagKey) {
            return Optional.empty();
        }
    };
    private static final RegistryOps.RegistryInfo<WorldClock> WORLD_CLOCK_REGISTRY =
            new RegistryOps.RegistryInfo<>(WORLD_CLOCK_OWNER, WORLD_CLOCK_GETTER, Lifecycle.stable());
    private static final RegistryOps<Tag> WORLD_CLOCK_OPS =
            RegistryOps.create(NbtOps.INSTANCE, new RegistryOps.RegistryInfoLookup() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> Optional<RegistryOps.RegistryInfo<T>> lookup(
                        ResourceKey<? extends Registry<? extends T>> registryKey) {
                    if (!Registries.WORLD_CLOCK.equals(registryKey))
                        return Optional.empty();
                    return Optional.of(
                            (RegistryOps.RegistryInfo<T>) (RegistryOps.RegistryInfo<?>) WORLD_CLOCK_REGISTRY);
                }
            });

#endif
    private WorldClockData() {}

    static void repairOwnedSavedData(Path worldFolder) throws Exception {
        FileProof.requirePlainPath(worldFolder, worldFolder.resolve("data/minecraft/world_clocks.dat"));
        LegacyWorldClocks.repair(worldFolder, WorldClockData::roundTripWorldClocksData);
    }

    static CompoundTag createWorldClocksData(long dayTime) {
        CompoundTag states = new CompoundTag();
        states.put("minecraft:overworld", createClockState(dayTime));
        states.put("minecraft:the_end", createClockState(dayTime));
        return roundTripWorldClocksData(states);
    }

    private static CompoundTag createClockState(long totalTicks) {
        CompoundTag state = new CompoundTag();
        state.putLong("total_ticks", totalTicks);
        state.putFloat("partial_tick", 0.0F);
        state.putFloat("rate", 1.0F);
        state.putBoolean("paused", false);
        return state;
    }

    static CompoundTag roundTripWorldClocksData(CompoundTag data) {
        PackedClockStates decoded = PackedClockStates.CODEC.parse(WORLD_CLOCK_OPS, data).getOrThrow();
        Tag encoded = PackedClockStates.CODEC.encodeStart(WORLD_CLOCK_OPS, decoded).getOrThrow();
        if (!(encoded instanceof CompoundTag compound)) {
            throw new IllegalStateException("Packed clock codec did not produce a compound tag");
        }
        return compound;
    }
}
#endif
