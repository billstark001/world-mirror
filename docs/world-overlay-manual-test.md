# In-world overlay rendering checks

Run these checks on Minecraft 1.21.11, 26.1.2, 26.2, and 26.3. Use a separate
run directory for each target so test saves, options, and external mods cannot
affect another target or the normal development world.

## Height and color settings

1. With a fresh config, confirm the fixed overlay height is 96 and the color mode
   is Session. Loading an existing config must preserve its height, including 0.
2. Check both Session and Map in the settings dropdown, then save and reopen it.
   Confirm each choice is translated and persists after restarting the client.
3. In Session, confirm pending captures are yellow, captures from this session
   are green, and historical chunks and replay imports are blue. Replay imports
   remain blue even if their timestamp is newer than the session start. Pending
   writes and cached chunks must not introduce the maps' extra queue colors.
4. In Map, compare saved and live chunk states against the built-in and Xaero
   maps. Capture-age gradients, source colors, and the four pending-capture/write
   colors must match; the world overlay retains its independent opacity.
5. Switch modes while the overlay is visible, including at coarse distances.
   Confirm the next capture updates the colors, queue priority remains correct,
   and both modes retain the grid, perimeter, opacity, and geometry bounds.

## Optional Xaero runtime

1. Leave `xaeroIntegration` unset and keep Xaero and the bridge out of the test
   directory's `mods` folder. Start the client, reach the title screen, enter a
   world, and return to the title screen. Confirm neither mod ID is in the loaded
   mod list and no optional bridge class is linked.
2. Install only the matching World Map in a second run directory. Launch with
   `-PxaeroIntegration=true`. Confirm the target's bridge is loaded and World
   Mirror registers its map overlay. Open the Xaero map and check its status layer.
3. For IDEA, reload the Gradle model with the integration property set. Changing
   only the generated run XML does not change the module's runtime dependencies.
4. Check the distribution JAR: Xaero and the bridge must remain optional and
   neither JAR may be nested under `META-INF/jars`.

## Depth, transparency, and box geometry

1. Enable the in-world overlay, use height 96 and opacity 60%, and prepare coverage
   over water below that height. Include a solid column crossing the overlay.
2. From above the box, look down at the water. The front overlay must tint both
   water and solid ground below it consistently. The column above the overlay
   must retain its original color; the part below the overlay should be tinted.
3. From below the box, look up at clouds behind it. Cloud silhouettes must not
   erase the overlay. Compare fill over clear sky from above and below: the
   fill must be applied once, not doubled by a second box cap.
4. View the box from each side and a corner. Keep the one-block-high walls and
   perimeter; rear walls must not add another layer of fill through the front.
5. Toggle the interior grid and change opacity. Grid visibility must not change
   the fill's opacity. On the classic path, the fill must not paint over its grid.
6. On 26.3 repeat with improved transparency both disabled and enabled. The
   classic path draws after composition; the improved path uses native OIT.
   Switching modes must neither duplicate the overlay nor leave stale geometry.
7. Check F1, the overlay toggle, changing worlds, disconnecting, and exiting.
   Drawing resources must be released on renderer shutdown.

The classic path retains scene depth testing and does not write overlay depth.
Its purpose is a readable coverage overlay; it does not implement full physical
transparency for coverage behind nearer water or glass. Native OIT handles those
transparent intersections on Minecraft 26.3.

## Large histories, shared buckets, and status semantics

1. In an isolated test save, prepare roughly 100,000 recorded chunks, including
   sparse distant records. Zoom and pan both maps horizontally and diagonally;
   clipped edge buckets must not inherit records outside the visible range.
2. Alternate the built-in map, Xaero, and Session/Map world-overlay modes. Check
   that views keep their own results while reusing complete source buckets.
   Switch worlds and dimensions and confirm old live colors disappear.
3. With a fixed view, allow capture-age colors to advance. Same-color boundary
   bands must change without a source-data update. Leave empty regions in the
   test area: high-zoom work must follow stored records, not empty coordinate area.
4. In Session, older cached captures can be blue even before they reach disk.
   In the built-in map, check red failed crosses, red/green partial markers, and
   purple conflict hints without replacing base colors. Cells below eight GUI
   pixels use dots; normal queued, writing, and successful states add no glyphs.
   Inspect all export states and terrain/entity and metadata results in its tooltip.
   Xaero and both world-overlay modes must show no export or conflict corner glyphs.
5. Compare a uniform 64 by 64 chunk grid with and without the interior grid.
   Every grid line and the perimeter must remain visible after line merging;
   toggling the grid must not change fill opacity.

Automatic regression tests cover CPU work counts and these data projections.
Measure actual frame spacing separately; a passing headless test is not an FPS
benchmark or a completed shader/OIT compatibility check.

## Export failures and notifications

Use only an isolated copy/test fixture, never the player's active save.

1. Introduce an unreadable entity region while exporting valid terrain. Confirm
   terrain still commits, the built-in map shows partial success, and a later
   terrain-only success does not hide the unresolved entity error.
2. Block terrain, index, and metadata commits separately. Check failed/partial
   markers in the built-in map, retained retry work, and the status screen's counts/stage.
   A metadata-only retry must restore success without rewriting confirmed terrain.
3. Exercise automatic periodic/adaptive exports as well as manual exports. Confirm
   the first error appears in chat and a top-right toast, repeated errors merge into
   30-second reminders, and escalation/recovery do not flood chat. Disconnect before
   delivery and reconnect; retain the latest notice without replaying every attempt.
4. Retry while capturing a newer revision, change dimension/source, and clear/evict
   live caches. Old receipts must not complete new captures; frozen retry markers
   must stay with their original source. After restarting the client, historical
   terrain remains covered and its tooltip does not infer entity completion.
5. Repeat the large-history zoom/pan and stationary-camera checks with mixed states.
   Failure counts must survive aggregation; geometry must be reused until its inputs change.
   Export status changes must not add glyphs or geometry to Xaero or world overlays.
   Ongoing region writes must respect the 0.5/1/5-second live refresh cadence as
   viewport extent grows. Confirm that chat remains quiet between reminder deadlines.

## Retained classic-renderer geometry

On Minecraft 26.2 and the classic path in 26.3, keep the camera position fixed
while looking around: uploaded geometry can be reused while view uniforms change.
Then move the camera across and below the overlay, change opacity, and replace
coverage. Geometry must update, retain sorting and camera-facing walls, and avoid
stale vertices. Toggle the overlay off and reload the renderer to check resource
release. Repeat the earlier 26.3 OIT checks; retained classic buffers do not replace
native improved-transparency submission.
