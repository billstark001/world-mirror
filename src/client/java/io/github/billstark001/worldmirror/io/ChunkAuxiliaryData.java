package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.util.FileProof;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Path;

/** One read-only view of a region's entity and point-of-interest data under the world lease. */
public final class ChunkAuxiliaryData implements AutoCloseable {
    private final Path root, entities, poi;
    private final int regionX, regionZ;
    private Readers readers;
    private IOException openFailure;

    public ChunkAuxiliaryData(Path root, Path regionFile, int regionX, int regionZ) {
        this.root = root;
        entities = regionFile.getParent().resolveSibling("entities").resolve(regionFile.getFileName());
        poi = regionFile.getParent().resolveSibling("poi").resolve(regionFile.getFileName());
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    private record Readers(AnvilReader.Reader entities, AnvilReader.Reader poi) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try (entities; poi) {
                // Close both readers even when one close reports an error.
            }
        }
    }

    /** Conversion scans validate auxiliary region headers before approving edits. */
    public void inspectRegions() throws IOException {
        if (readers != null)
            return;
        if (openFailure != null)
            throw openFailure;
        try {
            FileProof.requirePlainPath(root, entities);
            FileProof.requirePlainPath(root, poi);
            var entityReader = AnvilReader.open(entities, regionX, regionZ);
            try {
                readers = new Readers(entityReader, AnvilReader.open(poi, regionX, regionZ));
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    entityReader.close();
                } catch (IOException close) {
                    failure.addSuppressed(close);
                }
                throw failure;
            }
        } catch (IOException failure) {
            openFailure = failure;
            throw failure;
        }
    }

    /** Treat unreadable, future, or structurally unexpected data as protected. */
    public boolean hasData(ChunkPos pos) throws IOException {
        inspectRegions();
        return containsEntities(pos) || containsPoi(pos);
    }

    private boolean containsEntities(ChunkPos pos) throws IOException {
        if (!readers.entities().positions().contains(pos))
            return false;
        FileProof.requirePlainPath(root, AnvilReader.externalFile(entities.getParent(), pos));
        var entry = readers.entities().read(pos);
        return entry == null || futureVersion(entry) || nonemptyList(entry, "Entities")
                || nonemptyList(entry, "entities")
                || !(entry.get("Entities") instanceof ListTag || entry.get("entities") instanceof ListTag);
    }

    private boolean containsPoi(ChunkPos pos) throws IOException {
        if (!readers.poi().positions().contains(pos))
            return false;
        FileProof.requirePlainPath(root, AnvilReader.externalFile(poi.getParent(), pos));
        var entry = readers.poi().read(pos);
        if (entry == null || futureVersion(entry) || !(entry.get("Sections") instanceof CompoundTag sections))
            return true;
        for (String key : sections.keySet()) {
            if (!(sections.get(key) instanceof CompoundTag section)
                    || !(section.get("Records") instanceof ListTag records) || !records.isEmpty())
                return true;
        }
        return false;
    }

    private static boolean futureVersion(CompoundTag entry) {
        return entry.getIntOr("DataVersion", 0) > SharedConstants.getCurrentVersion().dataVersion().version();
    }

    private static boolean nonemptyList(CompoundTag tag, String key) {
        return tag.contains(key) && (!(tag.get(key) instanceof ListTag list) || !list.isEmpty());
    }

    @Override
    public void close() throws IOException {
        if (readers != null)
            readers.close();
    }
}
