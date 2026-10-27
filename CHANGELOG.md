# Changelog

All notable changes to World Mirror are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Mod and format-library releases share one reverse-chronological timeline. Mod
releases use `## [version]`; format-library releases use
`## world-mirror-format [version]`. Changes shared by every Minecraft target
of the mod have no prefix. Changes limited to one target use
`[fabric-<minecraft-version>]` at the start of the entry. The format library
also ships its own changelog in its release ZIP; keep its entries synchronized
with the root format sections. Unreleased format changes precede dated releases.

Use Added, Changed, and Fixed categories where applicable. Combine related
changes without dropping compatibility requirements or migration guidance.

---

## world-mirror-format [0.1.2] — 2026-10-23

### Fixed

- Roll back checked and unchecked transaction failures without accidentally
  committing after a failed rollback; preserve connection-restoration errors.
- Ignore null keys in legacy timestamp input.

---

## [0.5.2] — 2026-10-23

### Added

- Show queued, writing, failed, partially saved, and fully saved export markers
  across chunk maps and world overlays; retain their existing base colors.
- Surface automatic/manual export and file-operation failures through merged,
  rate-limited chat and toast notices, with current health on the status screen.

### Changed

- Reduce entity snapshot copying and index reconciliation by chunk, keeping
  small observations fast even after a long capture session.
- Share spatial queries and bucket summaries across chunk maps and world
  overlays; publish changed export-state regions and bound coarse-view refresh
  and marker work with large capture histories.
- Use purple conflict borders, reserving red export markers for failures.
- Replace the vendored NBT stack with bounded native codecs and read-only MCA
  access; stream entity rewrites and apply conflicts in bounded batches.
- Keep routing previews off disk-write locks and skip unchanged folder claims.

### Fixed

- Detect obsolete mirror generators despite current metadata and offer backed-up
  repair; preserve valid customizations and refuse unknown generator edits.
- Preserve captured terrain, lighting, entities, and inventories across source
  changes and concurrent exports. Resume interrupted capture scans and retain
  confirmed block removals under queue pressure.
- Retry only unfinished mirror data, including after disconnecting. Preserve
  each source's terrain/entity order while letting newer terrain proceed past
  entity-only failures.
- Protect open saves with Minecraft's session lock across Windows and Linux.
  Preserve damaged region headers and healthy neighboring durability claims.
  Recover interrupted file sets without overwriting unknown edits or undoing
  verified commits after cleanup failures; retain legacy timestamps when migration
  or metadata persistence fails.
- Preserve damaged configuration and optional saved data, defer overwrites when
  priority rules are unreadable, and keep independent terrain saves running.
- Validate mirror identities and relocation plans before moving files; recover
  routing consistently and reject stale mapping updates.
- Resolve conflicts without lock-order deadlocks, support external chunk files,
  and retain failed or newer incoming conflicts for review.
- Correct entity moves, detached passengers, and observations that revert during
  an export. Preserve legacy UUID trees missing type IDs while rejecting malformed
  new entity observations and oversized NBT strings.
- Preserve container names and inventories without applying stale data across
  worlds or block-entity types, or restoring block entities into confirmed air.
- Refresh map aggregation after panning and bound work for extreme viewports.
- Handle unsafe folder names and custom dimension paths; preserve nearby-export
  entities, Unicode names, spawn dimensions, and independent lineage.
- Finish accepted exports and one final queued pass on exit without interrupting
  atomic writes or keeping an idle export worker alive.

### Known Issues

- The in-world overlay does not work when shaders are enabled.

---

## [0.5.1] — 2026-10-18

### Added

- Add Session and Map overlay color modes. Session uses capture-session colors;
  Map shares the built-in and Xaero maps' age, source, and queue colors.

### Changed

- Default new overlay configurations to Y=96 while preserving existing heights.

### Fixed

- Keep replay imports historical in Session mode, regardless of timestamp, and
  retain historical cached coverage between sessions.

---

## [0.5.0] — 2026-10-18

### Added

- Add the in-world coverage overlay, with separate source/mirror switches and a
  **K** toggle. Thanks to [@cev-api](https://github.com/cev-api) for
  [PR #14](https://github.com/billstark001/world-mirror/pull/14).

### Changed

- Add adjustable height, distance, same-status grid, opacity, and F1 behavior.
  Default to a 128-chunk radius and 60% opacity; aggregate distant tiles.
- Keep enum settings selection-only and use direct input for the overlay's
  8–16384 chunk radius.
- Keep Xaero optional in development clients; opt into its matching bridge
  runtime with `-PxaeroIntegration=true`.

### Fixed

- Correct box geometry, grid visibility, and distant outlines.
- Fix transparency ordering and doubled fill opacity on the classic renderer;
  retain native improved-transparency rendering on Minecraft 26.3.

---

## [0.4.6] — 2026-10-05

### Added

- Show live cached chunks and capture/write queues on the built-in and Xaero
  maps, distinguishing whether a saved record already exists.
- Show separate saved, cached, and pending-write counts in the status screen.

### Changed

- Use the capture-age gradient for replay imports while retaining their source
  in tooltips.
- Use capture timestamps for new last-sync values; leave existing metadata
  timestamps unchanged.

### Fixed

- Fix concurrent map refreshes and sparse-view performance.
- Guard Xaero rendering against extreme zoom and invalid projections.

---

## world-mirror-format [0.1.1] — 2026-09-27

### Changed

- Publish binary and source JARs as direct GitHub Release assets for public
  Gradle Ivy consumption.

---

## [0.4.5] — 2026-09-27

### Changed

- Share the SQLite contract through the standalone format module.
- Require matching Xaero bridge 0.1.2 artifacts for every target during builds
  and IDEA synchronization, and use a localized mirror badge.
- Unify worldgen asset revision 3 and retain the backed-up upgrade path.

### Fixed

- [fabric-26.3] Select the matching bridge instead of the incompatible 26.2 file.
- [fabric-26.3] Fix noise-generator settings that prevented mirrors from loading;
  refresh affected worldgen through the normal upgrade flow.
- Refresh the status screen's last-sync time after completed exports.

---

## world-mirror-format [0.1.0] — 2026-09-27

### Added

- Introduce the standalone Java 21+ metadata and chunk-index contract, available
  through composite builds and a binary/source release ZIP.

---

## [0.4.4] — 2026-09-19

### Added

- Add the Minecraft 26.3 target and biome data-pack generation, including
  dynamically registered biomes such as the Dappled Forest.

### Changed

- Update chunk, lighting, keybinding, and clock adapters for Minecraft 26.3.
- Generate runtime and data-pack documents through typed JSON models.

---

## [0.4.3] — 2026-08-29

### Changed

- Invalidate successfully exported terrain by default.
- Include global and per-world configuration snapshots in performance diagnostics.

### Fixed

- Enable Done immediately after changing an enum dropdown.

---

## [0.4.2] — 2026-08-25

### Changed

- Replace editable/cycling enum controls with translated selection-only lists
  across global, nested, and per-world settings.
- Close lists consistently on selection, focus loss, outside click, and Escape.
  Use compact labels, expansion arrows, and ellipsis without a hidden text caret.

---

## [0.4.1] — 2026-08-23

### Changed

- Use vanilla entity serialization with complete passenger trees.
- Track versioned partial/complete observations independently of terrain
  retention, with atomic entity-region merging.

### Fixed

- Reconcile entity moves and safe despawns while preserving unobserved data.
  Verify region writes before acknowledgement and retain failed revisions.
- Handle empty entity-region placeholders without aborting other writes.
- Preserve stop-time terrain, entity, and container snapshots deferred behind
  an active export.
- Show an open mirror's original source and last successful sync.
- Copy source time, weather, and difficulty by default, with configurable
  fallbacks and separate manual sync actions.
- Repair the exact malformed world-clock wrapper written by 0.4.0 on Minecraft
  26.1.2 and 26.2, leaving unrelated clock payloads unchanged.

---

## [0.4.0] — 2026-08-23

### Added

- Add the opt-in adaptive pipeline with backlog and maximum-latency triggers;
  retain stable periodic export as the default.
- Capture chunk, block, biome, lighting, container, and unload changes through a
  bounded queue with configurable main-thread budgets and export cooldowns.
- Add optional performance diagnostics for capture latency, export stages,
  queue pressure, and JVM memory/GC use.

### Changed

- Stream terrain and entity regions with lower memory use. Reserve nearby
  rescanning for explicit exports.
- Separate capture, export, mapping, and lifecycle ownership with thin
  Minecraft-version adapters. Apply pipeline changes on the next activation.

### Fixed

- Retain unsaved changes until region verification and SQLite commit succeed;
  prevent older exports from acknowledging newer revisions.
- Preserve failed manual conflicts and isolate caches across source changes.
- Reconcile visible entity removals without treating unloaded chunks as empty.

---

## [0.3.0] — 2026-07-29

### Added

- Build Minecraft 1.21.11, 26.1.2, and 26.2 from shared sources and collect their
  distributable JARs with the Modrinth helper.
- Introduce optional Xaero World Map Bridge integration without bundling it.
- Add confirmed per-world mirror relocation and nearby-export lineage choices.
- Detect open mirrors and offer explicit, backed-up upgrades before loading
  outdated saves.

### Changed

- Move the Java package to `io.github.billstark001.worldmirror` and replace
  LibGui with native screens.
- Improve map aggregation, status layout, and translations in all four locales.

### Fixed

- Correct status-screen colors, opening flashes, map boundaries, and the Xaero
  current-mirror indicator.
- Replace legacy flat-void generation with per-dimension environment assets,
  preserving captured terrain and cleaning up only blank legacy void chunks.
- Separate worldgen schema and asset revisions, reject newer formats, and refresh
  generated data packs through backed-up upgrades.
- Defer World Mirror upgrades until Minecraft's own save-version upgrade finishes
  and restore the world list correctly on cancellation.

---

## [0.2.3] — 2026-06-22

### Changed

- Improve development helpers for Xaero version selection, disabled JAR reuse,
  disassembly, and explicit build cleanup.

### Fixed

- Fix Xaero 1.41.1 integration on Minecraft 26.2.
- Add configurable exact/fallback injection and capability reporting so
  unsupported Xaero builds do not crash startup or draw duplicate overlays.

---

## [0.2.2] — 2026-06-21

### Added

- Add Xaero status rendering, its settings, and a development download helper.

### Changed

- Update the Minecraft 26.2 toolchain.
- Cache map status, merge boundaries, and reduce routine logging.

### Fixed

- Preserve container contents, names, deferred overlays, and correct
  double-chest ordering.
- Use vanilla chunk serialization for lighting, blending, retrogen, and other
  available saved fields.
- Fix keybinding and Minecraft 26.2 client API compatibility.

---

## [0.2.1] — 2026-03-29

### Added

- Add the built-in map with tooltips, conflict actions, configurable rendering,
  and the **M** shortcut.
- Persist manual conflicts across restarts and support individual/bulk actions.
- Add named nearby-area exports with a 1–50 chunk radius and current-position spawn.

### Changed

- Improve distant map rendering and shared region-file write coordination.
- Keep export workers alive during shutdown to reduce interrupted writes.

### Fixed

- Queue exports behind active work and retain chunks after failed writes.
- Fix legacy conflict-folder resolution and CI dependency downloads.

---

## [0.2.0] — 2026-03-25

### Added

- Introduce SQLite chunk tracking, source priorities, legacy timestamp migration,
  and third-party database documentation.
- Add automated GitHub and Modrinth release workflows.

### Fixed

- Fix container contents, block states, biomes, and lighting in exports.
- Reduce capture stalls, cache growth, and mapping races.

---

## [0.1.0] — Initial release

### Added

- Introduce client-side world capture and singleplayer export for Minecraft
  1.21.11, including dimensions, entities, containers, and conflict handling.
