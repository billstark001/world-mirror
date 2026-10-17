package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/** Staged drawing adapter; executes immediately after world transparency composition. */
public final class ChunkWorldOverlayRenderApi {
    private static StagedVertexBuffer buffer;

    private ChunkWorldOverlayRenderApi() {}

    public static void renderLate(Vec3 camera) {
        var overlay = ChunkWorldOverlay.snapshot();
        if (overlay.isEmpty()) return;
        if (buffer == null) buffer = new StagedVertexBuffer(() -> "World Mirror overlay", 64 * 1024);
        var layer = RenderTypes.debugFilledBox();
        var pose = new PoseStack().last();
        try {
            var fill = buffer.appendDraw(layer.format(), layer.primitiveTopology(),
                    RenderSystem.getProjectionType().vertexSorting());
            ChunkWorldOverlayGeometry.renderFilled(pose, buffer.getVertexBuilder(fill), overlay, camera);
            var edges = buffer.appendDraw(layer.format(), layer.primitiveTopology());
            ChunkWorldOverlayGeometry.renderTopEdges(pose, buffer.getVertexBuilder(edges), overlay, camera);
            buffer.upload();
            var prepared = layer.prepare();
            var fillInfo = buffer.getExecuteInfo(fill);
            if (fillInfo != null) prepared.drawFromBuffer(fillInfo);
            var edgeInfo = buffer.getExecuteInfo(edges);
            if (edgeInfo != null) prepared.drawFromBuffer(edgeInfo);
        } finally {
            buffer.endFrame();
        }
    }

    public static void close() {
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }
}
