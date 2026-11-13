package io.github.billstark001.worldmirror.ui;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.DirectoryLock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionRowDataTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void readsVanillaNameAndInfoEvenWhenSaveIsInUse(@TempDir Path game) throws Exception {
        String relative = "downloaded_worlds/generated_folder";
        Path save = Files.createDirectories(game.resolve(relative));
        var world = new CompoundTag();
        var data = new CompoundTag();
        var version = new CompoundTag();
        version.putInt("Id", SharedConstants.getCurrentVersion().dataVersion().version());
        version.putString("Name", "Example Version");
        version.putString("Series", SharedConstants.getCurrentVersion().dataVersion().series());
        data.put("Version", version);
        data.putInt("version", 19133);
        data.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        data.putString("LevelName", "Display Name");
        data.putInt("GameType", 1);
        data.putBoolean("allowCommands", true);
        data.putLong("LastPlayed", 1_720_000_000_000L);
        world.put("Data", data);
        NbtIo.writeCompressed(world, save.resolve("level.dat"));

        try (DirectoryLock ignored = DirectoryLock.create(save)) {
            var row = SelectionRowData.world(game, save, relative);
            assertNotNull(row);
            assertEquals("Display Name", row.name().getString());
            assertTrue(row.pathAndDate().getString().startsWith(relative + " ("));
            assertTrue(row.info().getString().contains("Creative Mode"));
            assertTrue(row.info().getString().contains("Commands"));
            assertTrue(row.info().getString().contains("Example Version"));
        }
    }

    @Test
    void reservationsAndUnsavedAddressesHaveNoVanillaMetadata(@TempDir Path game) throws Exception {
        String relative = "saves/reservation";
        Path reservation = Files.createDirectories(game.resolve(relative));

        assertNull(SelectionRowData.world(game, reservation, relative));
        assertNull(SelectionRowData.worldIcon(game, reservation));
        assertTrue(SelectionRowData.servers(game, Set.of("example.org")).isEmpty());
        assertTrue(Files.notExists(reservation.resolve("session.lock")));
    }
}
