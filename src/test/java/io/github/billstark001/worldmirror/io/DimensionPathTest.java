package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.conflict.ConflictManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DimensionPathTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "/absolute", "foo/../../escape", "foo//bar", "foo/./bar"})
    void rejectsDimensionPathsThatAliasOrEscapeTheirNamespace(String path, @TempDir Path world) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("example", path));

        assertThrows(IllegalArgumentException.class, () -> ChunkExporter.dimensionDirForDimension(world, dimension));
        assertThrows(IllegalArgumentException.class, () -> ConflictManager.getConflictDir(world, dimension));
    }

    @Test
    void nestedCustomDimensionsKeepTheirExactNamespacedLayout(@TempDir Path world) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("example", "nested/caves"));

        assertEquals(world.resolve("dimensions/example/nested/caves"),
                ChunkExporter.dimensionDirForDimension(world, dimension));
        assertEquals(world.resolve("conflict_chunks/dimensions/example/nested/caves/region"),
                ConflictManager.getConflictDir(world, dimension));
    }
}
