# World Mirror

World Mirror is a client-side Fabric mod that saves the parts of a Minecraft world your
client can see, then turns them into a local singleplayer save. It works on multiplayer
servers, Realms, and singleplayer worlds; the server does not need to install anything.

Use it to archive a build, keep a personal record of an area, or take a permitted server
world offline. Only download worlds where you have permission to do so.

## Important before you start

- Press **P** after joining the world. World Mirror does not continuously record unless
  the action bar says the download session is active.
- Pressing **O** exports data that has already been captured. It does not start a download
  session or magically fetch distant chunks.
- Mirrors go to `<.minecraft>/downloaded_worlds/` by default. Minecraft does **not** show
  that folder in the Singleplayer menu. Choose **Saves Folder** in World Mirror's settings
  if you want the mirror to appear there automatically.
- A client-side mod can only save information sent to your client. Open containers whose
  contents matter, and expect server-only data to be missing.

## Quick start

1. Install the World Mirror JAR that exactly matches your Minecraft version.
2. Join the world and press **P**. Check that the action bar says World Mirror is active.
3. Explore the area you want to save. Open chests, barrels, furnaces, and other containers
   whose inventories you want to keep.
4. Press **O** when you want an immediate export, then wait for the completion message.
5. Press **P** again to stop the download session.
6. Open the mirror from Singleplayer if you selected **Saves Folder**. Otherwise it remains
   under `<.minecraft>/downloaded_worlds/<mirror-name>/` until you move it or change the
   per-world save location.

Automatic export on stop is available in settings but is disabled by default, so using
**O before stopping** is the safest simple workflow.

## Controls

| Key | Action |
| --- | --- |
| **P** | Start or stop the download session |
| **O** | Export captured changes now |
| **I** | Open World Mirror's status and settings screen |
| **M** | Open the built-in chunk map |
| **K** | Toggle the in-world chunk overlay for the current world type |
| **L** | Clear the in-memory capture cache |

All controls can be rebound under *Options → Controls → World Mirror*. Clearing the cache
with **L** does not delete mirror files already written to disk or cancel exports
already queued with their own captured data.

## What World Mirror saves

- Terrain and block states from loaded chunks in the Overworld, Nether, End, and observed
  custom dimensions.
- Block entities such as signs, banners, player heads, beacons, and lecterns.
- Contents of containers you open while recording, including double chests.
- Block changes while recording, including removal of the final block in a chunk.
- Best-effort snapshots of client-visible mobs, vehicles, paintings, item frames, armour
  stands, dropped items, and rider/passenger groups. Moves and despawns are reconciled in
  areas the client can safely observe without deleting last-known entities from unloaded
  chunks.
- Changes over time, with periodic background exports and an optional experimental
  adaptive mode for busier sessions.

The built-in map shows recorded chunks and unresolved conflicts. Drag to pan, use the
mouse wheel to zoom, and hover a chunk for its coordinates, age, and source.
Purple borders mark unresolved conflicts. Orange marks other data sources, including
known third-party tools. Separate corner markers show queued exports (yellow angle),
writing (light gray), all writes failed (red cross), partial success (red/green), and
complete success (green check). Hover for terrain/entity and metadata details.
Markers survive live-cache eviction during this client run; historical terrain rows
do not imply entity completion after restarting the client.

Enable the in-world chunk overlay in Chunk Map settings, or press **K**, to show
one-block-high coverage boxes at an adjustable height. Source worlds and mirror
worlds have separate switches, both disabled by default. The default fixed height
is Y=96; existing configured heights are preserved. Choose **Session** colors
(yellow for queued captures, green for current-session captures, blue for historical
chunks and replay imports) or **Map** colors (the same age gradient, source colors,
and live queue colors as both chunk maps) in settings. The boxes keep a consistent
fill opacity from above and below; water and clouds behind them no longer erase
their color. Solid terrain still hides boxes behind it. Session colors show what
you have captured, including unsaved data; they are not a save-success indicator.
At long distances, a single tile can summarize several chunks.
The five export markers appear in both overlay modes and Xaero, preserving their
base colors. Failures take priority in coarse tiles; a red accent persists during
retry, and conflict borders remain purple.
Coarse world tiles use compact colored squares; partial failure remains light red.
Wide views refresh less often to bound work with large histories.

Automatic and manual export failures appear in chat and top-right toasts, merged
per source with 30-second reminders. Escalation and recovery can arrive sooner;
deliveries are spaced by at least two seconds. The status screen shows persistent
counts and the failure stage. Unfinished data is retained for retry.

### What it cannot save perfectly

World Mirror is not a server backup. Unopened inventories, server datapacks, structure
metadata, hidden entity state, and chunks the server never sends may be absent. Entities
in unloaded chunks keep their last client-known state until the chunk is observed again.
Uncaptured terrain is intentionally void in the generated save.
Different logical worlds behind the same server address share one source identity.

## Save locations and conflicts

Press **I**, open **Settings**, then choose **Global Settings**. Optional Mod Menu provides
another shortcut to the same screen.

Enum settings are selection-only lists: open the control to see every translated choice,
then select one item. Per-world Save Location and Conflict Strategy use the same interaction.

- **Downloaded Folder** (default): saves to `<.minecraft>/downloaded_worlds/`; useful for
  keeping mirrors separate, but not listed in Singleplayer.
- **Saves Folder**: saves to `<.minecraft>/saves/`; immediately visible in Singleplayer.

When an incoming chunk meets an existing local chunk, choose one of these strategies:

- **Overwrite** (default): use the eligible incoming server update.
- **Ignore**: keep the local chunk and write only chunks that are not already present.
- **Manual**: keep the local chunk and store the incoming version for review. Resolve it
  from the chunk map or use **Overwrite All / Discard All** on the Conflicts tab.

Keep the target mirror closed while exporting, applying conflicts, upgrading it,
or copying world settings. Review its conflicts from the original source world.
If a mirror export fails, World Mirror keeps the unfinished data and retries while
the game is running, including after you leave the source world. An entity failure
does not prevent terrain from continuing to save. Terrain saved with entities still
pending appears as partial success. Periodic, adaptive, and manual failures all use
the merged chat/toast notices and status-screen health described above.

On exit, World Mirror lets accepted writes finish and makes a final attempt at
queued captured data. This does not guarantee recovery from a continuing storage
failure: remaining retries are held in memory and do not survive closing the game.
Use **O** and check its result before quitting.

If applying a conflict fails, its incoming version remains pending. A newer version
that arrives while you apply an older one also remains available for review.

**Export Nearby Region** on the status screen creates a separate save in the Singleplayer
folder from the chunks and visible non-player entities currently around you. You start
at the captured position in the captured dimension, including the Nether or End. Exports
with the same name receive different folders. It is useful when you only need one area
instead of a continuing mirror. Custom dimensions and biomes may require the source
server's matching data packs or mods to be available locally. Nearby exports are
one-off operations; if one fails, start a new nearby export to try again.

## Installation

| Minecraft | Required Java |
| --- | --- |
| 1.21.11 | Java 21 or newer |
| 26.1.2 | Java 25 or newer |
| 26.2 | Java 25 or newer |
| 26.3 | Java 25 or newer |

Install:

1. [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer.
2. The matching [Fabric API](https://modrinth.com/mod/fabric-api).
3. The World Mirror JAR for your exact Minecraft version.

Cloth Config and SQLite JDBC are bundled. [Mod Menu](https://modrinth.com/mod/modmenu)
is optional, and LibGui is not required.

### Optional Xaero's World Map overlay

World Mirror works without Xaero. To display World Mirror's chunk status on Xaero's
fullscreen map, install both:

- [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) for your Minecraft version; and
- the matching Minecraft-version Fabric file from
  [Xaero World Map Bridge](https://github.com/billstark001/xaero-world-map-bridge/releases).

Use the bridge's matching Fabric file from release 0.1.2 or newer for every
Minecraft version; bridge 0.1.0 restricts newer Xaero releases. For Minecraft
26.3, use Xaero's World Map 1.46.x and the bridge's 26.3 file.

## Reporting a performance problem

Open Global Settings, enable **Performance Diagnostic Logging**, reproduce the problem for
at least 30 seconds, then attach `latest.log` and `config/worldmirror.json` to the report.
Disable the option afterward. Download startup records the complete global and current-world
configuration in `[perf]` lines, followed by the periodic performance telemetry; these lines
are not needed during normal play.
