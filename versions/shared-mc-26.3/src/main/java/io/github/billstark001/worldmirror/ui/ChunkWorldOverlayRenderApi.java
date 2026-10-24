package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.OptionalDouble;

/** Uses the native OIT submits when enabled, otherwise draws after classic transparency. */
public final class ChunkWorldOverlayRenderApi {
    private static StagedVertexBuffer buffer;
    private static StagedVertexBuffer.Draw fill;
    private static StagedVertexBuffer.Draw edges;
    private static final ChunkWorldOverlayGeometry.UploadState uploadState = new ChunkWorldOverlayGeometry.UploadState();

    private ChunkWorldOverlayRenderApi() {}

    public static void renderLate(Vec3 camera) {
        var client = Minecraft.getInstance();
        if (client.gameRenderer.useImprovedTransparency()) return;
        var overlay = ChunkWorldOverlay.snapshot();
        if (overlay.isEmpty()) {
            discardMesh();
            return;
        }
        if (buffer == null) buffer = new StagedVertexBuffer(() -> "World Mirror overlay", 64 * 1024);
        var layer = RenderTypes.debugFilledBox();
        if (uploadState.needsUpload(overlay, camera)) {
            discardMesh();
            var pose = new PoseStack().last();
            try {
                fill = buffer.appendDraw(layer.format(), layer.primitiveTopology(),
                    RenderSystem.getProjectionType().vertexSorting());
                ChunkWorldOverlayGeometry.renderFilled(pose, buffer.getVertexBuilder(fill), overlay, camera);
                edges = buffer.appendDraw(layer.format(), layer.primitiveTopology());
                ChunkWorldOverlayGeometry.renderTopEdges(pose, buffer.getVertexBuilder(edges), overlay, camera);
                buffer.upload();
                uploadState.uploaded(overlay, camera);
            } catch (RuntimeException | Error failure) {
                discardMesh();
                throw failure;
            }
        }
        var prepared = layer.prepare();
        var fillInfo = buffer.getExecuteInfo(fill);
        var edgeInfo = buffer.getExecuteInfo(edges);
        RenderSystem.resizeAllAutoStorageIndexBuffers();
        var target = client.gameRenderer.mainRenderTarget();
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "World Mirror overlay", target.getColorTextureView(), Optional.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            if (fillInfo != null) prepared.drawFromBuffer(fillInfo, pass);
            if (edgeInfo != null) prepared.drawFromBuffer(edgeInfo, pass);
        }
    }

    private static void discardMesh() {
        if (buffer != null && (fill != null || edges != null)) buffer.endFrame();
        fill = null;
        edges = null;
        uploadState.clear();
    }

    public static void close() {
        discardMesh();
        if (buffer != null) {
            buffer.close();
            buffer = null;
        }
    }
}
