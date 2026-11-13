package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.io.NbtReadSupport;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Repairs the unversioned (v0) extra clock wrapper emitted by World Mirror 0.4.0. */
public final class LegacyWorldClocks {
    private static final Set<String> WRAPPER_FIELDS = Set.of("clocks");
    private static final Set<String> MIRROR_CLOCKS = Set.of("minecraft:overworld", "minecraft:the_end");

    private LegacyWorldClocks() {}

    public static void repair(Path world, UnaryOperator<CompoundTag> nativeCodec) throws IOException {
        Path file = world.resolve("data/minecraft/world_clocks.dat");
        if (!Files.isRegularFile(file)) return;
        CompoundTag root = NbtReadSupport.readWorldData(file);
        CompoundTag data = root.getCompoundOrEmpty("data");
        if (!data.keySet().equals(WRAPPER_FIELDS)) return;
        CompoundTag clocks = data.getCompoundOrEmpty("clocks");
        if (!clocks.keySet().equals(MIRROR_CLOCKS)) return;
        root.put("data", nativeCodec.apply(clocks));
        WorldStructureCreator.writeVerifiedWorldData(file, root);
    }
}
