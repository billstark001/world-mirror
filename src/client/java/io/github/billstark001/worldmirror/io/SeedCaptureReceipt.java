package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.format.SeedTerrainDigest;
import net.minecraft.nbt.CompoundTag;

/** An MCA receipt lets a verified capture replace its old seed index row after a restart. */
final class SeedCaptureReceipt {
    private SeedCaptureReceipt() {}

    static void write(CompoundTag chunk, String node, long time) {
        var receipt = new CompoundTag();
        receipt.putString(SeedTerrainDigest.CAPTURE_NODE, node);
        receipt.putLong(SeedTerrainDigest.CAPTURE_TIME, time);
        receipt.putString(SeedTerrainDigest.CAPTURE_SOURCE, "world_mirror");
        receipt.putString(SeedTerrainDigest.CAPTURE_DIGEST, SeedChunkProvenance.captureDigest(chunk));
        var attachments = chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS);
        attachments.put(SeedTerrainDigest.CAPTURE_RECEIPT, receipt);
        chunk.put(SeedTerrainDigest.ATTACHMENTS, attachments);
    }

    record Receipt(String source, long time) {}

    static Receipt read(CompoundTag chunk, String node) {
        var receipt = chunk.getCompoundOrEmpty(SeedTerrainDigest.ATTACHMENTS)
                              .getCompound(SeedTerrainDigest.CAPTURE_RECEIPT)
                              .orElse(null);
        if (receipt == null || !node.equals(receipt.getStringOr(SeedTerrainDigest.CAPTURE_NODE, "")))
            return null;
        String source = receipt.getStringOr(SeedTerrainDigest.CAPTURE_SOURCE, "");
        long time = receipt.getLongOr(SeedTerrainDigest.CAPTURE_TIME, 0);
        if (time <= 0 || (!source.equals("world_mirror") && !source.equals("replay_import")))
            return null;
        try {
            return SeedChunkProvenance.captureDigest(chunk).equals(
                           receipt.getStringOr(SeedTerrainDigest.CAPTURE_DIGEST, ""))
                    ? new Receipt(source, time)
                    : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
