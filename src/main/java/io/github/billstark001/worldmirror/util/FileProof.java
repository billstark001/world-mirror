package io.github.billstark001.worldmirror.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.nio.file.attribute.BasicFileAttributes;

/** Streaming content proofs avoid retaining a second copy of large staged files. */
public final class FileProof {
    private FileProof() {}

    /** Allows an absent directory. On Windows, junctions report both directory and other. */
    public static void requirePlainDirectory(Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return;
        }
        if (!attributes.isDirectory() || attributes.isOther() || attributes.isSymbolicLink()) {
            throw new IOException("Not a plain directory: " + path);
        }
    }

    /** A cheap change token, captured under the writer's lease; it is not a content proof. */
    public record Version(long size, java.nio.file.attribute.FileTime modified, Object identity) {}

    public static Version version(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) throw new IOException("Not a regular file: " + file);
        return new Version(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
    }

    public static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Hashes during copying, then checks the stored copy without reading the source a second time. */
    public static String copyVerified(Path source, Path destination) throws IOException {
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class);
        MessageDigest digest = sha256();
        try (var input = new DigestInputStream(Files.newInputStream(source), digest)) {
            Files.copy(input, destination);
        }
        BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class);
        String expected = HexFormat.of().formatHex(digest.digest());
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(), after.fileKey())
                || !expected.equals(sha256(destination))) {
            throw new IOException("Copy verification failed: " + source);
        }
        return expected;
    }
}
