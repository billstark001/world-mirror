package io.github.billstark001.worldmirror.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Shared JSON serialization policy for model-backed World Mirror documents. */
public final class JsonSupport {
    private static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;
    public enum ReadState { MISSING, VALID, INVALID, UNAVAILABLE }
    public record Document<T>(ReadState state, T value, Exception failure) {}
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

    /** Missing and unreadable documents have different meanings at every write boundary. */
    public static <T> Document<T> readDocument(Path path, Class<T> modelType) {
        if (Files.notExists(path)) {
            try {
                if (FileTransaction.hasPending(path.getParent())) {
                    return new Document<>(ReadState.UNAVAILABLE, null, new IOException("Pending recovery: " + path));
                }
                return new Document<>(ReadState.MISSING, null, null);
            } catch (IOException unavailable) {
                return new Document<>(ReadState.UNAVAILABLE, null, unavailable);
            }
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
            if (bytes.length > MAX_DOCUMENT_BYTES) throw new IOException("JSON document exceeds allocation budget: " + path);
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            T value = PRETTY.fromJson(text, modelType);
            if (value == null) return new Document<>(ReadState.INVALID, null, new IOException("Null JSON document: " + path));
            return new Document<>(ReadState.VALID, value, null);
        } catch (com.google.gson.JsonParseException | java.nio.charset.CharacterCodingException invalid) {
            return new Document<>(ReadState.INVALID, null, invalid);
        } catch (IOException | RuntimeException unavailable) {
            return new Document<>(ReadState.UNAVAILABLE, null, unavailable);
        }
    }

    public static String toPrettyJson(Object model) {
        return PRETTY.toJson(model);
    }

    /**
     * Replaces a UTF-8 JSON document through a unique, same-directory temporary
     * file. Serialization failures leave the previous document intact. Atomic
     * replacement is used when the filesystem supports it. Writes in this
     * process share bounded locks to avoid racing Windows file replacements;
     * callers still own the synchronization of changes to their model objects.
     */
    public static FileProof.Version writeDocument(Path destination, Object model) throws IOException {
        return replaceDocument(destination, model, false, null);
    }

    /** Rejects a stale routing/configuration snapshot under the same authority as publication. */
    public static FileProof.Version compareAndWriteDocument(Path destination, Object model, FileProof.Version expected) throws IOException {
        return replaceDocument(destination, model, true, expected);
    }

    private static FileProof.Version replaceDocument(Path destination, Object model, boolean conditional,
                                                     FileProof.Version expected) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        synchronized (WRITE_LOCKS[Math.floorMod(target.hashCode(), WRITE_LOCKS.length)]) {
            try (FileTransaction transaction = new FileTransaction(target.getParent())) {
                if (conditional) {
                    FileProof.Version current;
                    try { current = FileProof.version(target); }
                    catch (java.nio.file.NoSuchFileException missing) { current = null; }
                    if (!java.util.Objects.equals(expected, current)) throw new IOException("Document changed before publication: " + target);
                }
                Path staged = transaction.stagingDirectory().resolve(target.getFileName());
                // Serialize the model once. Parse the serialized form because Gson's
                // Float tree values and their decimal JSON round trips need not compare equal.
                String serialized = PRETTY.toJson(model);
                Files.writeString(staged, serialized, StandardCharsets.UTF_8);
                var expectedDocument = com.google.gson.JsonParser.parseString(serialized);
                var verified = readDocument(staged, com.google.gson.JsonElement.class);
                if (verified.state() != ReadState.VALID || !expectedDocument.equals(verified.value())) {
                    throw new IOException("JSON staging verification failed: " + target, verified.failure());
                }
                return transaction.commit(java.util.Map.of(target.getFileName(), staged)).get(target.getFileName());
            }
        }
    }

    public static String toCompactJsonWithNulls(Object model) {
        return COMPACT_WITH_NULLS.toJson(model);
    }
}
