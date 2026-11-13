package io.github.billstark001.worldmirror.util;

import io.github.billstark001.worldmirror.legacy.LegacyContainerName;
import net.minecraft.nbt.NbtOps;
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

    public static boolean isDefaultChestName(Tag tag) {
        return LegacyContainerName.decodeStored(tag).filter(ContainerNameSupport::isChestTitle).isPresent();
    }

    private static boolean isChestTitle(Component name) {
        return name.getContents() instanceof TranslatableContents translated
                && DEFAULT_CHEST_TITLES.contains(translated.getKey()) && isDefaultTitle(name);
    }

    private static boolean isDefaultTitle(Component name) {
        return name.getContents() instanceof TranslatableContents translated
                && translated.getKey().startsWith("container.")
                && translated.getArgs().length == 0 && name.getSiblings().isEmpty()
                && name.getStyle().isEmpty();
    }
}
