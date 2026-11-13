package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;

import java.nio.file.Path;

final class ExportTestTargets {
    private ExportTestTargets() {}

    private static final java.util.concurrent.atomic.AtomicLong EPOCH =
            new java.util.concurrent.atomic.AtomicLong();

    static CaptureSession session(String indexSource) {
        return session("mirror:" + indexSource, "remote:" + indexSource, indexSource);
    }

    static CaptureSession session(String nodeId, String parent, String indexSource) {
        return new CaptureSession(1L, EPOCH.incrementAndGet(), parent,
                new WorldGraphService.Target(nodeId, parent, indexSource, Path.of(".")),
                new CaptureSession.Settings(ModConfig.ConflictStrategy.OVERWRITE,
                        io.github.billstark001.worldmirror.io.WorldSettingsSnapshot.defaults(), false));
    }

    static Path reserve(Path folder, ExportWork.Frozen work) throws java.io.IOException {
        java.nio.file.Files.createDirectories(folder);
        if (WorldMetadata.inspectSchema(folder) == WorldMetadata.Schema.MISSING
                && MirrorClaim.read(folder) == null) {
            var target = work.context().session().target();
            MirrorClaim.create(folder, target.nodeId(), target.parentNodeId(), target.sourceId());
        }
        return folder;
    }
}
