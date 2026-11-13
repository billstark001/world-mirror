# World Mirror Format 0.1.3

Small Java 21+ contract for World Mirror save metadata and the chunk durability index. It has no Minecraft, Fabric, JSON, SQLite driver, or Maven Central dependency. The host supplies a JDBC `Connection` and its own JSON adapter.

## Gradle consumption

The release workflow publishes binary and source JARs as public GitHub Release assets. Gradle can resolve them in a fresh checkout with an Ivy repository:

```kotlin
repositories {
    ivy {
        url = uri("https://github.com/billstark001/world-mirror/releases/download")
        patternLayout {
            artifact("world-mirror-format-v[revision]/[artifact]-[revision](-[classifier]).[ext]")
        }
        metadataSources { artifact() }
        content { includeGroup("io.github.billstark001.worldmirror") }
    }
}

dependencies {
    implementation("io.github.billstark001.worldmirror:world-mirror-format:0.1.3")
}
```

The mod consumes the module directly from source through `includeBuild`. Run `gradle releaseBundle` to create the binary/source ZIP and checksum in `build/distributions/`. Pushing `world-mirror-format-v0.1.3` publishes the ZIP, checksum, and direct JAR assets without Maven Central.

Use the Ivy dependency after that version's release assets have been published. A local composite build can be used during development. The [changelog](CHANGELOG.md) records changes to the library independently of the mod.

`ChunkIndexStore` initializes the existing `chunks` and `update_sources` tables, applies the timestamp/source-priority rule, and records only completed writes. It does not write MCA files; the caller must verify those before recording them. The host must serialize access to its JDBC connection. Mutating operations require auto-commit mode and own their transaction; nested transactions are rejected. They roll back on checked and unchecked failures. If rollback or restoring auto-commit fails, the original error retains that failure as suppressed context; the host should discard the connection. Auto-commit is left disabled after a failed rollback to avoid committing a partially applied batch.

The unversioned JSON timestamp import implementation lives in `io.github.billstark001.worldmirror.legacy`. The published `ChunkIndexStore.migrateLegacyTimes` JVM entry point calls that implementation. The importer treats the map as v0 and leaves existing rows intact.

`ChunkUpdate` carries the capture information for one chunk. The index is bound to the chosen mirror `sourceId`. The `updateSource` field identifies how the chunk was acquired. For a batch, use `ChunkIndexStore.queryRegion` and `sourcePriorities` with `ChunkUpdatePolicy.assess`, then pass the result and the destination's physical state to `ChunkUpdatePolicy.decide`. The `merge` package sequences region writes and read-back verification for live sync and offline import. Callers hold the save lock, supply their own MCA reader and writer, and record only the returned verified entries.
