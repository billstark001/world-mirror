package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/** Immediate drawing adapter for the pre-staged-buffer renderer. */
public final class ChunkWorldOverlayRenderApi {
    private static ByteBufferBuilder allocator;
    private static MultiBufferSource.BufferSource buffers;

    private ChunkWorldOverlayRenderApi() {}

    public static void renderLate(Vec3 camera) {
        var overlay = ChunkWorldOverlay.snapshot();
        if (overlay.isEmpty()) return;
        if (buffers == null) {
            allocator = new ByteBufferBuilder(64 * 1024);
            buffers = MultiBufferSource.immediate(allocator);
        }
        var layer = RenderTypes.debugFilledBox();
        var pose = new PoseStack().last();
        ChunkWorldOverlayGeometry.renderFilled(pose, buffers.getBuffer(layer), overlay, camera);
        buffers.endBatch(layer);
        // Flush separately so quad sorting cannot put a large fill over its grid.
        ChunkWorldOverlayGeometry.renderTopEdges(pose, buffers.getBuffer(layer), overlay, camera);
        buffers.endBatch(layer);
    }

    public static void close() {
        if (allocator != null) {
            allocator.close();
            allocator = null;
            buffers = null;
        }
    }
}
