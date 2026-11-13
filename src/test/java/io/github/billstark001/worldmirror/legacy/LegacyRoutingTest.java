package io.github.billstark001.worldmirror.legacy;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.WorldGraphConfig;
import io.github.billstark001.worldmirror.download.MirrorClaim;
import io.github.billstark001.worldmirror.util.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRoutingTest {
    @Test
    void onlyTheV0ClaimSignatureIsAccepted(@TempDir Path folder) throws Exception {
        Path claim = folder.resolve(MirrorClaim.FILE_NAME);
        String source = "server:example.test";
        String token = UUID.randomUUID().toString();
        JsonSupport.writeDocument(claim, Map.of("sourceId", source, "token", token));
        assertEquals(source, LegacyMirrorClaim.readSource(folder));

        JsonSupport.writeDocument(
                claim, Map.of("sourceId", source, "token", token, "nodeId", "mirror:current"));
        assertNull(LegacyMirrorClaim.readSource(folder));

        JsonSupport.writeDocument(
                claim, new MirrorClaim.Claim(UUID.randomUUID(), "mirror:current", "remote:source", source));
        assertNull(LegacyMirrorClaim.readSource(folder));
    }

    @Test
    void v0ImportIncludesEntriesAndOverridesWithoutWriting(@TempDir Path root) throws Exception {
        Path file = root.resolve("mirrors.json");
        assertTrue(LegacyRouting.read(file).entries().isEmpty());
        String text = """
                {
                  "entries": {"server:EXAMPLE.test:25565":"Original","server:example.test":"Other"},
                  "resolvedFolderNames": {"server:EXAMPLE.test:25565\\u0000SAVES":"Stored"},
                  "perWorldSaveLocation": {"server:EXAMPLE.test:25565":"SAVES"},
                  "perWorldConflictStrategy": {"local:Settings only":"MANUAL"}
                }
                """;
        Files.writeString(file, text);
        var imported = LegacyRouting.read(file).entries();
        assertEquals(3, imported.size());
        assertEquals(
                new LegacyRouting.Entry("server:EXAMPLE.test:25565", "Original",
                        Map.of(ModConfig.SaveLocation.SAVES, "Stored"), ModConfig.SaveLocation.SAVES, null),
                imported.get("server:EXAMPLE.test:25565"));
        assertEquals(
                ModConfig.ConflictStrategy.MANUAL, imported.get("local:Settings only").conflictStrategy());
        assertEquals(text, Files.readString(file));
        assertFalse(Files.exists(root.resolve(WorldGraphConfig.FILE_NAME)));
    }
}
