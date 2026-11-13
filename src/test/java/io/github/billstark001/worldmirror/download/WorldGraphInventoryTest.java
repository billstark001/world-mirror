package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraph.Location;
import io.github.billstark001.worldmirror.download.WorldGraph.Root;
import io.github.billstark001.worldmirror.io.WorldWriteLock;
import io.github.billstark001.worldmirror.util.JsonSupport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.DirectoryLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WorldGraphInventoryTest {
    @TempDir Path game;

    @Test
    void leavesUntrackedSavesUntouchedAndListsOnlyEligibleOriginals() throws Exception {
        Path original = save(Root.SAVES, "Untracked");
        Path malformedIdentity = save(Root.SAVES, "MalformedIdentity");
        Files.writeString(malformedIdentity.resolve(LocalWorldIdentity.FILE_NAME), "{}");
        Path mirror = save(Root.SAVES, "Mirror");
        String historicalSource = "local:Historical name";
        String parent = WorldGraphConfig.deterministicId("unresolved", historicalSource);
        JsonSupport.writeDocument(mirror.resolve(WorldMetadata.FILE_NAME),
                Map.of("format", WorldMetadata.FORMAT, "metadataSchema", 1, "sourceId", historicalSource,
                        "sourceType", "singleplayer", "mirrorId", "child", "mirrorKind", "synchronized"));
        save(Root.DOWNLOADED, "DownloadedOriginal");
        Files.createDirectory(original.resolveSibling("Empty"));

        var result = refresh();

        assertEquals(List.of(new Location(Root.SAVES, "Untracked")), result.untrackedLocalWorlds());
        assertEquals(parent, result.graph().nodes().get("mirror:child").parentId());
        try (var files = Files.list(original)) {
            assertEquals(List.of("level.dat"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void assigningAnUntrackedParentCreatesOnlyItsIdentityAndKeepsItAfterRename() throws Exception {
        Path original = save(Root.SAVES, "Original");
        Path unrelated = save(Root.SAVES, "Unrelated");
        Path child = save(Root.DOWNLOADED, "Child");
        assertTrue(WorldMetadata
                        .create("mirror:child", WorldGraph.Kind.MIRROR, "unresolved:old-local",
                                WorldGraph.Role.SYNC, "local:old namespace")
                        .save(child));
        var service = new WorldGraphService(game, game.resolve("config/worldmirror"));
        var location = new Location(Root.SAVES, "Original");
        service.refresh();
        assertTrue(service.routingView().untrackedLocalWorlds().contains(location));
        assertThrows(IOException.class, () -> service.setUpstream("mirror:absent", location));
        assertNull(LocalWorldIdentity.read(original));
        try (var ignored = DirectoryLock.create(original)) {
            assertThrows(IOException.class, () -> service.setUpstream("mirror:child", location));
            assertNull(LocalWorldIdentity.read(original));
        }

        service.setUpstream("mirror:child", location);

        String id = LocalWorldIdentity.read(original);
        assertNotNull(id);
        assertNull(LocalWorldIdentity.read(unrelated));
        assertNull(LocalWorldIdentity.read(child));
        var metadata = WorldMetadata.loadIfPresent(child).orElseThrow();
        assertEquals(id, metadata.parentNodeId);
        assertEquals("local:old namespace", metadata.sourceId);
        Path renamed = Files.move(original, original.resolveSibling("Renamed"));
        service.refresh();
        assertEquals(id, LocalWorldIdentity.read(renamed));
        assertEquals(new Location(Root.SAVES, "Renamed"), service.graph().nodes().get(id).location());
        assertFalse(service.graph().missingParents().contains(id));
    }

    @Test
    void repairsAnEmptyReservationAndResumesItAfterReopen() throws Exception {
        Path original = save(Root.SAVES, "Original");
        Path reservation = Files.createDirectories(game.resolve("downloaded_worlds/Reserved"));
        String childId = "mirror:reserved";
        String namespace = "local:historical name";
        MirrorClaim.create(reservation, childId, "unresolved:old-local", namespace);
        var before = MirrorClaim.read(reservation);
        Path config = game.resolve("config/worldmirror");
        var service = new WorldGraphService(game, config);
        service.refresh();

        service.setUpstream(childId, new Location(Root.SAVES, "Original"));

        String parentId = LocalWorldIdentity.read(original);
        assertNotNull(parentId);
        assertEquals(new MirrorClaim.Claim(before.operationId(), childId, parentId, namespace),
                MirrorClaim.read(reservation));
        assertFalse(Files.exists(reservation.resolve(WorldMetadata.FILE_NAME)));
        assertFalse(Files.exists(reservation.resolve("level.dat")));

        var reopened = new WorldGraphService(game, config);
        reopened.refresh();
        var target = reopened.prepare(new WorldGraphService.Source(parentId, "Original", null));
        assertEquals(new WorldGraphService.Target(childId, parentId, namespace, reservation), target);
    }

    @Test
    void rejectsRepairWhenAnOrdinarySaveContainsAStaleReservation() throws Exception {
        Path original = save(Root.SAVES, "Original");
        Path ordinary = save(Root.SAVES, "Ordinary");
        String childId = "mirror:stale";
        MirrorClaim.create(ordinary, childId, "unresolved:old-local", "local:historical name");
        byte[] level = Files.readAllBytes(ordinary.resolve("level.dat"));
        byte[] claim = Files.readAllBytes(ordinary.resolve(MirrorClaim.FILE_NAME));
        var service = new WorldGraphService(game, game.resolve("config/worldmirror"));
        service.refresh();

        assertThrows(
                IOException.class, () -> service.setUpstream(childId, new Location(Root.SAVES, "Original")));

        assertArrayEquals(level, Files.readAllBytes(ordinary.resolve("level.dat")));
        assertArrayEquals(claim, Files.readAllBytes(ordinary.resolve(MirrorClaim.FILE_NAME)));
        assertFalse(Files.exists(ordinary.resolve(WorldMetadata.FILE_NAME)));
        assertNull(LocalWorldIdentity.read(original));
    }

    @Test
    void detachesMovesAndDeletesWithoutRevivingTheOldReservation() throws Exception {
        String node = "mirror:child";
        String parent = "remote:parent";
        String namespace = "server:old.example";
        Path folder = save(Root.DOWNLOADED, "Managed");
        assertTrue(WorldMetadata.create(node, WorldGraph.Kind.MIRROR, parent, WorldGraph.Role.SYNC, namespace)
                        .save(folder));
        Path child = save(Root.SAVES, "Descendant");
        assertTrue(WorldMetadata
                        .create("snapshot:descendant", WorldGraph.Kind.SNAPSHOT, null,
                                WorldGraph.Role.SNAPSHOT, "snapshot:descendant")
                        .save(child));
        var service = new WorldGraphService(game, game.resolve("config/worldmirror"));
        service.refresh();
        service.setUpstream("snapshot:descendant", node);
        assertThrows(IOException.class, () -> service.setUpstream(node, "snapshot:descendant"));
        service.moveWorld("snapshot:descendant", ModConfig.SaveLocation.DOWNLOADED);
        child = game.resolve("downloaded_worlds/Descendant");
        assertEquals(node, WorldMetadata.loadIfPresent(child).orElseThrow().parentNodeId);
        service.moveWorld(node, ModConfig.SaveLocation.SAVES);
        Path moved = game.resolve("saves/Managed");
        assertTrue(Files.isRegularFile(moved.resolve("level.dat")));
        assertTrue(MirrorClaim.ownedBy(folder, node));

        service.removeUpstream(node);
        assertNull(service.graph().nodes().get(node).parentId());
        assertNull(service.targetById(node));
        assertEquals(namespace, WorldMetadata.loadIfPresent(moved).orElseThrow().sourceId);
        assertEquals(node, WorldMetadata.loadIfPresent(child).orElseThrow().parentNodeId);
        service.moveWorld(node, ModConfig.SaveLocation.DOWNLOADED);
        assertEquals(new Location(Root.DOWNLOADED, "Managed"), service.graph().nodes().get(node).location());
        assertThrows(IOException.class, () -> service.deleteWorld(node, new Location(Root.SAVES, "Managed")));
        service.deleteWorld(node, new Location(Root.DOWNLOADED, "Managed"));
        assertFalse(service.graph().nodes().containsKey(node));
        assertTrue(service.graph().missingParents().contains(node));
        assertTrue(Files.notExists(folder));
    }

    @Test
    void movesAndDeletesAnUnmaterializedReservationWithoutCreatingMetadata() throws Exception {
        String node = "mirror:claim";
        Path folder = Files.createDirectories(game.resolve("downloaded_worlds/Reservation"));
        MirrorClaim.create(folder, node, "remote:parent", "server:old.example");
        var claim = MirrorClaim.read(folder);
        var service = new WorldGraphService(game, game.resolve("config/worldmirror"));
        service.moveWorld(node, ModConfig.SaveLocation.SAVES);
        Path moved = game.resolve("saves/Reservation");
        assertEquals(claim, MirrorClaim.read(moved));
        assertFalse(Files.exists(moved.resolve(WorldMetadata.FILE_NAME)));
        assertTrue(Files.notExists(folder));
        service.deleteWorld(node, new Location(Root.SAVES, "Reservation"));
        assertTrue(Files.notExists(moved));
    }

    @Test
    void keepsKnownOriginalAndRepairsItsCopiedIdentityAfterTheCopyUnlocks() throws Exception {
        Path original = save(Root.SAVES, "Z-Original");
        String id;
        try (var ignored = WorldWriteLock.acquire(original)) {
            id = LocalWorldIdentity.ensure(original);
        }
        assertEquals(new Location(Root.SAVES, "Z-Original"), refresh().graph().nodes().get(id).location());
        Path copy = save(Root.SAVES, "A-Copy");
        Files.copy(
                original.resolve(LocalWorldIdentity.FILE_NAME), copy.resolve(LocalWorldIdentity.FILE_NAME));
        try (var ignored = DirectoryLock.create(copy)) {
            assertEquals(1, refresh().graph().duplicates().size());
        }

        var result = refresh();

        assertEquals(id, LocalWorldIdentity.read(original));
        String copyId = LocalWorldIdentity.read(copy);
        assertNotEquals(id, copyId);
        assertEquals(new Location(Root.SAVES, "A-Copy"), result.graph().nodes().get(copyId).location());
        assertTrue(result.graph().duplicates().isEmpty());
        assertTrue(result.untrackedLocalWorlds().isEmpty());
    }

    private Path save(Root root, String name) throws IOException {
        Path folder = Files.createDirectories(
                game.resolve(root == Root.SAVES ? "saves" : "downloaded_worlds").resolve(name));
        var data = new CompoundTag();
        data.put("Data", new CompoundTag());
        NbtIo.writeCompressed(data, folder.resolve("level.dat"));
        return folder;
    }

    private WorldGraphInventory.Result refresh() throws IOException {
        Path config = Files.createDirectories(game.resolve("config").resolve("worldmirror"));
        var inventory = new WorldGraphInventory(game, config, () -> ModConfig.SaveLocation.DOWNLOADED);
        var result = new AtomicReference<WorldGraphInventory.Result>();
        inventory.refresh(WorldGraphConfig.open(config.resolve(WorldGraphConfig.FILE_NAME)), result::set);
        return result.get();
    }
}
