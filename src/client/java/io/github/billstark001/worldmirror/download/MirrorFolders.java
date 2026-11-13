package io.github.billstark001.worldmirror.download;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

final class MirrorFolders {
    private MirrorFolders() {}

    /** Characters that are NOT safe for folder names and will be replaced with underscores. */
    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9._\\-]");
    private static final Pattern REPEATED_UNDERSCORES = Pattern.compile("_+");
    private static final Pattern EDGE_UNDERSCORES = Pattern.compile("^_+|_+$");
    private static final Pattern TRAILING_DOTS = Pattern.compile("[. ]+$");
    private static final Pattern INVALID_FOLDER_CHARACTERS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Pattern RESERVED_FOLDER_NAME = Pattern.compile(
            "(?:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?", Pattern.CASE_INSENSITIVE);
    private static final int MAX_GENERATED_FOLDER_LENGTH = 64;

    static String generate(String label) {
        String clean = UNSAFE.matcher(label).replaceAll("_");
        clean = EDGE_UNDERSCORES.matcher(REPEATED_UNDERSCORES.matcher(clean).replaceAll("_")).replaceAll("");
        return sanitiseFolderName(clean, "unnamed");
    }

    static Path available(Path base, String name) {
        requireFolderName(name);
        Path candidate = base.resolve(name);
        for (int suffix = 2; !Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS); suffix++) {
            if (suffix == Integer.MAX_VALUE) throw new IllegalStateException("No free mirror folder in " + base);
            candidate = base.resolve(name + "_" + suffix);
        }
        return candidate;
    }

    static Path claim(Path base, String name) throws IOException {
        requireFolderName(name);
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            Path candidate = base.resolve(suffix == 1 ? name : name + "_" + suffix);
            try { return Files.createDirectory(candidate); }
            catch (FileAlreadyExistsException occupied) { /* Another save owns this path. */ }
        }
        throw new IOException("No free mirror folder in " + base);
    }

    /** Filename policy shared by persistent mirrors and one-shot nearby exports. */
    static String sanitiseFolderName(String name, String fallback) {
        name = INVALID_FOLDER_CHARACTERS.matcher(name).replaceAll("_").strip();
        int end = Math.min(name.length(), MAX_GENERATED_FOLDER_LENGTH);
        if (end < name.length() && end > 0 && Character.isHighSurrogate(name.charAt(end - 1))) end--;
        name = TRAILING_DOTS.matcher(name.substring(0, end)).replaceAll("");
        if (name.isBlank()) name = fallback;
        return RESERVED_FOLDER_NAME.matcher(name).matches() ? '_' + name : name;
    }

    static boolean isUsableFolderName(String name) {
        return name != null && !name.isBlank() && name.length() <= 255
                && !name.equals(".") && !name.equals("..")
                && !name.endsWith(".") && !name.endsWith(" ")
                && !INVALID_FOLDER_CHARACTERS.matcher(name).find()
                && !RESERVED_FOLDER_NAME.matcher(name).matches();
    }

    static void requireFolderName(String name) {
        if (!isUsableFolderName(name)) throw new IllegalArgumentException("Invalid mirror folder name");
    }

}
