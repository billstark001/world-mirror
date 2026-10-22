package io.github.billstark001.worldmirror.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSupportTest {
    @Test
    void writesUtf8AndCleansUpFailedSerialization(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("nested/document.json");
        JsonSupport.writePrettyJsonAtomically(file, Map.of("name", "镜像ワールド"));
        String original = Files.readString(file);
        assertTrue(original.contains("镜像ワールド"));

        assertThrows(IllegalArgumentException.class, () ->
                JsonSupport.writePrettyJsonAtomically(file, Map.of("invalid", Double.NaN)));

        assertEquals(original, Files.readString(file));
        try (var files = Files.list(file.getParent())) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void failedReplacementLeavesTheTargetAndCleansUpTheTemporaryFile(@TempDir Path directory)
            throws Exception {
        Path target = Files.createDirectory(directory.resolve("occupied.json"));
        Files.writeString(target.resolve("keep.txt"), "keep");

        assertThrows(IOException.class, () -> JsonSupport.writePrettyJsonAtomically(target, Map.of()));

        assertEquals("keep", Files.readString(target.resolve("keep.txt")));
        try (var files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void concurrentReplacementsDoNotShareATemporaryFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("shared.json");
        var tasks = new ArrayList<Callable<Void>>();
        for (int writer = 0; writer < 8; writer++) {
            int id = writer;
            tasks.add(() -> {
                for (int iteration = 0; iteration < 20; iteration++) {
                    JsonSupport.writePrettyJsonAtomically(file, Map.of("writer", id));
                }
                return null;
            });
        }
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var result : executor.invokeAll(tasks)) result.get();
        }
        try (var reader = Files.newBufferedReader(file)) {
            assertTrue(JsonSupport.fromJson(reader, Map.class).containsKey("writer"));
        }
        try (var files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
    }
}
