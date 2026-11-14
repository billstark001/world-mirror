package io.github.billstark001.worldmirror.io;

#if MC_VERSION >= 260102
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the saved-data clock format on the 26.x targets. */
class WorldClocksDataTest {
    @TempDir Path tempDir;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void generatedPayloadRoundTripsThroughMinecraftCodec() throws Exception {
        CompoundTag data = WorldClockData.createWorldClocksData(6_000L);

        assertEquals(Set.of("minecraft:overworld", "minecraft:the_end"), data.keySet());
        assertFalse(data.contains("clocks"));
        assertEquals(data, WorldClockData.roundTripWorldClocksData(data));
    }

    @Test
    void repairsOnlyTheExactWorldMirrorWrapper() throws Exception {
        Path file = tempDir.resolve("data/minecraft/world_clocks.dat");
        Files.createDirectories(file.getParent());
        CompoundTag expected = WorldClockData.createWorldClocksData(6_000L);
        CompoundTag malformedData = new CompoundTag();
        malformedData.put("clocks", expected.copy());
        writeSavedData(file, malformedData);

        WorldClockData.repairOwnedSavedData(tempDir);

        CompoundTag repaired =
                NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data");
        assertEquals(expected, repaired);
        assertEquals(repaired, WorldClockData.roundTripWorldClocksData(repaired));

        CompoundTag nonExact = new CompoundTag();
        nonExact.put("clocks", expected.copy());
        nonExact.putBoolean("third_party_marker", true);
        writeSavedData(file, nonExact);
        WorldClockData.repairOwnedSavedData(tempDir);
        CompoundTag preserved =
                NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data");
        assertTrue(preserved.contains("clocks"));
        assertTrue(preserved.getBooleanOr("third_party_marker", false));
    }

    private static void writeSavedData(Path file, CompoundTag data) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        WorldStructureCreator.writeVerifiedWorldData(file, root);
    }
}
#endif
