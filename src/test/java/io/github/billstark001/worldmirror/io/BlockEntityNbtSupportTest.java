package io.github.billstark001.worldmirror.io;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityNbtSupportTest {
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
        CompoundTag target = chunk(replacement);

        BlockEntityNbtSupport.mergeChunkBlockEntities(target, chunk(old));

        assertFalse(replacement.contains("Items"));
        assertFalse(replacement.contains("CustomName"));
    }

    @Test
    void duplicateSourcePositionsRestoreOnlyOneEntry() {
        CompoundTag first = blockEntity("minecraft:chest");
        CompoundTag second = blockEntity("minecraft:chest");
        second.putString("CustomName", "known name");
        CompoundTag target = new CompoundTag();

        BlockEntityNbtSupport.mergeChunkBlockEntities(target, chunk(first, second));

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

        BlockEntityNbtSupport.mergeChunkBlockEntities(chunk(replacement), chunk(old));

        assertFalse(replacement.contains("CustomName"));
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
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("x", 0);
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
}
