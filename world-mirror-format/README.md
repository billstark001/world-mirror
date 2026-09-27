# World Mirror Format 0.1.0

Small Java 21+ contract for World Mirror save metadata and the chunk durability index.
It has no Minecraft, Fabric, JSON, SQLite driver, or Maven Central dependency.
The host supplies a JDBC `Connection` and its own JSON adapter.

## Local consumption

The World Mirror and toolkit builds use Gradle composite builds (`includeBuild`) to
consume this module directly from source. No remote repository is needed for this
dependency. Run `gradle releaseBundle` to create a versioned binary/source ZIP
and `.sha256` checksum in `build/distributions/`. Pushing the tag
`world-mirror-format-v0.1.0` builds and publishes both files through the format
release workflow. Consumers outside the source tree may verify the release
checksum and use the JAR as a local Gradle file dependency.

`ChunkIndexStore` initializes the existing `chunks` and `update_sources` tables,
applies the timestamp/source-priority rule, and records only completed writes.
It does not write MCA files; the caller must verify those before recording them.
