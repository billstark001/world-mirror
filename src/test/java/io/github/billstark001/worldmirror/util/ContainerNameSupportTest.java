package io.github.billstark001.worldmirror.util;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerNameSupportTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void omitsOnlyUnmodifiedGuiTranslations() {
        assertTrue(ContainerNameSupport.encodeCustomName(null).isEmpty());
        assertTrue(ContainerNameSupport.encodeCustomName(Component.translatable("container.chest")).isEmpty());
        assertTrue(ContainerNameSupport.encodeCustomName(Component.translatable("container.furnace")).isEmpty());
    }

    @Test
    void preservesLiteralNamesEvenWhenTheyLookLikeTheDefaultEnglishTitle() {
        for (String text : new String[]{"Chest", "Large Chest", "", "箱子"}) {
            Component name = Component.literal(text);
            var encoded = ContainerNameSupport.encodeCustomName(name).orElseThrow();
            Component decoded = ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
            assertEquals(name, decoded);
            assertFalse(ContainerNameSupport.isDefaultChestName(encoded));
        }
    }

    @Test
    void preservesStyleSiblingsAndTranslationArguments() {
        for (Component name : new Component[]{
                Component.translatable("container.chest").withStyle(ChatFormatting.RED),
                Component.translatable("container.chest").append(" — personal storage"),
                Component.translatable("container.chest", "custom argument")}) {
            var encoded = ContainerNameSupport.encodeCustomName(name).orElseThrow();
            assertFalse(ContainerNameSupport.isDefaultChestName(encoded));
            assertEquals(name, ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
        }
    }
}
