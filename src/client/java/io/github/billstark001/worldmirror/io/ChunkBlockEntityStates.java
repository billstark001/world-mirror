package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.download.TerrainBitStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** Reads the block state under NBT records without constructing or decoding a whole chunk. */
final class ChunkBlockEntityStates {
    private final CompoundTag chunk;
    private final Map<Integer, Section> sections = new HashMap<>();

    ChunkBlockEntityStates(CompoundTag chunk) {
        this.chunk = chunk;
    }

    BlockState at(BlockPos pos) {
        int sectionY = pos.getY() >> 4;
        if (!sections.containsKey(sectionY))
            sections.put(sectionY, findSection(sectionY));
        Section section = sections.get(sectionY);
        return section == null ? null : section.at(pos);
    }

    private Section findSection(int sectionY) {
        CompoundTag found = null;
        for (Tag value : chunk.getListOrEmpty("sections")) {
            if (!(value instanceof CompoundTag section) || !(section.get("Y") instanceof ByteTag y)
                    || y.value() != sectionY)
                continue;
            if (found != null)
                return null;
            found = section;
        }
        return found == null ? null : Section.read(found.getCompoundOrEmpty("block_states"));
    }

    private record Section(ListTag palette, long[] data, int bits) {
        static Section read(CompoundTag data) {
            ListTag palette = data.getListOrEmpty("palette");
            if (palette.isEmpty() || palette.size() > 4096)
                return null;
            if (palette.size() == 1)
                return data.contains("data") ? null : new Section(palette, null, 0);
            int bits = Math.max(4, TerrainBitStorage.bits(palette.size()));
            long[] words = data.getLongArray("data").orElse(null);
            return words == null || words.length != TerrainBitStorage.wordCount(4096, bits)
                    ? null
                    : new Section(palette, words, bits);
        }

        BlockState at(BlockPos pos) {
            int cell = ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
            int index = bits == 0 ? 0 : TerrainBitStorage.cell(data, bits, cell);
            if (index >= palette.size())
                return null;
            Tag state = palette.get(index);
            String name = state instanceof CompoundTag compound
                    ? compound.getStringOr("id", compound.getStringOr("Name", compound.getStringOr("", "")))
                    : state.asString().orElse("");
            if (name.isEmpty())
                return null;
            Identifier id = Identifier.tryParse(name);
            // The block-entity type validates the block, independent of its properties.
            return id == null ? null
                              : BuiltInRegistries.BLOCK.getOptional(id)
                                        .map(block -> block.defaultBlockState())
                                        .orElse(null);
        }
    }
}
