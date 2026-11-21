package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.core.ContainerTracker;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityNbtSupportTest {
    private static final ChunkPos ORIGIN = new ChunkPos(0, 0);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void replacingAChestWithAnotherBlockEntityDoesNotTransferItsInventoryOrName() {
        CompoundTag old = blockEntity("minecraft:chest");
        old.putString("CustomName", "old chest");
        ListTag items = new ListTag();
        items.add(new CompoundTag());
        old.put("Items", items);
        CompoundTag replacement = blockEntity("minecraft:sign");
        CompoundTag target = terrain(legacyState("minecraft:oak_sign"), replacement);

        BlockEntityNbtSupport.mergeChunkBlockEntities(
                target, terrain(legacyState("minecraft:chest"), old), ORIGIN);

        assertFalse(replacement.contains("Items"));
        assertFalse(replacement.contains("CustomName"));
    }

    @Test
    void duplicateSourcePositionsRestoreOnlyOneEntry() {
        CompoundTag first = blockEntity("minecraft:chest");
        CompoundTag second = blockEntity("minecraft:chest");
        second.putString("CustomName", "known name");
        CompoundTag target = terrain(StringTag.valueOf("minecraft:chest"));

        BlockEntityNbtSupport.mergeChunkBlockEntities(target, chunk(first, second), ORIGIN);

        ListTag merged = target.getListOrEmpty("block_entities");
        assertEquals(1, merged.size());
        assertEquals("known name", merged.getCompound(0).orElseThrow().getStringOr("CustomName", ""));
    }

    @Test
    void malformedCoordinatesCannotAliasARealBlockEntityAtZero() {
        CompoundTag old = blockEntity("minecraft:chest");
        old.putString("x", "not a coordinate");
        old.putString("CustomName", "wrong position");
        CompoundTag replacement = blockEntity("minecraft:chest");

        BlockEntityNbtSupport.mergeChunkBlockEntities(chunk(replacement), chunk(old), ORIGIN);

        assertFalse(replacement.contains("CustomName"));
    }

    @Test
    void prunesOnlyIncompatibleAndForeignRecordsInPackedTerrain() {
        var chest = blockEntity("minecraft:chest");
        var removed = atX("minecraft:ender_chest", 1);
        removed.putBoolean("keepPacked", true);
        var foreign = atX("minecraft:chest", 16);
        var unknown = atX("example:other_mod_block_entity", 2);
        var dummy = blockEntity("DUMMY");
        dummy.putBoolean("keepPacked", true);
        // Packed palette index 1 is air only at x=1.
        var target = packed(chest, removed, foreign, unknown, dummy);

        assertEquals(2, BlockEntityNbtSupport.removeIncompatible(target, ORIGIN));
        assertEquals(chunk(chest, unknown, dummy).get("block_entities"), target.get("block_entities"));
        assertTrue(BlockEntityNbtSupport.isIncompatible(dummy, Blocks.AIR.defaultBlockState()));
        assertFalse(BlockEntityNbtSupport.isIncompatible(dummy, Blocks.CHEST.defaultBlockState()));
    }

    @Test
    void changedTerrainDoesNotInheritIncompatibleOrUnprovenOldBlockEntities() {
        var chest = blockEntity("minecraft:chest");
        chest.putString("CustomName", "removed chest");
        var sign = atX("minecraft:sign", 1);
        var before = terrain(legacyState("minecraft:chest"), chest, sign);
        var brokenPalette = packed(chest);
        sectionStates(brokenPalette).putLongArray("data", new long[] {1L});

        var targets = new CompoundTag[] {terrain(StringTag.valueOf("minecraft:stone")), new CompoundTag()};
        for (CompoundTag target : targets) {
            BlockEntityNbtSupport.mergeChunkBlockEntities(target, before, ORIGIN);
            assertTrue(target.getListOrEmpty("block_entities").isEmpty());
        }
        assertEquals(0, BlockEntityNbtSupport.removeIncompatible(brokenPalette, ORIGIN));
        assertEquals(chest, brokenPalette.getListOrEmpty("block_entities").getCompound(0).orElseThrow());
    }

    @Test
    void staleDiskRecordsCannotEnrichRealBlockEntitiesAtTheSamePosition() {
        var stale = blockEntity("minecraft:chest");
        stale.putString("CustomName", "old chest at a stone block");
        var newChest = blockEntity("minecraft:chest");
        CompoundTag target = terrain(StringTag.valueOf("minecraft:chest"), newChest);

        BlockEntityNbtSupport.mergeChunkBlockEntities(
                target, terrain(legacyState("minecraft:stone"), stale), ORIGIN);

        assertFalse(newChest.contains("CustomName"));
    }

    @Test
    void emptyFrozenInventoryStaysEmptyWhenAnOlderChestHadItems() {
        var old = blockEntity("minecraft:chest");
        ListTag items = new ListTag();
        items.add(new CompoundTag());
        old.put("Items", items);
        var chest = blockEntity("minecraft:chest");
        var target = terrain(StringTag.valueOf("minecraft:chest"), chest);
        var overlay = new CompoundTag();
        overlay.putString("id", "minecraft:chest");
        overlay.put("Items", new ListTag());
        var overlays = Map.of(Level.OVERWORLD, Map.of(new BlockPos(0, 64, 0), overlay));

        BlockEntityNbtSupport.applyContainerOverlays(Level.OVERWORLD, target, overlays);
        BlockEntityNbtSupport.mergeChunkBlockEntities(
                target, terrain(legacyState("minecraft:chest"), old), ORIGIN, Level.OVERWORLD, overlays);

        assertTrue(chest.getListOrEmpty("Items").isEmpty());
    }

    @Test
    void cachedInventoryAndOlderRecordStayWithTheirOriginalChestBlock() {
        var oldChest = blockEntity("minecraft:chest");
        oldChest.put("Items", items());
        var trap = blockEntity("minecraft:chest");
        var target = terrain(StringTag.valueOf("minecraft:trapped_chest"), trap);
        var overlay = blockEntity("minecraft:chest");
        overlay.putString(ContainerTracker.BLOCK_ID, "minecraft:chest");
        overlay.put("Items", items());
        var overlays = Map.of(Level.OVERWORLD, Map.of(new BlockPos(0, 64, 0), overlay));

        BlockEntityNbtSupport.applyContainerOverlays(Level.OVERWORLD, target, overlays);
        BlockEntityNbtSupport.mergeChunkBlockEntities(
                target, terrain(legacyState("minecraft:chest"), oldChest), ORIGIN, Level.OVERWORLD, overlays);

        assertFalse(trap.contains("Items"));
    }

    @Test
    void knownInventoryOverridesLootTableAndUnobservedInventoryRetainsIt() {
        var oldChest = blockEntity("minecraft:chest");
        oldChest.putString("LootTable", "minecraft:chests/simple_dungeon");
        oldChest.putLong("LootTableSeed", 25L);
        var previous = terrain(legacyState("minecraft:chest"), oldChest);
        var opened = (CompoundTag) oldChest.copy();
        var openedChunk = terrain(StringTag.valueOf("minecraft:chest"), opened);
        var overlay = new CompoundTag();
        overlay.putString("id", "minecraft:chest");
        overlay.put("Items", new ListTag());
        var overlays = Map.of(Level.OVERWORLD, Map.of(new BlockPos(0, 64, 0), overlay));

        BlockEntityNbtSupport.applyContainerOverlays(Level.OVERWORLD, openedChunk, overlays);
        BlockEntityNbtSupport.mergeChunkBlockEntities(
                openedChunk, previous, ORIGIN, Level.OVERWORLD, overlays);
        assertTrue(opened.contains("Items"));
        assertFalse(opened.contains("LootTable"));
        assertFalse(opened.contains("LootTableSeed"));

        var unobserved = blockEntity("minecraft:chest");
        BlockEntityNbtSupport.mergeChunkBlockEntities(
                terrain(StringTag.valueOf("minecraft:chest"), unobserved), previous, ORIGIN);
        assertEquals(oldChest.get("LootTable"), unobserved.get("LootTable"));
        assertEquals(oldChest.get("LootTableSeed"), unobserved.get("LootTableSeed"));
    }

    @Test
    void defaultChestTitlesUseComponentDecodingInsteadOfSubstringMatching() {
        assertFalse(sanitizedName(StringTag.valueOf("{ \"translate\" : \"container.chest\" }")));
        assertTrue(sanitizedName(StringTag.valueOf("player title: \"translate\":\"container.chest\"")));
        CompoundTag styled = new CompoundTag();
        styled.putString("translate", "container.chest");
        styled.putString("color", "red");
        assertTrue(sanitizedName(styled));
        CompoundTag extended = new CompoundTag();
        extended.putString("translate", "container.chest");
        ListTag extra = new ListTag();
        extra.add(StringTag.valueOf(" — custom label"));
        extended.put("extra", extra);
        assertTrue(sanitizedName(extended));
    }

    @Test
    void aCachedInventoryCannotBeAppliedToADifferentBlockEntityType() {
        CompoundTag inventory = new CompoundTag();
        inventory.putString("id", "minecraft:chest");
        ListTag items = new ListTag();
        items.add(new CompoundTag());
        inventory.put("Items", items);
        var overlays = Map.of(Level.OVERWORLD, Map.of(new BlockPos(0, 64, 0), inventory));
        CompoundTag replacement = blockEntity("minecraft:furnace");

        BlockEntityNbtSupport.applyContainerOverlays(Level.OVERWORLD, chunk(replacement), overlays);
        assertFalse(replacement.contains("Items"));

        CompoundTag chest = blockEntity("minecraft:chest");
        BlockEntityNbtSupport.applyContainerOverlays(Level.OVERWORLD, chunk(chest), overlays);
        assertEquals(items, chest.get("Items"));
    }

    private static boolean sanitizedName(Tag name) {
        CompoundTag chest = blockEntity("minecraft:chest");
        chest.put("CustomName", name);
        BlockEntityNbtSupport.applyContainerOverlays(Level.OVERWORLD, chunk(chest), Map.of());
        return chest.contains("CustomName");
    }

    private static CompoundTag blockEntity(String id) {
        return atX(id, 0);
    }

    private static ListTag items() {
        var items = new ListTag();
        items.add(new CompoundTag());
        return items;
    }

    private static CompoundTag atX(String id, int x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("x", x);
        tag.putInt("y", 64);
        tag.putInt("z", 0);
        return tag;
    }

    private static CompoundTag chunk(CompoundTag... blockEntities) {
        CompoundTag chunk = new CompoundTag();
        ListTag entries = new ListTag();
        for (CompoundTag blockEntity : blockEntities) entries.add(blockEntity);
        chunk.put("block_entities", entries);
        return chunk;
    }

    private static CompoundTag legacyState(String id) {
        var state = new CompoundTag();
        state.putString("Name", id);
        return state;
    }

    private static CompoundTag packed(CompoundTag... entities) {
        CompoundTag state = new CompoundTag();
        state.putString("id", "minecraft:chest");
        var chunk = terrain(state, entities);
        var states = sectionStates(chunk);
        var palette = states.getListOrEmpty("palette");
        palette.add(StringTag.valueOf("minecraft:air"));
        long[] words = new long[256];
        words[0] = 1L << 4;
        states.putLongArray("data", words);
        return chunk;
    }

    private static CompoundTag sectionStates(CompoundTag chunk) {
        return chunk.getListOrEmpty("sections")
                .getCompound(0)
                .orElseThrow()
                .getCompoundOrEmpty("block_states");
    }

    private static CompoundTag terrain(Tag state, CompoundTag... blockEntities) {
        var chunk = chunk(blockEntities);
        var palette = new ListTag();
        palette.add(state);
        var blocks = new CompoundTag();
        blocks.put("palette", palette);
        var section = new CompoundTag();
        section.putByte("Y", (byte) 4);
        section.put("block_states", blocks);
        var sections = new ListTag();
        sections.add(section);
        chunk.put("sections", sections);
        return chunk;
    }
}
