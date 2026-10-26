package io.github.billstark001.worldmirror.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.billstark001.worldmirror.util.FileProof;

/** Negative validation cache only: unchanged malformed data is not decoded on every retry. */
public final class FileReadFailures {
    public static final class InvalidData extends IOException {
        public InvalidData(String message) { super(message); }
        public InvalidData(String message, Throwable cause) { super(message, cause); }
    }
    private record Failure(FileProof.Version fingerprint, IOException error) {}
    private static final Map<Path, Failure> FAILURES = new LinkedHashMap<>();
    private static final int MAX_FAILURES = 256;

    private FileReadFailures() {}

    public static synchronized void invalidate() { FAILURES.clear(); }

    static FileProof.Version check(Path file) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        Failure failure;
        synchronized (FileReadFailures.class) { failure = FAILURES.get(key); }
        if (Files.notExists(key)) {
            synchronized (FileReadFailures.class) { FAILURES.remove(key, failure); }
            return null;
        }
        FileProof.Version current = FileProof.version(key);
        if (failure != null && failure.fingerprint().equals(current)) throw failure.error();
        if (failure != null) synchronized (FileReadFailures.class) { FAILURES.remove(key, failure); }
        return current;
    }

    static void record(Path file, FileProof.Version observed, IOException error) {
        if (!(error instanceof InvalidData || error instanceof java.io.EOFException
                || error instanceof java.util.zip.ZipException)) return;
        try {
            Path key = file.toAbsolutePath().normalize();
            if (observed == null || !observed.equals(FileProof.version(key))) return;
            synchronized (FileReadFailures.class) {
                FAILURES.put(key, new Failure(observed, error));
                while (FAILURES.size() > MAX_FAILURES) FAILURES.remove(FAILURES.keySet().iterator().next());
            }
        } catch (IOException unavailable) {
            // Access/lock errors remain transient; no fabricated fingerprint or empty result.
        }
    }

}
