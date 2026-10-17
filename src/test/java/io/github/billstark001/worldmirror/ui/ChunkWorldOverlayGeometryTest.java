package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkWorldOverlayGeometryTest {
    private static final ChunkWorldOverlay.OverlaySnapshot BOX = new ChunkWorldOverlay.OverlaySnapshot(
            List.of(new ChunkWorldOverlay.OverlayChunk(0, 1, 0, 1, 64, 0xFF20E060)),
            List.of(
                    new ChunkWorldOverlay.OverlayEdge(0, 0, 1, 0, 64, 0xFF20E060, false, false),
                    new ChunkWorldOverlay.OverlayEdge(0, 0, 0, 1, 64, 0xFF20E060, false, false),
                    new ChunkWorldOverlay.OverlayEdge(1, 0, 1, 1, 64, 0xFF20E060, false, true),
                    new ChunkWorldOverlay.OverlayEdge(0, 1, 1, 1, 64, 0xFF20E060, false, true)),
            true, 60);

    @Test
    void aboveAndBelowBlendExactlyOneCap() {
        var above = vertices(new Vec3(8, 80, 8));
        var below = vertices(new Vec3(8, 50, 8));
        assertEquals(4, above.size(), "A transparent back cap must not double the fill opacity");
        assertEquals(4, below.size());
        assertTrue(above.stream().allMatch(v -> v[1] == -15));
        assertTrue(below.stream().allMatch(v -> v[1] == 14));
    }

    @Test
    void keepsOnlyTheFacingWallFromEachSide() {
        for (Vec3 camera : List.of(new Vec3(-8, 64.5, 8), new Vec3(24, 64.5, 8),
                new Vec3(8, 64.5, -8), new Vec3(8, 64.5, 24))) {
            var vertices = vertices(camera);
            assertEquals(4, vertices.size(), "The box retains its near wall without blending the far wall");
            assertEquals(-0.5F, vertices.stream().map(v -> v[1]).min(Float::compare).orElseThrow());
            assertEquals(0.5F, vertices.stream().map(v -> v[1]).max(Float::compare).orElseThrow());
        }
    }

    @Test
    void cornerViewKeepsCapAndTwoNearWalls() {
        assertEquals(12, vertices(new Vec3(-8, 80, -8)).size());
    }

    private static List<float[]> vertices(Vec3 camera) {
        List<float[]> vertices = new ArrayList<>();
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(
                VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addVertex")) {
                        int offset = args.length - 3;
                        vertices.add(new float[]{((Number) args[offset]).floatValue(),
                                ((Number) args[offset + 1]).floatValue(), ((Number) args[offset + 2]).floatValue()});
                    }
                    return method.getReturnType() == VertexConsumer.class ? proxy : null;
                });
        ChunkWorldOverlayGeometry.renderFilled(new PoseStack().last(), consumer, BOX, camera);
        return vertices;
    }
}
