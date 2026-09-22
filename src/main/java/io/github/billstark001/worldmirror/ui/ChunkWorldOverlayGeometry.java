package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/** Version-independent vertex emission for the World Mirror chunk boxes. */
public final class ChunkWorldOverlayGeometry {
    private static final float LINE_WIDTH = 2.0F;
    private static final int FILL_ALPHA = 72;
    private static final int LINE_ALPHA = 220;

    private ChunkWorldOverlayGeometry() {}

    public static void renderFilled(PoseStack.Pose pose,
                                    VertexConsumer consumer,
                                    ChunkWorldOverlay.OverlaySnapshot snapshot,
                                    Vec3 camera) {
        for (ChunkWorldOverlay.OverlayChunk chunk : snapshot.chunks()) {
            float x1 = relative(chunk.chunkX() * 16.0D, camera.x);
            float z1 = relative(chunk.chunkZ() * 16.0D, camera.z);
            float x2 = relative((chunk.chunkX() + 1) * 16.0D, camera.x);
            float z2 = relative((chunk.chunkZ() + 1) * 16.0D, camera.z);
            float y1 = relative(chunk.y(), camera.y);
            float y2 = relative(chunk.y() + 1.0D, camera.y);
            int color = withAlpha(chunk.color(), FILL_ALPHA);

            quad(pose, consumer, x1, y1, z1, x2, y1, z2, color);
            quad(pose, consumer, x1, y2, z2, x2, y2, z1, color);
            quad(pose, consumer, x1, y1, z1, x1, y2, z2, color);
            quad(pose, consumer, x2, y1, z2, x2, y2, z1, color);
            quad(pose, consumer, x1, y1, z2, x2, y2, z2, color);
            quad(pose, consumer, x2, y1, z1, x1, y2, z1, color);
        }
    }

    public static void renderOutlined(PoseStack.Pose pose,
                                       VertexConsumer consumer,
                                       ChunkWorldOverlay.OverlaySnapshot snapshot,
                                       Vec3 camera) {
        for (ChunkWorldOverlay.OverlayChunk chunk : snapshot.chunks()) {
            float x1 = relative(chunk.chunkX() * 16.0D, camera.x);
            float z1 = relative(chunk.chunkZ() * 16.0D, camera.z);
            float x2 = relative((chunk.chunkX() + 1) * 16.0D, camera.x);
            float z2 = relative((chunk.chunkZ() + 1) * 16.0D, camera.z);
            float y1 = relative(chunk.y(), camera.y);
            float y2 = relative(chunk.y() + 1.0D, camera.y);
            int color = withAlpha(chunk.color(), LINE_ALPHA);

            line(pose, consumer, x1, y1, z1, x2, y1, z1, color);
            line(pose, consumer, x2, y1, z1, x2, y1, z2, color);
            line(pose, consumer, x2, y1, z2, x1, y1, z2, color);
            line(pose, consumer, x1, y1, z2, x1, y1, z1, color);
            line(pose, consumer, x1, y2, z1, x2, y2, z1, color);
            line(pose, consumer, x2, y2, z1, x2, y2, z2, color);
            line(pose, consumer, x2, y2, z2, x1, y2, z2, color);
            line(pose, consumer, x1, y2, z2, x1, y2, z1, color);
            line(pose, consumer, x1, y1, z1, x1, y2, z1, color);
            line(pose, consumer, x2, y1, z1, x2, y2, z1, color);
            line(pose, consumer, x2, y1, z2, x2, y2, z2, color);
            line(pose, consumer, x1, y1, z2, x1, y2, z2, color);
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer consumer,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2, int color) {
        vertex(pose, consumer, x1, y1, z1, color);
        vertex(pose, consumer, x2, y1, z1, color);
        vertex(pose, consumer, x2, y2, z2, color);
        vertex(pose, consumer, x1, y2, z2, color);
    }

    private static void line(PoseStack.Pose pose, VertexConsumer consumer,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 0.0F) return;
        float nx = dx / length;
        float ny = dy / length;
        float nz = dz / length;
        vertex(pose, consumer, x1, y1, z1, color).setNormal(pose, nx, ny, nz)
                .setLineWidth(LINE_WIDTH);
        vertex(pose, consumer, x2, y2, z2, color).setNormal(pose, nx, ny, nz)
                .setLineWidth(LINE_WIDTH);
    }

    private static VertexConsumer vertex(PoseStack.Pose pose, VertexConsumer consumer,
                                         float x, float y, float z, int argb) {
        return consumer.addVertex(pose, x, y, z).setColor(
                (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    private static float relative(double coordinate, double camera) {
        return (float) (coordinate - camera);
    }

    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }
}
