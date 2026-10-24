package io.github.billstark001.worldmirror.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.billstark001.worldmirror.config.ModConfig.WorldChunkOverlayColorMode;
import io.github.billstark001.worldmirror.download.ChunkDatabase.ChunkRecord;
import io.github.billstark001.worldmirror.download.ExportStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExportStatusRenderingTest {
    @ParameterizedTest
    @EnumSource(ExportStatus.State.class)
    void coarseWorldStatusUsesTwoQuadsWhileKeepingItsDistinctStateColor(ExportStatus.State state) {
        var mark = new ChunkWorldOverlay.OverlayMark(0,8,0,8,96,status(0,0,state).counts(),false);
        var overlay = new ChunkWorldOverlay.OverlaySnapshot(List.of(),List.of(),false,60,List.of(mark));
        var vertices = new java.util.concurrent.atomic.AtomicInteger();
        var colors = new ArrayList<Integer>();
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class},(proxy,method,args) -> {
                    if (method.getName().equals("addVertex")) vertices.incrementAndGet();
                    if (method.getName().equals("setColor") && args.length == 4)
                        colors.add(((int)args[0]<<16)|((int)args[1]<<8)|(int)args[2]);
                    return method.getReturnType() == VertexConsumer.class ? proxy : null;
                });
        ChunkWorldOverlayGeometry.renderTopEdges(new PoseStack().last(),consumer,overlay,new Vec3(0,110,0));
        assertEquals(8,vertices.get());
        assertTrue(colors.contains(ExportStatusMarkers.compact(mark.counts())&0xFFFFFF));
    }
    @Test
    void pixelSizedCellsKeepPartialFailureDistinctFromSuccessfulWrites() {
        var colors = new java.util.HashSet<Integer>();
        for (var state : ExportStatus.State.values()) {
            var saved = new ChunkStatusSnapshot(List.of(new ChunkRecord(0,0,100,"world_mirror")),Set.of());
            var live = new LiveChunkStatusSnapshot(Map.of(),Map.of(0L,status(0,0,state)));
            var drawn = new ArrayList<Integer>();
            ChunkMapView.renderBuiltIn((x1,z1,x2,z2,color)->drawn.add(color), new ChunkMapProjection(), saved,live,
                    new ChunkMapView.BuiltInViewport(10,10,0,0,1,false),200,1);
            int marker=ExportStatusMarkers.compact(status(0,0,state).counts());
            assertTrue(drawn.contains(marker));
            colors.add(marker);
        }
        assertEquals(5,colors.size());
    }
    @ParameterizedTest
    @EnumSource(ExportStatus.State.class)
    void allFiveStatesAppearOnDenseSparseXaeroAndBothWorldModesWithoutChangingBaseColors(ExportStatus.State state) {
        var saved = new ChunkStatusSnapshot(List.of(new ChunkRecord(0,0,100,"world_mirror")), Set.of(new ChunkPos(0,0)));
        var empty = new LiveChunkStatusSnapshot(Map.of(), Map.of());
        var live = new LiveChunkStatusSnapshot(Map.of(), Map.of(0L, status(0,0,state)));
        int marker = color(state);
        for (int threshold : new int[]{1,16}) {
            var colors = new ArrayList<Integer>();
            ChunkMapView.renderBuiltIn((x1,z1,x2,z2,color) -> colors.add(color), new ChunkMapProjection(), saved, live,
                    new ChunkMapView.BuiltInViewport(100,100,0,0,8,false),200,threshold);
            assertTrue(colors.contains(0xFF00C800));
            assertTrue(colors.contains(marker), state.toString());
            assertTrue(colors.contains(ExportStatusMarkers.CONFLICT));
        }
        var colors = new ArrayList<Integer>();
        ChunkMapView.renderOverlay((x1,z1,x2,z2,color) -> colors.add(color), new ChunkMapProjection(),
                coordinate -> 30+(int)coordinate, coordinate -> 30+(int)coordinate,
                saved,live,100,100,0,0,1,200,4096);
        assertTrue(colors.contains(0x5F00C800));
        assertTrue(colors.contains(marker));
        assertTrue(colors.contains(ExportStatusMarkers.CONFLICT));
        var range = new ChunkBounds(0,0,0,0);
        for (var mode : WorldChunkOverlayColorMode.values()) {
            var settings = new ChunkWorldOverlay.Settings(96,50,true,60,mode);
            var before = ChunkWorldOverlay.build(saved,empty,range,settings,200);
            var after = ChunkWorldOverlay.build(saved,live,range,settings,200);
            assertEquals(before.chunks(), after.chunks(), mode.toString());
            assertEquals(before.edges(), after.edges());
            assertEquals(state, after.marks().getFirst().counts().dominant());
            assertTrue(after.marks().getFirst().conflict());
        }
    }

    @ParameterizedTest
    @EnumSource(ExportStatus.State.class)
    void statusOnlyFrozenWorkRemainsVisibleAboveAndBelowWorldPlane(ExportStatus.State state) {
        var live = new LiveChunkStatusSnapshot(Map.of(),Map.of(0L,status(0,0,state)));
        var overlay = ChunkWorldOverlay.build(ChunkStatusSnapshot.EMPTY, live, new ChunkBounds(0,0,0,0),
                new ChunkWorldOverlay.Settings(96,0,false,60,WorldChunkOverlayColorMode.SESSION),200);
        assertTrue(overlay.chunks().isEmpty());
        assertFalse(overlay.isEmpty());
        for (double height : new double[]{80,110}) {
            var colors = new ArrayList<Integer>();
            var vertices = new ArrayList<float[]>();
            VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                    new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                        if (method.getName().equals("setColor") && args.length == 4)
                            colors.add(((int)args[0]<<16)|((int)args[1]<<8)|(int)args[2]);
                        if (method.getName().equals("addVertex")) {
                            int offset = args.length-3;
                            vertices.add(new float[]{((Number)args[offset]).floatValue(), ((Number)args[offset+1]).floatValue(), ((Number)args[offset+2]).floatValue()});
                        }
                        return method.getReturnType() == VertexConsumer.class ? proxy : null;
                    });
            ChunkWorldOverlayGeometry.renderTopEdges(new PoseStack().last(),consumer,overlay,new Vec3(0,height,0));
            assertTrue(colors.contains(color(state)&0xFFFFFF));
            assertFalse(vertices.isEmpty());
            float[] a=vertices.get(0), b=vertices.get(1), c=vertices.get(2);
            float normalY=(b[2]-a[2])*(c[0]-a[0])-(b[0]-a[0])*(c[2]-a[2]);
            assertTrue(height < 96 ? normalY < 0 : normalY > 0, "camera-facing marker winding");
        }
    }

    @Test
    void largeCacheBucketsRetainEveryStateCountAndWarmQueriesDoNotRescan() {
        Map<Long,ExportStatus.Chunk> records = new HashMap<>();
        for (int i=0;i<50_000;i++) {
            int x=i%250-125,z=i/250-100;
            records.put(ChunkBounds.key(x,z),status(x,z,ExportStatus.State.values()[i%5]));
        }
        var live = new LiveChunkStatusSnapshot(Map.of(), records);
        var range = new ChunkBounds(-512,511,-512,511);
        var layout = ChunkBucketLayout.withinBudget(range,1024);
        var summaries = live.index().query(layout);
        var counts = summaries.values().stream().map(ChunkBucketIndex.Summary::exports)
                .reduce(ExportStatus.Counts.EMPTY,ExportStatus.Counts::plus);
        assertEquals(new ExportStatus.Counts(10_000,10_000,10_000,10_000,10_000,20_000,10_000,0),counts);
        long visits=live.index().visits();
        for(int frame=0;frame<120;frame++) assertEquals(summaries,live.index().query(layout));
        assertEquals(visits,live.index().visits());
        var geometry = new ChunkWorldOverlay.GeometryCache();
        var settings = new ChunkWorldOverlay.Settings(96,0,false,60,WorldChunkOverlayColorMode.SESSION);
        var overlay=geometry.get(ChunkStatusSnapshot.EMPTY,live,range,settings,0);
        assertTrue(overlay.marks().size()<=4096);
        for(int frame=0;frame<120;frame++) assertSame(overlay,geometry.get(ChunkStatusSnapshot.EMPTY,live,range,settings,frame*50));
        assertEquals(ExportStatus.State.FAILED,counts.dominant());
    }

    @Test
    void retryWritingKeepsFailureAccentAndHistoricalTerrainHasNoInventedSuccess() {
        var part = new ExportStatus.Part(1,-1,1,1);
        var retry = new ExportStatus.Chunk(0,0,part,ExportStatus.Part.NONE,false,0);
        var colors=new ArrayList<Integer>();
        ExportStatusMarkers.draw(retry.counts(),false,(x1,z1,x2,z2,color)->colors.add(color));
        assertTrue(colors.contains(ExportStatusMarkers.WRITING));
        assertTrue(colors.contains(ExportStatusMarkers.FAILED));
        var saved=new ChunkStatusSnapshot(List.of(new ChunkRecord(0,0,100,"world_mirror")),Set.of());
        var frame=new ChunkMapProjection().get(saved,LiveChunkStatusSnapshot.EMPTY,new ChunkBounds(0,0,0,0),200,4096,false);
        assertTrue(frame.marks().isEmpty());
        assertNull(LiveChunkStatusSnapshot.EMPTY.exportAt(0,0));
    }

    static ExportStatus.Chunk status(int x,int z,ExportStatus.State state) {
        var part=switch(state) {
            case QUEUED -> new ExportStatus.Part(1,-1,-1,-1);
            case WRITING -> new ExportStatus.Part(1,-1,-1,1);
            case FAILED -> new ExportStatus.Part(1,-1,1,-1);
            case PARTIAL, SUCCEEDED -> new ExportStatus.Part(1,1,-1,-1);
        };
        return new ExportStatus.Chunk(x,z,part,state==ExportStatus.State.PARTIAL ? new ExportStatus.Part(1,-1,1,-1) : ExportStatus.Part.NONE,false,0);
    }
    private static int color(ExportStatus.State state) {
        return switch(state) {
            case QUEUED -> ExportStatusMarkers.QUEUED;
            case WRITING -> ExportStatusMarkers.WRITING;
            case FAILED, PARTIAL -> ExportStatusMarkers.FAILED;
            case SUCCEEDED -> ExportStatusMarkers.SUCCEEDED;
        };
    }
}
