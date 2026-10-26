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
    void malformedUtf8IsInvalidAndItsOriginalBytesRemainIntact(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("invalid.json");
        byte[] bytes = {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}'};
        Files.write(file, bytes);
        assertEquals(JsonSupport.ReadState.INVALID, JsonSupport.readDocument(file, Map.class).state());
        org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test
    void floatDecimalsAndExplicitNullsKeepTheirSerializedMeaning(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("biome.json");
        JsonSupport.writeDocument(file, Map.of("temperature", 0.8F));
        var value = JsonSupport.readDocument(file, com.google.gson.JsonObject.class).value();
        assertEquals(0.8, value.get("temperature").getAsDouble());
        var explicit = new com.google.gson.JsonObject();
        explicit.add("optional", com.google.gson.JsonNull.INSTANCE);
        JsonSupport.writeDocument(file, explicit);
        assertEquals(JsonSupport.toPrettyJson(explicit), Files.readString(file));
    }

    @Test
    void writesUtf8AndCleansUpFailedSerialization(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("nested/document.json");
        JsonSupport.writeDocument(file, Map.of("name", "镜像ワールド"));
        String original = Files.readString(file);
        assertTrue(original.contains("镜像ワールド"));

        assertThrows(IllegalArgumentException.class, () ->
                JsonSupport.writeDocument(file, Map.of("invalid", Double.NaN)));

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

        assertThrows(IOException.class, () -> JsonSupport.writeDocument(target, Map.of()));

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
                    JsonSupport.writeDocument(file, Map.of("writer", id));
                }
                return null;
            });
        }
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var result : executor.invokeAll(tasks)) result.get();
        }
        assertTrue(JsonSupport.readDocument(file, Map.class).value().containsKey("writer"));
        try (var files = Files.list(directory)) {
            assertEquals(1L, files.count());
        }
    }
}
