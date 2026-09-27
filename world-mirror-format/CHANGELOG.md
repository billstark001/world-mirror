# World Mirror Format changelog

Releases are listed newest first by release date. Changes that belong only to a
single consumer remain in that consumer's changelog with an artifact prefix.

## [0.1.0] — 2026-09-27

### Added

- Introduced version 0.1.0 of the Java 21+ mirror-format contract, consumable
  through a local composite build or a release ZIP without Maven Central. It
  covers mirror paths, metadata markers, SQLite tables, source priorities,
  durability timestamps, and legacy timestamp migration.
