package io.github.billstark001.worldmirror.io;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldDataPersistenceTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void sharedWriterPreservesTheLastGoodFileAfterUncheckedSerializationFailure(@TempDir Path folder)
            throws Exception {
        Path file = folder.resolve("level.dat");
        CompoundTag original = new CompoundTag();
        original.putString("marker", "original");
        WorldStructureCreator.writeVerifiedWorldData(file, original);
        byte[] bytes = Files.readAllBytes(file);
        CompoundTag malformed = new CompoundTag();
        malformed.put("malformed", null);

        assertThrows(RuntimeException.class,
                () -> WorldStructureCreator.writeVerifiedWorldData(file, malformed));

        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertEquals(original, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
        try (var files = Files.list(folder)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void silentlyTruncatedNbtStringsCannotReplaceAGoodSave(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("level.dat");
        CompoundTag original = new CompoundTag();
        original.putString("marker", "original");
        WorldStructureCreator.writeVerifiedWorldData(file, original);
        byte[] bytes = Files.readAllBytes(file);
        CompoundTag oversized = new CompoundTag();
        oversized.putString("text", "x".repeat(70_000));

        assertThrows(IOException.class, () -> WorldStructureCreator.writeVerifiedWorldData(file, oversized));

        assertArrayEquals(bytes, Files.readAllBytes(file));
        try (var files = Files.list(folder)) {
            assertEquals(1L, files.count());
        }
    }
}
