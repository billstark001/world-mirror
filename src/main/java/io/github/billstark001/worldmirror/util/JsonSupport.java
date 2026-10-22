package io.github.billstark001.worldmirror.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** Shared JSON serialization policy for model-backed World Mirror documents. */
public final class JsonSupport {
    private static final Object[] WRITE_LOCKS = new Object[64];
    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Gson COMPACT_WITH_NULLS = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    static {
        Arrays.setAll(WRITE_LOCKS, ignored -> new Object());
    }

    private JsonSupport() { }

    public static <T> T fromJson(Reader source, Class<T> modelType) {
        return PRETTY.fromJson(source, modelType);
    }

    public static String toPrettyJson(Object model) {
        return PRETTY.toJson(model);
    }

    public static void writePrettyJson(Writer destination, Object model) {
        PRETTY.toJson(model, destination);
    }

    /**
     * Replaces a UTF-8 JSON document through a unique, same-directory temporary
     * file. Serialization failures leave the previous document intact. Atomic
     * replacement is used when the filesystem supports it. Writes in this
     * process share bounded locks to avoid racing Windows file replacements;
     * callers still own the synchronization of changes to their model objects.
     */
    public static void writePrettyJsonAtomically(Path destination, Object model) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        synchronized (WRITE_LOCKS[Math.floorMod(target.hashCode(), WRITE_LOCKS.length)]) {
            replacePrettyJson(target, model);
        }
    }

    private static void replacePrettyJson(Path target, Object model) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writePrettyJson(writer, model);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static String toCompactJsonWithNulls(Object model) {
        return COMPACT_WITH_NULLS.toJson(model);
    }
}
