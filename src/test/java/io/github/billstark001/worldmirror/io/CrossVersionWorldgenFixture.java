package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.download.WorldMetadata;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs in a separate target JVM: the fixture is written by real 26.2 APIs, not relabelled 26.3 NBT. */
public final class CrossVersionWorldgenFixture {
    private CrossVersionWorldgenFixture() { }

    public static void main(String[] arguments) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (!"26.2".equals(SharedConstants.getCurrentVersion().name())) {
            throw new IllegalStateException("Wrong fixture runtime: " + SharedConstants.getCurrentVersion().name());
        }
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        Path world = Files.createTempDirectory(output, "world-");
        if (!WorldStructureCreator.createLoadableWorld(world, "26.2 fixture", true, true,
                WorldSettingsSnapshot.defaults())) throw new IllegalStateException("Fixture creation failed");
        WorldMetadata metadata = WorldMetadata.create("local:cross-version", "singleplayer", "synchronized");
        metadata.markWorldgenCurrent(SharedConstants.getCurrentVersion().dataVersion().version(), MirrorWorldgenAssets.ASSET_REVISION);
        if (!metadata.save(world)) throw new IllegalStateException("Fixture metadata failed");
        Files.writeString(output.resolve("fixture-root.txt"), world.toAbsolutePath().toString());
    }
}
