package net.billstark001.worlddownloader.io;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.storage.WriteView;
import org.jspecify.annotations.Nullable;

/**
 * Implementation class that bridges WriteView operations to NbtCompound.
 */
public class NbtWriteView implements WriteView {
    private final NbtCompound compound;
    private final DynamicOps<NbtElement> ops;

    /**
     * @param compound Target NBT compound tag
     * @param ops      Dynamic operations' provider. If you need registry context during serialization (e.g., enchantments, items), pass RegistryOps
     */
    public NbtWriteView(NbtCompound compound, DynamicOps<NbtElement> ops) {
        this.compound = compound;
        this.ops = ops;
    }

    /**
     * Default constructor that uses native NbtOps (without registry context).
     */
    public NbtWriteView() {
        this(new NbtCompound(), NbtOps.INSTANCE);
    }

    /**
     * Gets the final NbtCompound containing the written data.
     */
    public NbtCompound getCompound() {
        return this.compound;
    }

    @Override
    public <T> void put(String key, Codec<T> codec, T value) {
        codec.encodeStart(this.ops, value)
                .resultOrPartial(err -> { /* Error logging can be added here */ })
                .ifPresent(element -> this.compound.put(key, element));
    }

    @Override
    public <T> void putNullable(String key, Codec<T> codec, @Nullable T value) {
        if (value != null) {
            this.put(key, codec, value);
        }
    }

    @Override
    @Deprecated
    public <T> void put(MapCodec<T> codec, T value) {
        // MapCodec generates key-value pairs. We need to build them as a Compound and merge into the current compound
        codec.encode(value, this.ops, this.ops.mapBuilder()).build(this.ops.empty())
                .resultOrPartial(err -> {})
                .ifPresent(element -> {
                    if (element instanceof NbtCompound mapCompound) {
                        this.compound.copyFrom(mapCompound);
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
    public WriteView get(String key) {
        NbtCompound child = new NbtCompound();
        this.compound.put(key, child);
        return new NbtWriteView(child, this.ops);
    }

    @Override
    public ListView getList(String key) {
        NbtList list = new NbtList();
        this.compound.put(key, list);
        return new NbtListView(list, this.ops);
    }

    @Override
    public <T> ListAppender<T> getListAppender(String key, Codec<T> codec) {
        NbtList list = new NbtList();
        this.compound.put(key, list);
        return new NbtListAppender<>(list, codec, this.ops);
    }

    @Override
    public void remove(String key) {
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
            NbtList list,
            DynamicOps<NbtElement> ops
    ) implements ListView {

        @Override
            public WriteView add() {
                NbtCompound child = new NbtCompound();
                this.list.add(child);
                return new NbtWriteView(child, this.ops);
            }

            @Override
            public void removeLast() {
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
            NbtList list,
            Codec<T> codec,
            DynamicOps<NbtElement> ops
    ) implements ListAppender<T> {

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