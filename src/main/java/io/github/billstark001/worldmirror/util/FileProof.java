package io.github.billstark001.worldmirror.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.nio.file.attribute.BasicFileAttributes;

/** Checks scoped filesystem paths and proves file contents without buffering whole files. */
public final class FileProof {
    private FileProof() {}

    /** Allows an absent directory. On Windows, junctions report both directory and other. */
    public static void requirePlainDirectory(Path path) throws IOException {
        var attributes = attributesIfPresent(path);
        if (attributes != null && !isPlainDirectory(attributes)) {
            throw new IOException("Not a plain directory: " + path);
        }
    }

    /** Allows an absent file. Links, junctions and nonregular files are rejected. */
    public static void requirePlainFileIfPresent(Path path) throws IOException {
        var attributes = attributesIfPresent(path);
        if (attributes != null && !isPlainFile(attributes)) {
            throw new IOException("Not a plain file: " + path);
        }
    }

    /** Checks an existing regular file without following a link at its final component. */
    public static BasicFileAttributes requirePlainFile(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!isPlainFile(attributes))
            throw new IOException("Not a plain file: " + path);
        return attributes;
    }

    public static boolean isPlainDirectory(BasicFileAttributes attributes) {
        return attributes.isDirectory() && !isAlias(attributes);
    }

    public static boolean isPlainFile(BasicFileAttributes attributes) {
        return attributes.isRegularFile() && !isAlias(attributes);
    }

    /** Checks existing components from a trusted root and allows creation of absent components. */
    public static void requirePlainPath(Path root, Path path) throws IOException {
        root = root.toAbsolutePath().normalize();
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(root))
            throw new IOException("Path leaves its root: " + path);
        requirePlainDirectory(root);
        Path part = root;
        for (Path segment : root.relativize(path)) {
            part = part.resolve(segment);
            var attributes = attributesIfPresent(part);
            if (attributes == null)
                return;
            if (isAlias(attributes))
                throw new IOException("Aliased path: " + part);
        }
    }

    private static boolean isAlias(BasicFileAttributes attributes) {
        // NTFS junctions can report both directory and other.
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    private static BasicFileAttributes attributesIfPresent(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    /** A cheap change token, captured under the writer's lease; it is not a content proof. */
    public record Version(long size, java.nio.file.attribute.FileTime modified, Object identity) {}

    public static Version version(Path file) throws IOException {
        BasicFileAttributes attributes = requirePlainFile(file);
        return new Version(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String sha256(Path file) throws IOException {
        return sha256(file, null);
    }

    /** Checks cancellation between bounded reads without altering the file. */
    public static String sha256(Path file, Runnable checkpoint) throws IOException {
        MessageDigest digest = sha256();
        try (var input = new DigestInputStream(
                     Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest)) {
            if (checkpoint == null) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            } else {
                byte[] buffer = new byte[64 * 1024];
                do {
                    checkpoint.run();
                } while (input.read(buffer) != -1);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Hashes during copying, then checks the stored copy without reading the source a second time. */
    public static String copyVerified(Path source, Path destination) throws IOException {
        return copyVerified(source, destination, null);
    }

    /** Checks cancellation during the copy and the read-back of the private destination. */
    public static String copyVerified(Path source, Path destination, Runnable checkpoint) throws IOException {
        Version before = version(source);
        MessageDigest digest = sha256();
        try (var input = new DigestInputStream(
                     Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS), digest)) {
            if (checkpoint == null) {
                Files.copy(input, destination);
            } else {
                copyWithCheckpoints(input, destination, checkpoint);
            }
        }
        String expected = HexFormat.of().formatHex(digest.digest());
        if (!before.equals(version(source)) || !expected.equals(sha256(destination, checkpoint))) {
            throw new IOException("Copy verification failed: " + source);
        }
        return expected;
    }

    private static void copyWithCheckpoints(DigestInputStream input, Path destination, Runnable checkpoint)
            throws IOException {
        try (var output = Files.newOutputStream(
                     destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                checkpoint.run();
                int count = input.read(buffer);
                if (count < 0)
                    break;
                output.write(buffer, 0, count);
            }
        }
    }
}
