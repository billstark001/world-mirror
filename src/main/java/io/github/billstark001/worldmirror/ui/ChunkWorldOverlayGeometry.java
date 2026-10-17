package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/** Version-independent vertex emission for the World Mirror chunk boxes. */
public final class ChunkWorldOverlayGeometry {
    private static final float EDGE_HALF_WIDTH = 0.125F;
    private static final float EDGE_Y_OFFSET = 0.01F;
    private static final int BASE_FILL_ALPHA = 72;
    private static final int BASE_EDGE_ALPHA = 220;

    private ChunkWorldOverlayGeometry() {}

    public static void renderFilled(PoseStack.Pose pose,
                                    VertexConsumer consumer,
                                    ChunkWorldOverlay.OverlaySnapshot snapshot,
                                    Vec3 camera) {
        for (ChunkWorldOverlay.OverlayChunk chunk : snapshot.chunks()) {
            float x1 = relative(chunk.startChunkX() * 16.0D, camera.x);
            float z1 = relative(chunk.startChunkZ() * 16.0D, camera.z);
            float x2 = relative(chunk.endChunkX() * 16.0D, camera.x);
            float z2 = relative(chunk.endChunkZ() * 16.0D, camera.z);
            float y1 = relative(chunk.y(), camera.y);
            float y2 = relative(chunk.y() + 1.0D, camera.y);
            int color = withAlpha(chunk.color(), scaledAlpha(BASE_FILL_ALPHA,
                    snapshot.opacityPercent()));

            // Keep the closed box, but blend only its camera-facing shell. Some
            // versions' debug layer disables culling, so emitting both caps would
            // apply the fill opacity twice even when transparency sorting is correct.
            if (camera.y < chunk.y()) {
                quad(pose, consumer, color,
                        x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
            }
            if (camera.y > chunk.y() + 1.0D) {
                quad(pose, consumer, color,
                        x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1);
            }
        }
        // Only status transitions and the perimeter have vertical walls. Adjacent
        // translucent boxes otherwise leave visible stripes even with the grid off.
        for (ChunkWorldOverlay.OverlayEdge edge : snapshot.edges()) {
            if (edge.sameColorInterior()) continue;
            float x1 = relative(edge.startChunkX() * 16.0D, camera.x);
            float z1 = relative(edge.startChunkZ() * 16.0D, camera.z);
            float x2 = relative(edge.endChunkX() * 16.0D, camera.x);
            float z2 = relative(edge.endChunkZ() * 16.0D, camera.z);
            double normalDistance = z1 == z2 ? -z1 : -x1;
            if (edge.outwardPositive() ? normalDistance <= 0 : normalDistance >= 0) continue;
            float y1 = relative(edge.y(), camera.y);
            float y2 = relative(edge.y() + 1.0D, camera.y);
            int color = withAlpha(edge.color(), scaledAlpha(BASE_FILL_ALPHA,
                    snapshot.opacityPercent()));
            if (z1 == z2) {
                if (edge.outwardPositive()) {
                    quad(pose, consumer, color,
                            x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1);
                } else {
                    quad(pose, consumer, color,
                            x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1);
                }
            } else if (edge.outwardPositive()) {
                quad(pose, consumer, color,
                        x1, y1, z1, x1, y2, z1, x2, y2, z2, x2, y1, z2);
            } else {
                quad(pose, consumer, color,
                        x1, y1, z1, x2, y1, z2, x2, y2, z2, x1, y2, z1);
            }
        }
    }

    /** Draws top edges with the same unfogged, depth-tested layer as the fill. */
    public static void renderTopEdges(PoseStack.Pose pose,
                                      VertexConsumer consumer,
                                      ChunkWorldOverlay.OverlaySnapshot snapshot,
                                      Vec3 camera) {
        for (ChunkWorldOverlay.OverlayEdge edge : snapshot.edges()) {
            if (!snapshot.showInnerGrid() && edge.sameColorInterior()) continue;
            float x1 = relative(edge.startChunkX() * 16.0D, camera.x);
            float z1 = relative(edge.startChunkZ() * 16.0D, camera.z);
            float x2 = relative(edge.endChunkX() * 16.0D, camera.x);
            float z2 = relative(edge.endChunkZ() * 16.0D, camera.z);
            float y = relative(edge.y() + 1.0D, camera.y) + EDGE_Y_OFFSET;
            int color = withAlpha(edge.color(), scaledAlpha(BASE_EDGE_ALPHA,
                    snapshot.opacityPercent()));

            if (z1 == z2) {
                // +Y winding so the strip remains visible with face culling.
                quad(pose, consumer, color,
                        x1, y, z1 + EDGE_HALF_WIDTH, x2, y, z1 + EDGE_HALF_WIDTH,
                        x2, y, z1 - EDGE_HALF_WIDTH, x1, y, z1 - EDGE_HALF_WIDTH);
            } else {
                quad(pose, consumer, color,
                        x1 - EDGE_HALF_WIDTH, y, z1, x1 - EDGE_HALF_WIDTH, y, z2,
                        x1 + EDGE_HALF_WIDTH, y, z2, x1 + EDGE_HALF_WIDTH, y, z1);
            }
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer consumer, int color,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4) {
        vertex(pose, consumer, x1, y1, z1, color);
        vertex(pose, consumer, x2, y2, z2, color);
        vertex(pose, consumer, x3, y3, z3, color);
        vertex(pose, consumer, x4, y4, z4, color);
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

    private static int scaledAlpha(int base, int percent) {
        return Math.round(base * Math.max(0, Math.min(100, percent)) / 100.0F);
    }
}
