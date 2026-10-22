package io.github.billstark001.worldmirror.util;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Optional;
import java.util.Set;

/** Preserves player titles while excluding unmodified container GUI translations. */
public final class ContainerNameSupport {
    private static final Set<String> DEFAULT_CHEST_TITLES = Set.of("container.chest", "container.chestDouble");

    private ContainerNameSupport() { }

    /** Encodes a custom title without inferring identity from localized or literal display text. */
    public static Optional<Tag> encodeCustomName(Component name) {
        if (name == null || isDefaultTitle(name)) return Optional.empty();
        return ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, name)
                .resultOrPartial(error -> WMLogger.warnRateLimited(
                        "container-name-encode", 30_000L, "Failed to encode a container name: " + error));
    }

    /** Recognizes both legacy JSON and current NBT component encodings of plain chest titles. */
    public static boolean isDefaultChestName(Tag tag) {
        try {
            Component component;
            String text = tag instanceof StringTag ? tag.asString().orElse("").stripLeading() : "";
            if (text.startsWith("{") || text.startsWith("[")) {
                component = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                        .result().orElse(null);
            } else {
                component = ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null);
            }
            return component != null && component.getContents() instanceof TranslatableContents translated
                    && DEFAULT_CHEST_TITLES.contains(translated.getKey()) && isDefaultTitle(component);
        } catch (RuntimeException malformedComponent) {
            // An unknown component is user data, not evidence of a default title.
            return false;
        }
    }

    private static boolean isDefaultTitle(Component name) {
        return name.getContents() instanceof TranslatableContents translated
                && translated.getKey().startsWith("container.")
                && translated.getArgs().length == 0 && name.getSiblings().isEmpty()
                && name.getStyle().isEmpty();
    }
}
