package io.github.billstark001.worldmirror.io;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.io.UTFDataFormatException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NbtWriteSupportTest {
    @Test
    void usesModifiedUtfLimitsForNulsAndMultibyteCharacters() {
        CompoundTag value = new CompoundTag();
        value.putString("text", "\0".repeat(32_767));
        assertDoesNotThrow(() -> NbtWriteSupport.validateStrings(value));
        value.putString("text", "\0".repeat(32_768));
        assertThrows(UTFDataFormatException.class, () -> NbtWriteSupport.validateStrings(value));
        value.putString("text", "名".repeat(21_845));
        assertDoesNotThrow(() -> NbtWriteSupport.validateStrings(value));
        value.putString("text", "名".repeat(21_846));
        assertThrows(UTFDataFormatException.class, () -> NbtWriteSupport.validateStrings(value));
    }

    @Test
    void checksNestedListStringsAndCompoundKeys() {
        CompoundTag value = new CompoundTag();
        ListTag list = new ListTag();
        list.add(StringTag.valueOf("名".repeat(21_846)));
        value.put("nested", list);
        assertThrows(UTFDataFormatException.class, () -> NbtWriteSupport.validateStrings(value));
        value.remove("nested");
        value.putString("名".repeat(21_846), "short value");
        assertThrows(UTFDataFormatException.class, () -> NbtWriteSupport.validateStrings(value));
    }

    @Test
    void allowsSharedCompoundValuesWithoutRepeatingTheirTraversal() {
        CompoundTag child = new CompoundTag();
        child.putString("text", "shared");
        CompoundTag value = new CompoundTag();
        value.put("first", child);
        value.put("second", child);
        assertDoesNotThrow(() -> NbtWriteSupport.validateStrings(value));
    }
}
