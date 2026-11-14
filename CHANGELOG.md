# Changelog

All notable changes to World Mirror are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Mod and format-library releases share one reverse-chronological timeline. Mod releases use `## [version]` and format-library releases use `## world-mirror-format [version]`. Changes shared by every Minecraft target of the mod have no prefix. Changes limited to one target use `[fabric-<minecraft-version>]` at the start of the entry. The format library also ships its own changelog in its release ZIP. Keep its entries synchronized with the root format sections. Unreleased changes precede dated releases.

Use Added, Changed, and Fixed categories where applicable. Combine related changes without dropping compatibility requirements or migration guidance.

---

## [0.6.0-alpha.2] — Unreleased

### Fixed

- Clear verified void placeholders with mixed vanilla biomes or old vanilla blending data when assigning a seed, so the affected chunks can generate terrain.
- Recalculate lighting along newly generated terrain borders, including when experimental terrain blending is off.
- Repair surviving void placeholders when opening a previously converted mirror with its conversion backup. Trace fully vanilla biome placeholders along verified old boundaries and recognize incoming fluids and boundary cobblestone deposits.
- Respect the experimental blending switch during native generation. Regenerate distorted terrain only when its saved provenance confirms it is unmodified.
- Preserve custom files and unrelated data pack settings when changing a mirror's seed.
- Show repair progress during world opening and preserve untouched chunk payloads and timestamps in edited region files.

---

## world-mirror-format [0.1.4] — 2026-11-14

### Added

- Register `seed_generated` at priority 100 with timestamp zero. Expose seed-only index mutations, map-visible queries, and a shared terrain digest.
- Share typed world lineage traversal and the native graph lease with offline importers so they can verify upstream links and coordinate with the mod.

### Changed

- Move the unversioned chunk timestamp importer into the legacy package and preserve the published JVM entry point.
- Persist missing source registrations during writable initialization. Priority queries read registered rules from SQLite.

---

## [0.6.0-alpha.1] — 2026-11-14

This Fabric prerelease supports Minecraft 1.21.11, 26.1.2, 26.2, and 26.3.

### Added

- Track upstream worlds and multiple mirrors in a graph built from `saves` and `downloaded_worlds`. Choose a fixed default mirror or automatic selection for each source.
- Give extra copies new world IDs while retaining their upstream edges. Preserve IDs when moving saves and retain edges to unavailable worlds.
- Open the world relationship graph from the title screen or in-game settings. Edit upstream links, default mirrors, endpoint aliases, and Xaero stable ID bindings.
- Choose mirrors, upstream worlds, and remote sources in searchable lists with native icons and save details. Move mirrors with the World Location dropdown, detach their upstream, or delete a save after confirmation.
- Set, change, or remove a mirror's seed offline. Preview terrain differences, void cleanup, and optional removal of unchanged generated chunks with a progress bar and cancellation.
- Enable experimental Overworld blending and regenerate eligible generated chunks along captured terrain boundaries.
- Import old local routes from the Worlds toolbar by confirming the source save, including renamed saves. Keep each verified destination's SQLite namespace and retain creation preferences when the old destination is missing.

### Changed

- Migrate v0.5.4 mirror metadata and routing to schema 2. Back up the original metadata, preserve SQLite namespaces and existing chunk history, and archive the old routing file after the graph is saved. Preserve verified default targets and keep upgraded server routes on address-based identity unless their policy is changed.
- Scope downloads, retries, conflict handling, maps, and notifications to the destination mirror. Record lineage for nearby exports when requested.
- Resolve logical worlds using the selected endpoint or stable ID policy. Wait for an explicit source choice when the available evidence is ambiguous. Use World Mirror Toolkit 0.1.2 or later for Replay imports into upgraded mirrors.
- Assign a stable ID to a local world on its first linked use or when saving its route preferences. Browsing and standalone nearby exports leave ordinary saves untracked.
- Prepare downloads in the background before showing Active. Review a required mirror upgrade from the status screen.
- Keep unchanged seed terrain off the chunk map and overlay. Captures and Replay imports can replace it after validating its provenance.

### Fixed

- Clear fixed defaults when switching back to Automatic. If the last automatic target is unavailable, choose another verified direct mirror.
- Offer a source choice after stable ID discovery expires when the policy permits it. Pause uncertain remote transitions until fresh evidence arrives, resume a verified same-source session, and prevent a changed source from capturing stale client chunks.
- Recover interrupted mirror moves and migration journals before using the affected save. Keep writes away from ambiguous graph branches and reject symbolic links and Windows directory junctions in migration and recovery paths.
- Retry retirement of completed transactions when Windows briefly holds the journal open, preventing a false recovery warning during the next save inspection.
- Preserve edited seed terrain and auxiliary entity and POI data when regenerating or removing seed chunks.
- Keep queued exports and confirmations bound to the original world. Old export completions leave a newer capture session's state alone.
- Continue from Minecraft's existing loading step after inspecting a current mirror. Preserve Minecraft's data pack safe mode.
- Repair an empty mirror reservation's upstream without materializing a save, and refuse to write into a playable world that only has a copied reservation.
- Avoid vanilla key conflicts with new defaults U for download, V for export, and J for clearing the cache. Existing saved bindings are retained.

---

## [0.5.4] — 2026-11-13

### Changed

- Share source-priority decisions and MCA read-back verification between live sync and toolkit imports. Live sync honors the replay source's configured priority and only indexes terrain that passes verification.
- Register `replay_import` at priority 10 when no rule exists for it. Existing source rules and save formats carry forward.
- Use the same zoom-bucket selection and drawing path in the built-in map and Xaero overlay. Keep bucket sizes stable while panning and bound work at every zoom level.
- Validate NBT string lengths without encoding strings twice. Buffer export reads and writes while retaining exact content checks and native region formats.
- Reduce client-thread capture work by queueing each section update packet once, checking the remaining tick budget before starting more captures, and avoiding redundant terrain and lighting copies.
- Add optional performance diagnostics for map rendering and the entity, terrain, and bundle stages of preparing an export.
- Reuse map geometry when the visible data and colors stay the same.

### Fixed

- Treat legacy zero-byte MCA placeholders as empty during terrain, entity, and conflict writes.
- Refresh the matching saved chunk map and count after a durable export or index repair, including when a previous refresh is still in flight.
- [fabric-1.21.11] Fix a crash when the status screen requests background blur twice in one frame.
- Keep the status screen open during manual export. Check the target in the background and let singleplayer continue processing the export while the screen is open.

---

## world-mirror-format [0.1.3] — 2026-11-13

### Added

- Share scoped source-priority lookup, overwrite decisions, and region read-back sequencing between live sync and offline imports.
- Expose region-bounded index reads and a batch priority snapshot for offline import planning.

### Changed

- Seed the `replay_import` source at priority 10 and preserve existing source rules. Record verified replacements of missing or physically empty chunks with their actual capture times.

---

## [0.5.3] — 2026-11-11

### Changed

- Coordinate periodic, adaptive, resource-pressure, and retry exports through one schedule. Finish accepted writes and drain retained snapshots on exit without waiting for another client tick or interrupting atomic file writes.
- Share one source tree across Minecraft targets with compile-time version branches and separate common, client, and Fabric entrypoint layers. Generate projects, artifact collection, and release matrices from one version definition; isolate development run directories by Minecraft target.
- Verify distributable metadata, bytecode, entrypoints, Mixins, and bundled dependencies before collection. Resolve source branches before Loom remapping so the published 1.21.11 sources remain fully remapped and usable.

### Fixed

- Bind generated IDEA run configurations to the imported Gradle module names so all supported targets keep a valid launch module after project reload.
- Replace resource-pressure capture stops with reduced work and recoverable pauses. Preserve the download toggle, pending tasks, and capture before chunk unload; automatically resume and recheck loaded chunks after stable recovery. Distinguish resource reduction, pause, and recovery from file failures in status and notices.
- Account for shared and in-flight retained data without double-counting revisions. Prioritize export draining under pressure while deferring optional capture work.
- Limit light-update waiting to the affected chunk so other terrain can still save.
- Restore left-button dragging in the built-in map on Minecraft 26.3 and keep map drags tied to the primary button on all targets.

---

## world-mirror-format [0.1.2] — 2026-10-23

### Fixed

- Roll back checked and unchecked transaction failures without accidentally committing after a failed rollback; preserve connection-restoration errors.
- Ignore null keys in legacy timestamp input.

---

## [0.5.2] — 2026-10-23

### Added

- Add built-in chunk-map warnings for failed and partially saved exports, with purple conflict hints and simplified markers in small cells. Show all export states, terrain/entity results, and metadata status in hover details; keep Xaero and in-world overlays free of corner glyphs.
- Surface automatic/manual export and file-operation failures through merged, rate-limited chat and toast notices, with current health on the status screen.

### Changed

- Reduce entity snapshot copying and index reconciliation by chunk, keeping small observations fast even after a long capture session.
- Share spatial queries and bucket summaries across chunk maps and world overlays; publish changed export-state regions and bound coarse-view refresh work with large capture histories.
- Replace the vendored NBT stack with bounded native codecs and read-only MCA access; stream entity rewrites and apply conflicts in bounded batches.
- Keep routing previews off disk-write locks and skip unchanged folder claims.

### Fixed

- Detect obsolete mirror generators despite current metadata and offer backed-up repair; preserve valid customizations and refuse unknown generator edits.
- Preserve captured terrain, lighting, entities, and inventories across source changes and concurrent exports. Resume interrupted capture scans and retain confirmed block removals under queue pressure.
- Retry only unfinished mirror data, including after disconnecting. Preserve each source's terrain/entity order while letting newer terrain proceed past entity-only failures.
- Protect open saves with Minecraft's session lock across Windows and Linux. Preserve damaged region headers and healthy neighboring durability claims. Recover interrupted file sets without overwriting unknown edits or undoing verified commits after cleanup failures; retain legacy timestamps when migration or metadata persistence fails.
- Preserve damaged configuration and optional saved data, defer overwrites when priority rules are unreadable, and keep independent terrain saves running.
- Validate mirror identities and relocation plans before moving files; recover routing consistently and reject stale mapping updates.
- Resolve conflicts without lock-order deadlocks, support external chunk files, and retain failed or newer incoming conflicts for review.
- Correct entity moves, detached passengers, and observations that revert during an export. Preserve legacy UUID trees missing type IDs while rejecting malformed new entity observations and oversized NBT strings.
- Preserve container names and inventories without applying stale data across worlds or block-entity types, or restoring block entities into confirmed air.
- Refresh map aggregation after panning and bound work for extreme viewports.
- Handle unsafe folder names and custom dimension paths; preserve nearby-export entities, Unicode names, spawn dimensions, and independent lineage.
- Finish accepted exports and one final queued pass on exit without interrupting atomic writes or keeping an idle export worker alive.

### Known Issues

- The in-world overlay does not work when shaders are enabled.

---

## [0.5.1] — 2026-10-18

### Added

- Add Session and Map overlay color modes. Session uses capture-session colors; Map shares the built-in and Xaero maps' age, source, and queue colors.

### Changed

- Default new overlay configurations to Y=96 while preserving existing heights.

### Fixed

- Keep replay imports historical in Session mode, regardless of timestamp, and retain historical cached coverage between sessions.

---

## [0.5.0] — 2026-10-18

### Added

- Add the in-world coverage overlay, with separate source/mirror switches and a **K** toggle. Thanks to [@cev-api](https://github.com/cev-api) for [PR #14](https://github.com/billstark001/world-mirror/pull/14).

### Changed

- Add adjustable height, distance, same-status grid, opacity, and F1 behavior. Default to a 128-chunk radius and 60% opacity; aggregate distant tiles.
- Keep enum settings selection-only and use direct input for the overlay's 8–16384 chunk radius.
- Keep Xaero optional in development clients; opt into its matching bridge runtime with `-PxaeroIntegration=true`.

### Fixed

- Correct box geometry, grid visibility, and distant outlines.
- Fix transparency ordering and doubled fill opacity on the classic renderer; retain native improved-transparency rendering on Minecraft 26.3.

---

## [0.4.6] — 2026-10-05

### Added

- Show live cached chunks and capture/write queues on the built-in and Xaero maps, distinguishing whether a saved record already exists.
- Show separate saved, cached, and pending-write counts in the status screen.

### Changed

- Use the capture-age gradient for replay imports while retaining their source in tooltips.
- Use capture timestamps for new last-sync values; leave existing metadata timestamps unchanged.

### Fixed

- Fix concurrent map refreshes and sparse-view performance.
- Guard Xaero rendering against extreme zoom and invalid projections.

---

## world-mirror-format [0.1.1] — 2026-09-27

### Changed

- Publish binary and source JARs as direct GitHub Release assets for public Gradle Ivy consumption.

---

## [0.4.5] — 2026-09-27

### Changed

- Share the SQLite contract through the standalone format module.
- Require matching Xaero bridge 0.1.2 artifacts for every target during builds and IDEA synchronization, and use a localized mirror badge.
- Unify worldgen asset revision 3 and retain the backed-up upgrade path.

### Fixed

- [fabric-26.3] Select the matching bridge instead of the incompatible 26.2 file.
- [fabric-26.3] Fix noise-generator settings that prevented mirrors from loading; refresh affected worldgen through the normal upgrade flow.
- Refresh the status screen's last-sync time after completed exports.

---

## world-mirror-format [0.1.0] — 2026-09-27

### Added

- Introduce the standalone Java 21+ metadata and chunk-index contract, available through composite builds and a binary/source release ZIP.

---

## [0.4.4] — 2026-09-19

### Added

- Add the Minecraft 26.3 target and biome data-pack generation, including dynamically registered biomes such as the Dappled Forest.

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

- Replace editable/cycling enum controls with translated selection-only lists across global, nested, and per-world settings.
- Close lists consistently on selection, focus loss, outside click, and Escape. Use compact labels, expansion arrows, and ellipsis without a hidden text caret.

---

## [0.4.1] — 2026-08-23

### Changed

- Use vanilla entity serialization with complete passenger trees.
- Track versioned partial/complete observations independently of terrain retention, with atomic entity-region merging.

### Fixed

- Reconcile entity moves and safe despawns while preserving unobserved data. Verify region writes before acknowledgement and retain failed revisions.
- Handle empty entity-region placeholders without aborting other writes.
- Preserve stop-time terrain, entity, and container snapshots deferred behind an active export.
- Show an open mirror's original source and last successful sync.
- Copy source time, weather, and difficulty by default, with configurable fallbacks and separate manual sync actions.
- Repair the exact malformed world-clock wrapper written by 0.4.0 on Minecraft 26.1.2 and 26.2, leaving unrelated clock payloads unchanged.

---

## [0.4.0] — 2026-08-23

### Added

- Add the opt-in adaptive pipeline with backlog and maximum-latency triggers; retain stable periodic export as the default.
- Capture chunk, block, biome, lighting, container, and unload changes through a bounded queue with configurable main-thread budgets and export cooldowns.
- Add optional performance diagnostics for capture latency, export stages, queue pressure, and JVM memory/GC use.

### Changed

- Stream terrain and entity regions with lower memory use. Reserve nearby rescanning for explicit exports.
- Separate capture, export, mapping, and lifecycle ownership with thin Minecraft-version adapters. Apply pipeline changes on the next activation.

### Fixed

- Retain unsaved changes until region verification and SQLite commit succeed; prevent older exports from acknowledging newer revisions.
- Preserve failed manual conflicts and isolate caches across source changes.
- Reconcile visible entity removals without treating unloaded chunks as empty.

---

## [0.3.0] — 2026-07-29

### Added

- Build Minecraft 1.21.11, 26.1.2, and 26.2 from shared sources and collect their distributable JARs with the Modrinth helper.
- Introduce optional Xaero World Map Bridge integration without bundling it.
- Add confirmed per-world mirror relocation and nearby-export lineage choices.
- Detect open mirrors and offer explicit, backed-up upgrades before loading outdated saves.

### Changed

- Move the Java package to `io.github.billstark001.worldmirror` and replace LibGui with native screens.
- Improve map aggregation, status layout, and translations in all four locales.

### Fixed

- Correct status-screen colors, opening flashes, map boundaries, and the Xaero current-mirror indicator.
- Replace legacy flat-void generation with per-dimension environment assets, preserving captured terrain and cleaning up only blank legacy void chunks.
- Separate worldgen schema and asset revisions, reject newer formats, and refresh generated data packs through backed-up upgrades.
- Defer World Mirror upgrades until Minecraft's own save-version upgrade finishes and restore the world list correctly on cancellation.

---

## [0.2.3] — 2026-06-22

### Changed

- Improve development helpers for Xaero version selection, disabled JAR reuse, disassembly, and explicit build cleanup.

### Fixed

- Fix Xaero 1.41.1 integration on Minecraft 26.2.
- Add configurable exact/fallback injection and capability reporting so unsupported Xaero builds do not crash startup or draw duplicate overlays.

---

## [0.2.2] — 2026-06-21

### Added

- Add Xaero status rendering, its settings, and a development download helper.

### Changed

- Update the Minecraft 26.2 toolchain.
- Cache map status, merge boundaries, and reduce routine logging.

### Fixed

- Preserve container contents, names, deferred overlays, and correct double-chest ordering.
- Use vanilla chunk serialization for lighting, blending, retrogen, and other available saved fields.
- Fix keybinding and Minecraft 26.2 client API compatibility.

---

## [0.2.1] — 2026-03-29

### Added

- Add the built-in map with tooltips, conflict actions, configurable rendering, and the **M** shortcut.
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

- Introduce SQLite chunk tracking, source priorities, legacy timestamp migration, and third-party database documentation.
- Add automated GitHub and Modrinth release workflows.

### Fixed

- Fix container contents, block states, biomes, and lighting in exports.
- Reduce capture stalls, cache growth, and mapping races.

---

## [0.1.0] — Initial release

### Added

- Introduce client-side world capture and singleplayer export for Minecraft 1.21.11, including dimensions, entities, containers, and conflict handling.
