package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.download.SeedConversion.Progress;
import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.blending.BlendingData;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

import static io.github.billstark001.worldmirror.download.SeedTerrainFiles.chunkX;
import static io.github.billstark001.worldmirror.download.SeedTerrainFiles.chunkZ;

/** Native blending marker ownership and the bounded regeneration search. */
public final class SeedTerrainBlending {
    // BlendingData only recognizes these vanilla blocks as surfaces for its height samples.
    private static final Set<String> BLENDING_SURFACES =
            Set.of("minecraft:podzol", "minecraft:gravel", "minecraft:grass_block", "minecraft:stone",
                    "minecraft:coarse_dirt", "minecraft:sand", "minecraft:red_sand", "minecraft:mycelium",
                    "minecraft:snow_block", "minecraft:terracotta", "minecraft:dirt");
    private static final int[] BLENDING_EDGE_SAMPLES = {0, 4, 8, 12, 15};
    // Vanilla height samples reach roughly seven chunks. Axial and diagonal
    // steps approximate that radius while keeping protected gaps out of the rewrite.
    private static final int BLENDING_REACH = 70;

    private SeedTerrainBlending() {}

    private record Distance(ChunkPos pos, int cost) {}

    static Set<ChunkPos> regenerationBand(Set<ChunkPos> donors, Set<ChunkPos> eligible, Progress progress) {
        if (donors.isEmpty() || eligible.isEmpty())
            return Set.of();
        Map<ChunkPos, Integer> distances = new HashMap<>();
        var frontier = new PriorityQueue<Distance>(java.util.Comparator.comparingInt(Distance::cost));
        for (ChunkPos donor : donors) {
            progress.checkpoint();
            if (hasGap(donor, donors))
                frontier.add(new Distance(donor, 0));
        }
        while (!frontier.isEmpty()) {
            progress.checkpoint();
            Distance from = frontier.remove();
            if (from.cost() > distances.getOrDefault(from.pos(), from.cost()))
                continue;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0)
                        continue;
                    int cost = from.cost() + (dx == 0 || dz == 0 ? 10 : 14);
                    if (cost > BLENDING_REACH)
                        continue;
                    var neighbor = new ChunkPos(chunkX(from.pos()) + dx, chunkZ(from.pos()) + dz);
                    if (!donors.contains(neighbor) && eligible.contains(neighbor)
                            && cost < distances.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                        distances.put(neighbor, cost);
                        frontier.add(new Distance(neighbor, cost));
                    }
                }
            }
        }
        return distances.keySet();
    }

    static boolean hasGap(ChunkPos pos, Set<ChunkPos> present) {
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++)
                if ((dx != 0 || dz != 0)
                        && !present.contains(new ChunkPos(chunkX(pos) + dx, chunkZ(pos) + dz)))
                    return true;
        return false;
    }

    static boolean canDonateBlend(CompoundTag chunk) {
        if (!chunk.getStringOr("Status", "").matches("(minecraft:)?full")
                || chunk.getIntOr("DataVersion", 0)
                        > SharedConstants.getCurrentVersion().dataVersion().version())
            return false;
        int minSection = SeedTerrainDigest.BLENDING_MIN_SECTION;
        int maxSection = SeedTerrainDigest.BLENDING_MAX_SECTION;
        if (chunk.contains(SeedTerrainDigest.BLENDING_DATA)) {
            var data = BlendingData.Packed.CODEC
                               .parse(NbtOps.INSTANCE, chunk.get(SeedTerrainDigest.BLENDING_DATA))
                               .result()
                               .orElse(null);
            if (data == null || data.minSection() < minSection || data.maxSection() > maxSection
                    || data.minSection() >= data.maxSection())
                return false;
            minSection = data.minSection();
            maxSection = data.maxSection();
        }
        boolean hasSurface = false;
        for (Tag value : chunk.getListOrEmpty("sections")) {
            if (!(value instanceof CompoundTag section))
                return false;
            int y = section.getIntOr("Y", Integer.MIN_VALUE);
            if (y < minSection || y >= maxSection)
                continue;
            var states = section.getCompoundOrEmpty("block_states");
            var palette = states.getListOrEmpty("palette");
            if (!section.contains("block_states"))
                continue;
            if (palette.isEmpty())
                return false;
            int bits = Math.max(4, TerrainBitStorage.bits(palette.size()));
            var data = states.getLongArray("data").orElse(null);
            if (palette.size() == 1) {
                if (states.contains("data"))
                    return false;
                hasSurface |= BLENDING_SURFACES.contains(blockId(palette.getFirst()));
                continue;
            }
            if (data == null || data.length != TerrainBitStorage.wordCount(4096, bits))
                return false;
            hasSurface |= containsSurfaceSample(palette, data, bits);
        }
        return hasSurface;
    }

    private static boolean containsSurfaceSample(ListTag palette, long[] data, int bits) {
        for (int dy = 0; dy < 16; dy++) {
            int layer = dy * 256;
            for (int offset : BLENDING_EDGE_SAMPLES) {
                if (hasBlendingSurface(palette, data, bits, layer + offset)
                        || hasBlendingSurface(palette, data, bits, layer + 15 * 16 + offset)
                        || hasBlendingSurface(palette, data, bits, layer + offset * 16)
                        || hasBlendingSurface(palette, data, bits, layer + offset * 16 + 15))
                    return true;
            }
        }
        return false;
    }

    private static boolean hasBlendingSurface(ListTag palette, long[] data, int bits, int position) {
        int index = TerrainBitStorage.cell(data, bits, position);
        return index < palette.size() && BLENDING_SURFACES.contains(blockId(palette.get(index)));
    }

    private static String blockId(Tag state) {
        return state instanceof CompoundTag compound ? compound.getStringOr("Name", "")
                                                     : state.asString().orElse("");
    }

    static boolean hasOwnedBlend(CompoundTag chunk) {
        String owner = chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS)
                               .getStringOr(SeedTerrainDigest.BLENDING_ATTACHMENT, "");
        try {
            return UUID.fromString(owner).toString().equals(owner);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Whether this chunk's blending marker belongs to the current seed epoch. */
    public static boolean ownsBlend(CompoundTag chunk, UUID epoch) {
        return chunk != null
                && epoch.toString().equals(chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS)
                                .getStringOr(SeedTerrainDigest.BLENDING_ATTACHMENT, ""));
    }

    public static void markBlend(CompoundTag chunk, UUID epoch) {
        var data = new CompoundTag();
        data.putInt("min_section", SeedTerrainDigest.BLENDING_MIN_SECTION);
        data.putInt("max_section", SeedTerrainDigest.BLENDING_MAX_SECTION);
        chunk.put(SeedTerrainDigest.BLENDING_DATA, data);
        var attachments = chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS);
        attachments.putString(SeedTerrainDigest.BLENDING_ATTACHMENT, epoch.toString());
        chunk.put(SeedTerrainDigest.ATTACHMENTS, attachments);
    }

    static void removeBlend(CompoundTag chunk) {
        var attachments = chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS);
        attachments.remove(SeedTerrainDigest.BLENDING_ATTACHMENT);
        if (attachments.isEmpty())
            chunk.remove(SeedTerrainDigest.ATTACHMENTS);
        chunk.remove(SeedTerrainDigest.BLENDING_DATA);
    }
}
