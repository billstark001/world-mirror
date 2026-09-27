package io.github.billstark001.worldmirror.format;

/** Stable paths and markers shared by World Mirror save readers and writers. */
public final class MirrorFormat {
    public static final String METADATA_FILE = "worldmirror_meta.json";
    public static final String DATABASE_FILE = "data/world_mirror.sqlite";
    public static final String FORMAT = "worldmirror";
    public static final int METADATA_SCHEMA = 1;
    public static final int WORLDGEN_SCHEMA = 1;

    private MirrorFormat() { }
}
