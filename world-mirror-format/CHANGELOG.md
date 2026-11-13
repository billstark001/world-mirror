# World Mirror Format changelog

Releases are listed newest first by release date, after unreleased changes. Use Added, Changed, and Fixed categories where applicable. Keep the root `world-mirror-format` sections synchronized with this file; changes that belong only to one consumer remain in that consumer's changelog.

## [Unreleased]

### Changed

- Move the unversioned chunk timestamp importer into the legacy package and preserve the published JVM entry point.

## [0.1.3] — 2026-11-13

### Added

- Share scoped source-priority lookup, overwrite decisions, and region read-back sequencing between live sync and offline imports.
- Expose region-bounded index reads and a batch priority snapshot for offline import planning.

### Changed

- Seed the `replay_import` source at priority 10 and preserve existing source rules. Record verified replacements of missing or physically empty chunks with their actual capture times.

## [0.1.2] — 2026-10-23

### Fixed

- Roll back checked and unchecked transaction failures without accidentally committing after a failed rollback; preserve connection-restoration errors.
- Ignore null keys in legacy timestamp input.

## [0.1.1] — 2026-09-27

### Changed

- Publish binary and source JARs as direct GitHub Release assets for public Gradle Ivy consumption.

## [0.1.0] — 2026-09-27

### Added

- Introduce the standalone Java 21+ metadata and chunk-index contract, available through composite builds and a binary/source release ZIP.
