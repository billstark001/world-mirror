package io.github.billstark001.worldmirror.legacy;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import java.util.Optional;

/** Decodes stored names, including v0 component JSON inside an NBT string. */
public final class LegacyContainerName {
    private LegacyContainerName() {}

    public static Optional<Component> decodeStored(Tag tag) {
        try {
            String text = tag instanceof StringTag ? tag.asString().orElse("").stripLeading() : "";
            if (text.startsWith("{") || text.startsWith("[")) {
                return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                        .result();
            }
            return ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, tag).result();
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }
}
