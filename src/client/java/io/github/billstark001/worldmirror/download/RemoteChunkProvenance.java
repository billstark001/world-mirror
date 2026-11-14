package io.github.billstark001.worldmirror.download;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Full chunk evidence for a client level that a proxy may have reused across a source change. */
public final class RemoteChunkProvenance {
    private static final int MAX_REFRESHED_CHUNKS = 16_384;
    private static final RemoteChunkProvenance LIVE = new RemoteChunkProvenance(MAX_REFRESHED_CHUNKS);

    static void restrict(ClientLevel world) {
        LIVE.enter(world);
    }

    static void reset() {
        LIVE.enter(null);
    }

    public static void refresh(ClientLevel world, ChunkPos pos) {
        if (world == null || LIVE.world != world || DownloadWorldChanges.isRespawnBoundary())
            return;
        int x = pos.getMinBlockX() >> 4;
        int z = pos.getMinBlockZ() >> 4;
        if (world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null)
            LIVE.record(world, pack(x, z));
    }

    public static void forget(ClientLevel world, ChunkPos pos) {
        if (LIVE.world == world)
            LIVE.refreshed.remove(pack(pos));
    }

    public static boolean canSample(ClientLevel world, ChunkPos pos) {
        return canSample(world, pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
    }

    static boolean canSample(ClientLevel world, int x, int z) {
        // Ordinary sessions never consult the per-coordinate set.
        return LIVE.world != world || world == null || LIVE.accepts(world, pack(x, z));
    }

    private static long pack(ChunkPos pos) {
        return pack(pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
    }

    private static long pack(int x, int z) {
        return Integer.toUnsignedLong(x) | (long) z << 32;
    }

    private final int limit;
    private final LongLinkedOpenHashSet refreshed = new LongLinkedOpenHashSet();
    private Object world;

    RemoteChunkProvenance(int limit) {
        if (limit < 1)
            throw new IllegalArgumentException("Expected a positive refresh limit");
        this.limit = limit;
    }

    void enter(Object world) {
        this.world = world;
        refreshed.clear();
    }

    void record(Object world, long slot) {
        if (world == null || this.world != world)
            return;
        refreshed.addAndMoveToLast(slot);
        if (refreshed.size() > limit)
            refreshed.removeFirstLong();
    }

    boolean accepts(Object world, long slot) {
        return this.world == null || this.world != world || refreshed.contains(slot);
    }
}
