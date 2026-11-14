package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeedConversionPackTest {
    @BeforeAll
    static void bootstrap() {
        SeedConversionTest.bootstrap();
    }

    @Test
    void managedSeedConversionPreservesCustomFilesInTheOwnedPackPath(@TempDir Path world) throws Exception {
        var target = SeedConversionTest.mirror(world);
        Path pack = world.resolve("datapacks").resolve(MirrorWorldgenAssets.PACK_DIRECTORY);
        Path extra = Files.writeString(pack.resolve("custom.json"), "{}");
        assertThrows(IOException.class,
                () -> SeedConversion.preview(target, SeedConversion.Request.set(9, false)));
        assertEquals("{}", Files.readString(extra));
        Files.delete(extra);
        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.set(9, false)));

        Files.createDirectories(pack);
        Files.writeString(extra, "{}");
        SeedConversion.apply(SeedConversion.preview(target, SeedConversion.Request.reseed(10, false)));
        assertEquals("{}", Files.readString(extra));
        assertThrows(IOException.class,
                () -> SeedConversion.preview(target, SeedConversion.Request.remove(false)));
        assertEquals(10, WorldStructureCreator.readSeed(world));
        assertEquals("{}", Files.readString(extra));
    }
}
