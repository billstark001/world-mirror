package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SeedChunkProvenanceTest {
    private static final UUID EPOCH = UUID.fromString("a1e511c3-5206-4c27-b4f1-0a03a262ff8d");

    @Test
    void baselineIgnoresLightingButRejectsEditedTerrainAndNativeDataUpgrade() {
        var chunk = terrain();
        var entry = SeedChunkProvenance.baseline(chunk, EPOCH, 42);
        assertTrue(SeedChunkProvenance.isUnmodified(chunk, entry));
        chunk.putLong("InhabitedTime", 999L);
        section(chunk).putByteArray("SkyLight", new byte[] {1});
        var lightOnlySection = new CompoundTag();
        lightOnlySection.putByte("Y", (byte) 1);
        lightOnlySection.putByteArray("BlockLight", new byte[] {1});
        chunk.getListOrEmpty("sections").add(lightOnlySection);
        assertTrue(SeedChunkProvenance.isUnmodified(chunk, entry));

        section(chunk).getCompoundOrEmpty("block_states").putLongArray("data", new long[] {1L});
        assertFalse(SeedChunkProvenance.isUnmodified(chunk, entry));
        var upgraded = terrain();
        upgraded.putInt("DataVersion", 43);
        assertFalse(SeedChunkProvenance.isUnmodified(upgraded, entry));
    }

    @Test
    void stripRemovesOnlyOwnedMetadataAndItsBlending() {
        var chunk = terrain();
        var entry = SeedChunkProvenance.baseline(chunk, EPOCH, 42);
        var payload = new CompoundTag();
        payload.putInt(SeedTerrainDigest.SEED_SCHEMA, SeedTerrainDigest.SEED_SCHEMA_VERSION);
        payload.putString(SeedTerrainDigest.EPOCH, EPOCH.toString());
        payload.putInt(SeedTerrainDigest.DATA_VERSION, 42);
        payload.putString(SeedTerrainDigest.BASELINE, entry.baseline());
        payload.putBoolean(SeedTerrainDigest.SEED_EDITED, false);
        var attachments = new CompoundTag();
        attachments.put(SeedTerrainDigest.ATTACHMENT, payload);
        attachments.putString("fixture:other", "preserved");
        chunk.put(SeedTerrainDigest.ATTACHMENTS, attachments);
        chunk.put(SeedTerrainDigest.BLENDING_DATA, new CompoundTag());
        assertEquals(entry, SeedChunkProvenance.read(chunk));
        payload.putBoolean(SeedTerrainDigest.SEED_EDITED, true);
        var edited = SeedChunkProvenance.read(chunk);
        assertTrue(edited.edited());
        assertEquals(entry.baseline(), edited.baseline());
        assertFalse(SeedChunkProvenance.isUnmodified(chunk, edited));
        payload.putInt(SeedTerrainDigest.SEED_SCHEMA, 99);
        assertNull(SeedChunkProvenance.read(chunk));
        SeedChunkProvenance.strip(chunk);
        assertTrue(chunk.contains(SeedTerrainDigest.BLENDING_DATA));

        attachments.putString(SeedTerrainDigest.BLENDING_ATTACHMENT, EPOCH.toString());
        attachments.put(SeedTerrainDigest.CAPTURE_RECEIPT, new CompoundTag());
        SeedChunkProvenance.strip(chunk);
        assertFalse(chunk.contains(SeedTerrainDigest.BLENDING_DATA));
        assertEquals("preserved", attachments.getStringOr("fixture:other", ""));
        assertEquals(1, attachments.size());
        assertNull(SeedChunkProvenance.read(chunk));
    }

    private static CompoundTag terrain() {
        var chunk = new CompoundTag();
        chunk.putInt("DataVersion", 42);
        var section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        section.put("block_states", new CompoundTag());
        var sections = new ListTag();
        sections.add(section);
        chunk.put("sections", sections);
        return chunk;
    }

    private static CompoundTag section(CompoundTag chunk) {
        return (CompoundTag) chunk.getListOrEmpty("sections").getFirst();
    }
}
