# Contributing to World Mirror

## Source layout and boundaries

Root `src/main/java` is the home of version-neutral behavior. A class must not be
copied wholesale into every `versions/shared-mc-<version>` tree merely because a
Minecraft method name or rendering type changed. Keep the business class in root
source and add a small package-local API adapter per target, following
`WorldStructureCreator` + `WorldStructureApi`, `StatusScreen` + `StatusScreenApi`,
and `WMPlayerMessages`.

Version adapters translate Minecraft types, method names, data encodings, or save
layouts. They must not acquire lifecycle policy, persistence policy, or duplicated
UI behavior. Do not introduce a cross-version `shared-mc-26` layer until the Gradle
source sets explicitly consume it; identical thin 26.1.2/26.2/26.3 adapters are acceptable
while their upstream APIs remain separate compatibility targets.

The download package follows the same ownership rule:

- `DownloadManager` is the public lifecycle and command facade.
- `DownloadPipeline` implementations decide when an export is due. New strategies
  implement that contract alongside the existing stable and adaptive strategies.
- `DownloadCaptureQueue` owns main-thread serialization, hint coalescing, queue limits,
  and capture diagnostics.
- `ExportWork.Intent` describes a live request; `ExportWork.Frozen` carries immutable
  terrain, entity, container, settings, and source data. Frozen requests never sample
  a later client world or acknowledge its live entity signals.
- `DownloadExportCoordinator` owns queue ordering, snapshot preparation, durability
  commits, and the export worker. Normal and shutdown passes execute the same
  `ExportJob`; its `ExportOutcome` separates verified receipts from remaining work.
- `MirrorMapping` owns output-path selection, claiming, and per-world path settings.
- `EntityTracker` owns game-thread vanilla serialization and observation boundaries;
  `EntitySnapshotStore` owns versioned partial/complete reconciliation; and
  `EntityRegionWriter` owns UUID-aware recoverable entity-region merging.

Avoid adding queue locks, region transactions, strategy-specific state, or path-claiming
logic back to the manager. Prefer extending an existing owner over adding a one-method
utility file, but do not merge unrelated state merely to reduce the file count.

Captured world state is scoped to one source world. Join, server-world, and dimension
transitions must not allow cached chunks, entities, containers, or lighting overlays to
leak into another source. Background work receives immutable snapshots that remain valid
after live caches are cleared. A region write is durable only after file validation and
the persistence step appropriate to that data: terrain also requires its SQLite index
commit, while entity output requires post-write region and dimension-wide UUID
verification. Failed conflict application must retain its conflict file.

Capture-cache mutations, including durable acknowledgements, belong to the game
thread. Background exporters consume frozen snapshots and dispatch their exact
revision receipts back to that thread; they must not remove live terrain, lighting,
entity, or container state while capture callbacks are running. Container cleanup
must also preserve newer overlays and overlays awaiting a pending chunk capture.

Entity dirtiness is independent from terrain-cache retention. Never prune entity updates
because a terrain entry was evicted. A successfully reopened and UUID-verified
entity-region replacement acknowledges only the exact captured revisions it represents.
Stop-time exports deferred behind an active worker must carry immutable terrain, entity,
and container snapshots so disconnect or a new source cannot change their meaning.
Terrain and entity retries preserve source order independently; an entity-only failure
must not hold up newer terrain or cause successful terrain to be rewritten. A final
metadata failure retains the verified capture-time floor. Shutdown never interrupts
an accepted region transaction or waits for a game-thread acknowledgement; its frozen
queue gets a finite final pass, with failed revisions still blocking dependent work.

Version-specific saved-data repairs belong in `WorldStructureApi`. Match only a known
World Mirror-owned malformed shape, preserve unrelated or extended payloads, replace the
file atomically, and exercise the target Minecraft codec in a round-trip test. A target
that does not use that saved-data file should provide only the no-op adapter required by
the shared orchestrator.

## Map data and rendering ownership

- `ChunkBounds` and `ChunkBucketLayout` own coordinate bounds and bucket sizing.
  Widen boundary arithmetic before adding one and keep clipped edge buckets exact.
- `ChunkRegionIndex` supports mutable game-thread metadata and immutable indices
  published after construction. `ChunkStatusSnapshot` and `LiveChunkStatusSnapshot`
  expose data; `ChunkBucketIndex` owns bounded, color-independent bucket summaries.
  `Publication` stages writer-owned changes and publishes immutable region pages;
  readers never wait for a bulk export or traverse a mutable page. Do not copy the
  full history when one region changes.
  Packed coordinate keys have collision-heavy boxed hashes; use Minecraft's
  existing fastutil maps and `copyCoordinates`, preserving defensive copies and
  read-only entries instead of freezing coordinate maps with `Map.copyOf`.
- A summary retains independent newest-record, session-coverage, conflict, export-state counts, and live
  priority facts. MAP's newest-record rule and SESSION's coverage priority must not
  discard each other's inputs. Equal timestamps use deterministic coordinate ties.
- `ChunkMapStyle` projects facts into display colors; `ChunkTileGeometry` builds
  shared runs, rectangles, and edges. World overlays add height and facing without
  expanding a finished 2-D map back into cells.
- `ExportStatusStore` observes exact terrain/entity receipts without granting any
  acknowledgement. Retain failures across retry and live-cache eviction, and never
  infer entity completion from SQLite terrain rows. `ExportStatusMarkers` supplies
  the same corner glyphs to all maps and both overlay modes without changing base colors.
  Coarse world tiles use compact markers to bound vertex work. Update source counts
  incrementally and allocate published health only for changed sources.
- `ChunkViewportCache` owns per-view live capture and context invalidation;
  `ChunkMapProjection` owns per-view derived map geometry. Complete-bucket caches
  are shared through source indices. Source changes and dimension resets invalidate
  relevant caches; color ageing must continue without rebuilding source snapshots.
  Live refresh intervals grow with viewport extent (0.5/1/5 seconds); region progress
  must not bypass this cadence for wide overlays.

Unit tests check display semantics, clipped and sparse views, cache bounds, and
candidate work counts. These tests do not establish in-game GPU frame rates; use
`docs/world-overlay-manual-test.md` for interactive rendering checks.

## Mirror worldgen revision policy

`worldgenAssetRevision` is one global, monotonically increasing integer for every
supported Minecraft target. All artifacts in one mod release write the same value
to both `worldmirror_meta.json` and the embedded data-pack manifest. Never select
this revision from the Minecraft or data-pack version. Increment it across all
targets when a release changes the embedded worldgen assets or the serialized
worldgen settings for any target. A target whose bytes did not change still
adopts the new revision; this keeps migration ordering consistent.

`worldgenAssetDataVersion` separately records the Minecraft data version used
to write those assets. The revision does not imply identical encodings across
Minecraft targets. `worldgenSchema` tracks semantic changes to the mirror's
dimension layout and is bumped only when that schema changes. Do not reuse any
of these numbers for a different purpose or decrease them in a later release.

An older revision or data version requires an approved upgrade: back up the
affected files, regenerate the data pack and worldgen settings with the target
Minecraft codec, then advance both metadata markers after the write succeeds.
A newer revision or data version is treated as future data and must not be
overwritten. Test the revision transition and generated settings on every
supported target; test the exact new codec on the target whose encoding changed.

## UI control policy

User-selectable enums are selection-only listboxes. Do not expose editable enum text or
one-click value cycling. The closed control and every open-list row must use the same
field-specific translation function; raw serialized enum names are permitted only in the
JSON file, never as the normal UI label. Register new `ModConfig` enums through the shared
enum GUI provider rather than adding field-by-field screen code.

An open list is a temporary overlay above surrounding controls. It must retain a visible
current-value marker and close after any selection, on outside click, on Escape, and when
focus leaves the control. Opening and closing without choosing must not change the pending
value. Native-screen lists should be no wider or taller than their content requires and
must not imitate a second full-width form row when a compact value-aligned popup suffices.
Selection-only controls must not retain a hidden `EditBox` or render a text caret. Closed
labels reserve room for their dropdown indicator and use font-width ellipsis truncation
when needed; do not add a pre-render pass or cache per-character screen coordinates.
The indicator direction derives from the popup's actual expanded state, never the row's
selection or keyboard-focus flag. Keep value state, focus handling, layout, truncation,
and expansion semantics in root source; target adapters should submit only API-specific
draw calls when that is the sole upstream difference.

Changes to selection controls require updating and running
`docs/enum-dropdown-manual-test.md` on every supported Minecraft target. Test translated
closed/open labels, repeated open/close, selection of the already-current value, mouse and
keyboard focus loss, outside click, Escape, scrolling where applicable, nested-section
overlay order, save/cancel/default behavior, and serialized-name compatibility.

## Documentation and release policy

### Changelog order and prefixes

Keep mod and format-library release sections in one reverse-chronological
timeline by release date. For releases on the same date, put the later release
first. Mod sections use `## [<mod-version>]`; independent format-library
sections use `## world-mirror-format [<format-version>]`. Put format-only
changes under the format section, never as prefixed bullets under a mod
section. Within a mod section, use an unprefixed entry when a change applies
to every supported Minecraft build and `[fabric-<minecraft-version>]` when it
affects only one target. Add changes to the current section in the order they
happened within each category. Retain the applicable Added, Changed, and Fixed
headings, including in historical releases. Keep entries concise: combine related fixes,
describe observable behavior, and leave class names, test procedures, and
implementation details in contributor documentation or commits. Retain important
compatibility and migration requirements when condensing historical releases.
Record unreleased format-library changes under `## world-mirror-format [Unreleased]`
at the top of the timeline without changing its independent version. Keep the copy in
`world-mirror-format/CHANGELOG.md` consistent with the root format section.

The format module is a standalone Java 21+ Gradle build inside this repository.
The mod and toolkit consume its source through `includeBuild`, so development
needs no package repository. `gradle -p world-mirror-format releaseBundle` produces
the versioned binary/source bundle for a GitHub Release; publish the ZIP,
its SHA-256 checksum, and direct binary/source JAR assets together. Keep its
version independent of `mod_version`. Public Gradle consumers use the
GitHub Release Ivy pattern documented in `world-mirror-format/README.md`.
Tag format releases `world-mirror-format-v<version>` to run the independent
format release workflow; tag mod releases `v<version>` to run the mod workflow.

Documentation changes are part of a feature or compatibility change, not a later release
chore. Before merging, compare all user-visible behavior against `README.md`, the
self-contained player description in `README_MODRINTH.md`, the current release section in
`CHANGELOG.md`, and `DATABASE.md` when persistence semantics changed.

`README_MODRINTH.md` is written for players who may not know World Mirror's internals.
Lead with the required action and observable result. In particular, always keep these
points explicit:

- pressing **P** starts/stops capture, while **O** exports already captured data;
- the default `downloaded_worlds` directory is not shown in Minecraft's world list;
- containers must be opened and client-invisible server data cannot be recovered;
- every download must exactly match its Minecraft target and required Java version.

Avoid implementation terms such as revision acknowledgment, queue hysteresis, immutable
NBT materialization, or durability indexes in the first-use instructions. Put technical
detail in the repository README and link-oriented contributor docs instead.

The supported-target metadata in root `build.gradle` is the build source of truth. Adding,
removing, or releasing a target also requires checking `settings.gradle`, its
`versions/fabric-<minecraft>` and `versions/shared-mc-<minecraft>` directories, CI/release
workflows, both READMEs, and the current changelog. CI and release jobs must call
`buildAll` and collect artifacts from `versions/fabric-*/build/libs`; the old root
`build/libs` path is not a multi-target distribution directory.
Every target must have a matching `libs/xaero-world-map-bridge-<minecraft>.jar`.
The `verifyBridgeMatrix` task reads each JAR's Fabric metadata and rejects a
different Minecraft target, a version other than `xaero_bridge_version`, or a
Xaero dependency with an upper bound; `buildAll`, IDEA sync, and the release
workflow run it. Update all four JARs and `xaero_bridge_version` together.
Do not point a new target at an older bridge artifact merely to satisfy compilation.

Before a release, run all target tests, `buildAll`, and one client startup smoke test per
target. The shared `run/mods` directory may contain only one enabled Minecraft-version
build of Xaero's World Map at a time. Use `-PxaeroIntegration=true` for integration
testing; the default runtime excludes our compile-only bridge. To test genuine absence of Xaero, use
`-PworldMirrorRunDir=build/smoke/<target>` with an empty `mods` directory and leave
`xaeroIntegration` unset. Run the matching published map and bridge separately as an
integration test. For IDEA, the integration property must also be present when
reloading the Gradle model, not only when regenerating run configuration XML.

### Release automation

Mod releases use `.github/workflows/release.yml`. Update `mod_version` in
`gradle.properties` and make the first mod release section in `CHANGELOG.md` use that
exact version, then push the corresponding `v<version>` tag. The workflow validates
those values, extracts only that mod section, builds all targets once, checks the
exact four distributable JAR names, and creates one GitHub Release. Its release
channel and pre-release flag are derived from the version:
plain semantic versions are stable, `-alpha...` versions are alpha, and other suffixes are
beta.

Format releases use `.github/workflows/release-format.yml`. Update the module
version in `world-mirror-format/build.gradle`, add a separate
`## world-mirror-format [<version>]` section to the root changelog, then push
`world-mirror-format-v<version>`. This workflow extracts only the format section,
builds the ZIP and SHA-256 checksum with Java 21, and publishes them to a
separate GitHub Release. Publish the format tag before a mod tag that consumes it.

Modrinth publishing is optional. To enable it, configure an Actions repository variable
named `MODRINTH_PROJECT_ID` with the Modrinth project slug or ID, and an Actions repository
secret named `MODRINTH_TOKEN`. Use a Modrinth personal access token belonging to a project
team member with the minimum `VERSION_CREATE` scope. When either setting is absent, tag
releases still publish to GitHub and report that Modrinth was skipped. Each enabled
Modrinth release is a four-entry matrix, with one primary JAR and one exact Minecraft
version per entry. Never hard-code deployment project IDs or tokens in the workflow.

`workflow_dispatch` is the recovery path for an existing tag. It uses the same validation,
build, and publishing jobs; Modrinth is off by default to make retrying only the GitHub
Release safe. Enable it explicitly only when none of the corresponding Modrinth versions
already exists.

## Logging policy

World Mirror has one operational logging API: the shared `WMLogger`. Minecraft's
version-specific player-message methods live separately in `WMPlayerMessages`.
Do not add another logger,
`System.out`, `System.err`, or `printStackTrace` in World Mirror code.

Choose the lowest level that still makes the event actionable:

| Level | Use it for | Do not use it for |
| --- | --- | --- |
| `debug` | Normal state transitions, cache maintenance, coalescing, and detailed counts useful during development | Per-tick success messages or expected failures |
| `info` | A session or export milestone that explains what World Mirror did | Packet-, chunk-, entity-, or container-level activity |
| `warn` | A recoverable failure, lost operation, disabled integration, or durability risk requiring investigation | Expected lifecycle states such as a world being absent during disconnect |
| `warnRateLimited` | A warning that can recur for packets, chunks, regions, codecs, or database lookups | Unique failures that should always be visible |
| `errorRateLimited` | A blocked export or failed index/metadata commit, including automatic exports | Successful or merely queued work |

Use a stable, low-cardinality rate-limit key and normally a 30-second interval.
The next emitted warning includes `suppressed=<count>`. Pass the `Throwable` to
the overload whenever an exception exists; appending only `getMessage()` loses
the stack and frequently loses the cause. Lower layers should either throw a
contextual exception or log it, not both.

Messages start with a short event description and then add searchable
`key=value` context, for example:

```java
WMLogger.warnRateLimited("chunk-process-" + dimension.identifier(), 30_000L,
        "Chunk processing failed dimension=" + dimension.identifier()
                + " chunk=" + chunkPos + "; retained for retry", error);
```

Do not log complete NBT, packet or inventory payloads, authentication/session
data, player chat, or user-provided container names. Paths, dimensions, chunk
positions, counts, durations, pipeline modes, and queue sizes are appropriate
when they help identify the failed operation.

### Player messages

Logs and player messages are separate interfaces. `ExportNotifications` turns typed
export health into translated chat and toast notices, including automatic failures.
Coalesce per source, retain undelivered failures while no player exists, limit
reminders to 30 seconds, allow escalation/recovery, and space global deliveries by
two seconds. Only report recovery once all related work has settled. Other player
messages use `WMPlayerMessages` at explicit command or lifecycle boundaries.
Detailed exception data belongs in `latest.log`; worker completion never waits for UI delivery.
The notification tick uses event/deadline wakeups and forgets recovered sources;
healthy play must not repeatedly scan source history.

### Performance diagnostics

Normal logs retain low-frequency session/export milestones and warnings.
Expensive or periodic telemetry is gated by **Performance Diagnostic Logging**
and uses the `[perf]` marker with stable `key=value` fields. New performance
fields should describe a queue, stage duration, volume, memory/GC delta, or
failure count and should preserve existing field names. Individual slow-stage
records must also honor the configured threshold.

The periodic snapshot is session-scoped. Capture latency fields report bounded
samples with average, p95, p99 and maximum values; capture-hint counters are
grouped by reason; unload capture is measured separately; and GC deltas are
reported per collector so concurrent and stop-the-world activity are not merged.
`captureReasonLatency` entries use `reason:count/avg/p95/p99/max` in
microseconds. Diagnostic slow-capture records are rate-limited by origin and
bounded reason; do not emit a message whenever a short capture queue drains.
Export timing includes post-flush region verification. Keep all of these fields
bounded in memory and reset them when a new download session starts.
Gameplay frame spacing and World Mirror's own tick-handler time are measured
separately: capture timing alone cannot distinguish serialization cost from a GC
pause that happened during the same call. Each diagnostic export reports its
trigger and stage timings; periodic snapshots report worker duty time, capture
budget overruns, suppressed automatic requests, and deferred explicit-request
coalescing.

Region files are not durable merely because `flush()` returned. Terrain writes must
validate the Anvil location table, reopen and decode each staged entry, and only then
advance the SQLite durability index. Entity writes must reopen affected chunks and
verify affected UUIDs across the dimension before acknowledging revisions. Failed
validation remains dirty for retry; stale World Mirror terrain durability rows for unreadable entries
must be removed.

Before merging a logging change, search the complete source tree for direct
console/logging calls, check that recurring failures are limited, verify that
exceptions retain their cause, and build every supported target. Do not launch
Minecraft as part of this check unless interactive validation is specifically
required.

## File failures and recovery

Use `JsonSupport.readDocument` to distinguish missing, invalid and inaccessible JSON.
Unavailable priority rules are not permission to overwrite: propagate the failed
decision and retain the pending revision. Removing an unreadable World Mirror
durability claim must preserve rows owned by player/other update sources.

`NbtReadSupport` bounds native NBT allocation and compressed input. `AnvilReader`
reads containers without opening a repairing writer, including native compression
IDs and external `.mcc` payloads. Entity merging and legacy cleanup use native NBT;
there is no second NBT model or conversion/dependency compatibility layer.
Reuse its validated location table and channel throughout a region scan. Streaming
scans have per-chunk allocation limits; cumulative allocation is not retained heap.
Policy-limit failures are not malformed-file cache entries. Cache failures against
the version observed before reading, and only while that version remains unchanged.
Native selective NBT visitors are not a substitute for bounded validation: skipped
arrays do not charge the allocation quota, and field collectors can stop early.
Use strict `NbtIo.writeUnnamedTag` for staging. In-place MCA output streams commit
on close even after serialization fails, so they still need string preflight.
An allocated entry that vanilla cannot decode is an error, never an absent chunk.
Reject damaged location tables before opening a writable region, retaining valid
older durability claims for healthy neighbors. Bound retained conflict snapshots
separately from streaming-read quotas; reuse one reader per batch and remove only
unchanged, verified incoming entries after its target batch succeeds.

Keep failures at their real dependency boundary. Directory setup failure affects
its dimension; optional saved-data maintenance cannot erase terrain receipts.
Entity UUID uniqueness can legitimately depend on every entity region in a
dimension, so an unreadable region must not be silently skipped.

Whole-file replacements use `FileTransaction`: stage and validate semantics once,
verify backups while copying, publish a recovery journal, replace, verify exact
file bytes, then mark committed. Reuse these content proofs instead of decoding
live MCA/NBT or JSON again. Decision records are bound to their manifest; recovery
checks the whole restoration set before changing a file and refuses unknown edits.
MCA and external payloads belong to one recovery set. Migration works in a staged
world and commits its files together. This provides rollback/restart recovery under
the native world lease, not simultaneous multi-file visibility or a hardware
power-loss guarantee. `WorldRecovery` uses a marker to avoid scanning healthy worlds;
uncertain journals/backups are retained and cannot authorize a guessed restoration.
Terminal directories are retired before deletion, and cleanup failure cannot turn
a known commit into a failed save. Discover pending region transactions once per
native world lease, invalidating that discovery after an uncertain result. Routing
uses immutable published views: preview/render queries never wait for disk writes,
unchanged claims skip replacement, and stale writes are checked inside publication's
lease using the change token captured there. Relocation validates both mirror IDs,
counterpart journals and all entries before any move; it has no cross-filesystem
copy-and-delete fallback.

Run long file operations through `OfflineTasks`; progress and UI callbacks cannot
decide persistence success. `OperationFailures` reports non-chunk file problems
through bounded, coalesced local chat/toasts. Keep those separate from chunk-state
receipts. Frozen retries back off, unchanged malformed reads are negatively cached,
and admission stops when the retained backlog/heap budget is exceeded. Never evict
unacknowledged data to satisfy a cache limit. This queue remains memory-only.
