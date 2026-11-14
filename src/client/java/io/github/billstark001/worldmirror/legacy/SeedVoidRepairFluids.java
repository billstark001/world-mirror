package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.download.SeedTerrainBiomes;
import io.github.billstark001.worldmirror.download.TerrainBitStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Requires each incoming fluid component to touch the same liquid across a retained chunk edge. */
final class SeedVoidRepairFluids {
    static final byte WATER = 1;
    static final byte LAVA = 2;
    private static final byte COBBLESTONE = 3;
    private static final int SECTION_CELLS = 4096;
    private static final byte[] EMPTY = new byte[0];
    private static final Set<String> BLOCK_KEYS = Set.of("palette", "data");
    private static final Set<String> FLOW_LEVELS =
            Set.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15");
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private record Cell(int x, int y, int z) {}
    private record Chunk(int x, int z) {}

    private SeedVoidRepairFluids() {}

    static byte[] flowingCells(CompoundTag section, boolean allowReaction) {
        return decode(section, true, allowReaction);
    }

    private static byte[] decode(CompoundTag section, boolean checkFlow, boolean allowReaction) {
        var blocks = section.getCompoundOrEmpty("block_states");
        var palette = blocks.getListOrEmpty("palette");
        int size = palette.size();
        if (size < 1 || size > SECTION_CELLS || (size == 1 && blocks.contains("data"))
                || (checkFlow && !BLOCK_KEYS.containsAll(blocks.keySet())))
            return null;
        byte[] types = new byte[size];
        boolean hasFluid = false;
        for (int i = 0; i < size; i++) {
            Tag state = palette.get(i);
            types[i] = liquid(state, checkFlow);
            if (allowReaction && types[i] == 0 && stateId(state).equals("minecraft:cobblestone")
                    && (!(state instanceof CompoundTag tag) || tag.size() == 1))
                types[i] = COBBLESTONE;
            if (checkFlow && types[i] == 0 && !SeedTerrainBiomes.isVanillaAir(state))
                return null;
            hasFluid |= types[i] != 0;
        }
        if (size == 1 && !hasFluid)
            return EMPTY;
        var data = blocks.getLongArray("data").orElse(null);
        int bits = Math.max(4, TerrainBitStorage.bits(size));
        if (size > 1 && (data == null || data.length != TerrainBitStorage.wordCount(SECTION_CELLS, bits)))
            return null;
        var cells = new byte[SECTION_CELLS];
        boolean fluidInUse = false;
        for (int i = 0; i < SECTION_CELLS; i++) {
            int index = size == 1 ? 0 : TerrainBitStorage.cell(data, bits, i);
            if (index >= size)
                return null;
            cells[i] = types[index];
            fluidInUse |= cells[i] != 0;
        }
        return fluidInUse ? cells : EMPTY;
    }

    private static byte liquid(Tag state, boolean checkFlow) {
        byte type = liquidId(stateId(state));
        if (!checkFlow || type == 0)
            return type;
        if (!(state instanceof CompoundTag tag) || tag.size() != 2)
            return 0;
        String propertiesKey = tag.contains("id") ? "properties" : "Properties";
        var properties = tag.getCompoundOrEmpty(propertiesKey);
        return properties.size() == 1 && FLOW_LEVELS.contains(properties.getStringOr("level", "")) ? type : 0;
    }

    private static String stateId(Tag state) {
        if (state instanceof CompoundTag tag)
            return tag.getStringOr("id", tag.getStringOr("Name", tag.getStringOr("", "")));
        return state.asString().orElse("");
    }

    private static byte liquidId(String name) {
        return switch (name) {
            case "minecraft:water" -> WATER;
            case "minecraft:lava" -> LAVA;
            default -> 0;
        };
    }

    static byte fluidAt(Map<Integer, byte[]> sections, int x, int y, int z) {
        var cells = sections.get(y >> 4);
        return cells == null || cells.length == 0 ? 0 : cells[(y & 15) * 256 + z * 16 + x];
    }

    static boolean connected(Map<Integer, byte[]> sections, int x, int z,
            SeedVoidRepairEvidence.NeighborTerrain reader) throws IOException {
        Map<Chunk, Map<Integer, byte[]>> neighbors = new HashMap<>();
        Set<Cell> visited = new HashSet<>();
        int reactions = 0;
        for (var entry : sections.entrySet()) {
            int minY = entry.getKey() << 4;
            byte[] cells = entry.getValue();
            for (int index = 0; index < cells.length; index++) {
                if (cells[index] == 0)
                    continue;
                var cell = new Cell(index & 15, minY + (index >> 8), (index >> 4) & 15);
                if (cells[index] == COBBLESTONE) {
                    if (++reactions > 256 || !isBoundaryReaction(sections, cell, x, z, neighbors, reader))
                        return false;
                    continue;
                }
                if (visited.add(cell)
                        && !reachesRetainedNeighbor(
                                sections, cell, cells[index], visited, x, z, neighbors, reader))
                    return false;
            }
        }
        return true;
    }

    private static boolean isBoundaryReaction(Map<Integer, byte[]> sections, Cell cell, int chunkX,
            int chunkZ, Map<Chunk, Map<Integer, byte[]>> neighbors,
            SeedVoidRepairEvidence.NeighborTerrain reader) throws IOException {
        // Reactions in affected void chunks lie at a low edge, in contact with incoming water.
        if (cell.y() > -20 || (cell.x() != 0 && cell.x() != 15 && cell.z() != 0 && cell.z() != 15))
            return false;
        for (int[] direction : DIRECTIONS) {
            int x = cell.x() + direction[0];
            int z = cell.z() + direction[2];
            int y = cell.y() + direction[1];
            boolean outside = x < 0 || x > 15 || z < 0 || z > 15;
            byte fluid = outside ? retainedFluidAt(new Cell(x, y, z), chunkX, chunkZ, neighbors, reader)
                                 : fluidAt(sections, x, y, z);
            if (fluid == WATER)
                return true;
        }
        return false;
    }

    private static boolean reachesRetainedNeighbor(Map<Integer, byte[]> sections, Cell start, byte type,
            Set<Cell> visited, int chunkX, int chunkZ, Map<Chunk, Map<Integer, byte[]>> neighbors,
            SeedVoidRepairEvidence.NeighborTerrain reader) throws IOException {
        var pending = new ArrayDeque<Cell>();
        pending.add(start);
        boolean connects = false;
        while (!pending.isEmpty()) {
            Cell current = pending.removeFirst();
            for (int[] direction : DIRECTIONS) {
                var next = new Cell(
                        current.x() + direction[0], current.y() + direction[1], current.z() + direction[2]);
                if (next.x() < 0 || next.x() > 15 || next.z() < 0 || next.z() > 15) {
                    if (!connects)
                        connects = retainedFluidAt(next, chunkX, chunkZ, neighbors, reader) == type;
                } else if (fluidAt(sections, next.x(), next.y(), next.z()) == type && visited.add(next)) {
                    pending.add(next);
                }
            }
        }
        return connects;
    }

    private static byte retainedFluidAt(Cell cell, int x, int z, Map<Chunk, Map<Integer, byte[]>> cache,
            SeedVoidRepairEvidence.NeighborTerrain reader) throws IOException {
        var key = new Chunk(x + (cell.x() >> 4), z + (cell.z() >> 4));
        var sections = cache.get(key);
        if (sections == null) {
            sections = retainedFluids(reader.read(key.x(), key.z()));
            cache.put(key, sections);
        }
        return fluidAt(sections, cell.x() & 15, cell.y(), cell.z() & 15);
    }

    private static Map<Integer, byte[]> retainedFluids(CompoundTag chunk) {
        if (chunk == null)
            return Map.of();
        Map<Integer, byte[]> result = new HashMap<>();
        for (Tag tag : chunk.getListOrEmpty("sections")) {
            if (!(tag instanceof CompoundTag section) || !section.contains("block_states")
                    || section.getByte("Y").isEmpty())
                continue;
            int y = section.getByteOr("Y", (byte) 0);
            byte[] data = decode(section, false, false);
            if (data == null || result.putIfAbsent(y, data) != null)
                return Map.of();
        }
        return result;
    }
}
