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

`ChunkIndexStore` initializes the existing `chunks` and `update_sources` tables,
applies the timestamp/source-priority rule, and records only completed writes.
It does not write MCA files; the caller must verify those before recording them.
