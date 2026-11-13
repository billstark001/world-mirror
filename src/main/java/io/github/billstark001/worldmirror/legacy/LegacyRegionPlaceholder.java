package io.github.billstark001.worldmirror.legacy;

/** The v0 Anvil placeholder has no header and no chunk payloads. */
public final class LegacyRegionPlaceholder {
    private LegacyRegionPlaceholder() {}

    public static boolean isEmpty(long size) {
        return size == 0;
    }
}
