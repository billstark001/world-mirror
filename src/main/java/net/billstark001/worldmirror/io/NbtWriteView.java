package net.billstark001.worldmirror.io;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Implementation class that bridges WriteView operations to NbtCompound.
 */
public class NbtWriteView implements ValueOutput {
    private final CompoundTag compound;
    private final DynamicOps<Tag> ops;

    /**
     * @param compound Target NBT compound tag
     * @param ops      Dynamic operations' provider. If you need registry context during serialization (e.g., enchantments, items), pass RegistryOps
     */
    public NbtWriteView(CompoundTag compound, DynamicOps<Tag> ops) {
        this.compound = compound;
        this.ops = ops;
    }

    /**
     * Default constructor that uses native NbtOps (without registry context).
     */
    public NbtWriteView() {
        this(new CompoundTag(), NbtOps.INSTANCE);
    }

    /**
     * Gets the final NbtCompound containing the written data.
     */
    public CompoundTag getCompound() {
        return this.compound;
    }

    @Override
    public <T> void store(String key, Codec<T> codec, T value) {
        codec.encodeStart(this.ops, value)
                .resultOrPartial(err -> { /* Error logging can be added here */ })
                .ifPresent(element -> this.compound.put(key, element));
    }

    @Override
    public <T> void storeNullable(String key, Codec<T> codec, @Nullable T value) {
        if (value != null) {
            this.store(key, codec, value);
        }
    }

    @Override
    @Deprecated
    public <T> void store(MapCodec<T> codec, T value) {
        // MapCodec generates key-value pairs. We need to build them as a Compound and merge into the current compound
        codec.encode(value, this.ops, this.ops.mapBuilder()).build(this.ops.empty())
                .resultOrPartial(err -> {})
                .ifPresent(element -> {
                    if (element instanceof CompoundTag mapCompound) {
                        this.compound.merge(mapCompound);
                    }
                });
    }

    @Override
    public void putBoolean(String key, boolean value) {
        this.compound.putBoolean(key, value);
    }

    @Override
    public void putByte(String key, byte value) {
        this.compound.putByte(key, value);
    }

    @Override
    public void putShort(String key, short value) {
        this.compound.putShort(key, value);
    }

    @Override
    public void putInt(String key, int value) {
        this.compound.putInt(key, value);
    }

    @Override
    public void putLong(String key, long value) {
        this.compound.putLong(key, value);
    }

    @Override
    public void putFloat(String key, float value) {
        this.compound.putFloat(key, value);
    }

    @Override
    public void putDouble(String key, double value) {
        this.compound.putDouble(key, value);
    }

    @Override
    public void putString(String key, String value) {
        this.compound.putString(key, value);
    }

    @Override
    public void putIntArray(String key, int[] value) {
        this.compound.putIntArray(key, value);
    }

    @Override
    public ValueOutput child(String key) {
        CompoundTag child = new CompoundTag();
        this.compound.put(key, child);
        return new NbtWriteView(child, this.ops);
    }

    @Override
    public ValueOutputList childrenList(String key) {
        ListTag list = new ListTag();
        this.compound.put(key, list);
        return new NbtListView(list, this.ops);
    }

    @Override
    public <T> TypedOutputList<T> list(String key, Codec<T> codec) {
        ListTag list = new ListTag();
        this.compound.put(key, list);
        return new NbtListAppender<>(list, codec, this.ops);
    }

    @Override
    public void discard(String key) {
        this.compound.remove(key);
    }

    @Override
    public boolean isEmpty() {
        return this.compound.isEmpty();
    }

    // ==========================================
    // Inner Classes: List handling logic
    // ==========================================

    private record NbtListView(
            ListTag list,
            DynamicOps<Tag> ops
    ) implements ValueOutputList {

        @Override
            public ValueOutput addChild() {
                CompoundTag child = new CompoundTag();
                this.list.add(child);
                return new NbtWriteView(child, this.ops);
            }

            @Override
            public void discardLast() {
                if (!this.list.isEmpty()) {
                    this.list.removeLast();
                }
            }

            @Override
            public boolean isEmpty() {
                return this.list.isEmpty();
            }
        }

    private record NbtListAppender<T>(
            ListTag list,
            Codec<T> codec,
            DynamicOps<Tag> ops
    ) implements TypedOutputList<T> {

        @Override
            public void add(T value) {
                this.codec.encodeStart(this.ops, value)
                        .resultOrPartial(err -> {
                        })
                        .ifPresent(this.list::add);
            }

            @Override
            public boolean isEmpty() {
                return this.list.isEmpty();
            }
        }
}