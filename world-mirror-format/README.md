# World Mirror Format 0.1.1

Small Java 21+ contract for World Mirror save metadata and the chunk durability index.
It has no Minecraft, Fabric, JSON, SQLite driver, or Maven Central dependency.
The host supplies a JDBC `Connection` and its own JSON adapter.

## Gradle consumption

The release workflow publishes binary and source JARs as public GitHub Release
assets. Gradle can resolve them in a fresh checkout with an Ivy repository:

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
    implementation("io.github.billstark001.worldmirror:world-mirror-format:0.1.1")
}
```

The mod can still consume the module directly from source through `includeBuild`.
Run `gradle releaseBundle` to create the binary/source ZIP and checksum in
`build/distributions/`. Pushing `world-mirror-format-v0.1.1` publishes the ZIP,
checksum, and direct JAR assets without Maven Central.

The transaction behavior below describes the current source checkout. The
[Unreleased changelog](CHANGELOG.md#unreleased) lists fixes after the published
0.1.1 release; consume the local composite build to use them before the next
independent format release. Bumping the mod version does not release this library.

`ChunkIndexStore` initializes the existing `chunks` and `update_sources` tables,
applies the timestamp/source-priority rule, and records only completed writes.
It does not write MCA files; the caller must verify those before recording them.
The host must serialize access to its JDBC connection. Mutating operations require
auto-commit mode and own their transaction; nested transactions are rejected.
They roll back on checked and unchecked failures. If rollback or restoring
auto-commit fails, the original error retains that failure as suppressed context;
the host should discard the connection. Auto-commit is left disabled after a
failed rollback to avoid committing a partially applied batch.
