package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;

import java.nio.file.Path;

final class ExportTestTargets {
    private ExportTestTargets() {}

    static ExportWork.Source source(String indexSource) {
        return new ExportWork.Source(new WorldGraphService.Target("mirror:" + indexSource,
                                             "remote:" + indexSource, indexSource, Path.of(".")),
                ModConfig.ConflictStrategy.OVERWRITE);
    }

    static Path reserve(Path folder, ExportWork.Frozen work) throws java.io.IOException {
        java.nio.file.Files.createDirectories(folder);
        if (WorldMetadata.inspectSchema(folder) == WorldMetadata.Schema.MISSING
                && MirrorClaim.read(folder) == null) {
            var target = work.context().source().target();
            MirrorClaim.create(folder, target.nodeId(), target.parentNodeId(), target.sourceId());
        }
        return folder;
    }
}
