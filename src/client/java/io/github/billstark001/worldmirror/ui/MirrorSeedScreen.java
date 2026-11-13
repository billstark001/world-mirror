package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.SeedConversion;
import io.github.billstark001.worldmirror.download.SeedConversion.Phase;
import io.github.billstark001.worldmirror.download.SeedConversion.Progress;
import io.github.billstark001.worldmirror.download.SeedConversion.ProgressSnapshot;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Offline seed conversion for one verified mirror save. */
final class MirrorSeedScreen extends StatusScreenApi {
    private final Screen parent;
    private final Path folder;
    private final Runnable returned;
    private String nodeId;
    private boolean loaded, seeded, blendingEnabled, blend, remove, editingSeed, changed;
    private String seed = "";
    private String failure;
    private Long currentSeed;
    private SeedConversion.Plan pending;
    private Progress task;
    private Button cancelButton;

    MirrorSeedScreen(Screen parent, String nodeId, Path folder) {
        this(parent, nodeId, folder, () -> {});
    }

    MirrorSeedScreen(Screen parent, String nodeId, Path folder, Runnable returned) {
        super(label("title"));
        this.parent = parent;
        this.folder = folder;
        this.nodeId = nodeId;
        this.returned = returned;
    }

    private boolean available;

    @Override
    protected void init() {
        int x = width / 2;
        int y = Math.max(97, height / 2);
        int span = Math.min(300, Math.max(100, width - 24));
        cancelButton = null;
        if (!loaded && task == null)
            load();
        if (pending != null) {
            if (task == null) {
                button(label("apply"), x - span / 2, height - 31, span / 2 - 3, this::apply);
                button(Component.translatable("gui.back"), x + 3, height - 31, span / 2 - 3, () -> {
                    pending = null;
                    rebuildWidgets();
                });
            }
        } else {
            if (loaded && available) {
                if (!seeded || editingSeed) {
                    var field = addRenderableWidget(
                            new EditBox(font, x - span / 2, y - 45, span, 20, label("input")));
                    field.setMaxLength(128);
                    field.setHint(label("input"));
                    field.setValue(seed);
                    field.setResponder(value -> seed = value);
                    field.active = task == null;
                    if (field.active)
                        setInitialFocus(field);
                    button(label(blend ? "blendOn" : "blendOff"), x - span / 2, y - 21, span, () -> {
                        blend = !blend;
                        rebuildWidgets();
                    });
                    button(label("previewSet"), x - span / 2, y + 3, span, () -> {
                        var value = WorldStructureCreator.parseSeed(seed);
                        if (value.isEmpty()) {
                            failure = label("empty").getString();
                            return;
                        }
                        preview(seeded ? SeedConversion.Request.reseed(value.getAsLong(), blend)
                                       : SeedConversion.Request.set(value.getAsLong(), blend));
                    });
                    if (seeded)
                        button(Component.translatable("gui.back"), x - span / 2, y + 27, span, () -> {
                            editingSeed = false;
                            rebuildWidgets();
                        });
                } else {
                    button(label("change"), x - span / 2, y - 50, span, () -> {
                        editingSeed = true;
                        seed = "";
                        blend = blendingEnabled;
                        rebuildWidgets();
                    });
                    button(label(remove ? "deleteOn" : "deleteOff"), x - span / 2, y - 26, span, () -> {
                        remove = !remove;
                        rebuildWidgets();
                    });
                    button(label("previewRemove"), x - span / 2, y - 2, span,
                            () -> preview(SeedConversion.Request.remove(remove)));
                    if (blendingEnabled)
                        button(label("previewRegenerate"), x - span / 2, y + 22, span,
                                () -> preview(SeedConversion.Request.regenerateBorder()));
                }
            }
            if (task == null)
                button(Component.translatable("gui.done"), x - 55, height - 31, 110, this::onClose);
        }
        if (task != null) {
            cancelButton = addRenderableWidget(
                    Button.builder(Component.translatable("gui.cancel"), ignored -> onClose())
                            .bounds(x - 55, height - 31, 110, 20)
                            .build());
            cancelButton.active = task.snapshot().canCancel();
            setInitialFocus(cancelButton);
        }
    }

    private void button(Component text, int x, int y, int width, Runnable action) {
        addRenderableWidget(Button.builder(text, ignored -> action.run()).bounds(x, y, width, 20).build())
                .active = task == null;
    }

    private void load() {
        run("WM-ReadSeed", progress -> SeedConversion.inspectTarget(folder, nodeId, progress), mode -> {
            loaded = true;
            available = mode != null;
            if (mode != null) {
                seeded = mode.seeded();
                blendingEnabled = mode.blendingEnabled();
                currentSeed = mode.seed();
            }
        });
    }

    private void preview(SeedConversion.Request request) {
        run("WM-PreviewSeed",
                progress
                -> SeedConversion.preview(folder, nodeId, request, progress),
                result -> pending = result);
    }

    private void apply() {
        var approved = pending;
        run("WM-ApplySeed",
                progress
                -> {
                    SeedConversion.apply(approved, progress);
                    WorldGraphService.instance().refreshAsync();
                    return approved.nodeId();
                },
                success -> {
                    pending = null;
                    if (success != null) {
                        changed = true;
                        nodeId = success;
                        loaded = false;
                        remove = false;
                        editingSeed = false;
                    }
                });
    }

    @FunctionalInterface
    private interface SeedWork<T> {
        T run(Progress progress) throws Exception;
    }

    private <T> void run(String name, SeedWork<T> work, Consumer<T> completed) {
        if (task != null)
            return;
        failure = null;
        var progress = new Progress();
        task = progress;
        var client = Minecraft.getInstance();
        if (loaded)
            rebuildWidgets();
        OfflineTasks.start(name,
                ()
                        -> {
                    try {
                        return new Outcome<>(work.run(progress), null);
                    } catch (CancellationException cancelled) {
                        return new Outcome<T>(null, null);
                    }
                },
                error
                -> new Outcome<T>(null, error.getMessage()),
                client::execute,
                outcome
                -> {
                    boolean cancelled = progress.snapshot().phase() == Phase.CANCELLING;
                    if (cancelled)
                        WMLogger.info("Cancelled mirror seed check: folder=" + folder + " worker=" + name);
                    if (ClientDialogs.currentScreen(client) != this)
                        return;
                    if (cancelled) {
                        if (!loaded)
                            onClose();
                        else
                            rebuildWidgets();
                        return;
                    }
                    completed.accept(outcome.result());
                    if (outcome.error() != null)
                        failure = outcome.error();
                    rebuildWidgets();
                },
                ()
                        -> task = null,
                () -> {
                    task = null;
                    if (ClientDialogs.currentScreen(client) == this) {
                        loaded = true;
                        failure = label("interrupted").getString();
                        rebuildWidgets();
                    }
                });
    }

    private record Outcome<T>(T result, String error) {}

    @Override
    public void tick() {
        super.tick();
        if (task != null && cancelButton != null)
            cancelButton.active = task.snapshot().canCancel();
    }

    @Override
    protected void renderContent(Canvas graphics) {
        int x = width / 2;
        graphics.centered(title, x, 16, 0xFFFFFFFF);
        if (task != null) {
            renderProgress(graphics, task.snapshot());
            return;
        }
        if (currentSeed != null && pending == null)
            graphics.centered(label("current", currentSeed), x, 35, 0xFFE0E0E0);
        if (pending != null) {
            var preview = pending.preview();
            int top = 40;
            graphics.centered(label("removed", preview.removed()), x, top, 0xFFE0E0E0);
            graphics.centered(label("repaired", preview.repairedBiomes()), x, top + 13, 0xFFE0E0E0);
            graphics.centered(label("blended", preview.blended()), x, top + 26, 0xFFE0E0E0);
            graphics.centered(label("protected", preview.protectedChunks(), preview.unknownChunks()), x,
                    top + 39, 0xFFE0E0E0);
            if (preview.samples() > 0)
                graphics.centered(label("difference",
                                          String.format(Locale.ROOT, "%.1f", preview.meanHeightDifference()),
                                          preview.samples()),
                        x, top + 52, 0xFFE0E0E0);
            else
                graphics.centered(label("noSamples"), x, top + 52, 0xFFE0E0E0);
            if (preview.warnsAboutTerrain())
                graphics.centered(label("warning"), x, top + 65, 0xFFFFAA55);
        }
        if (failure != null)
            graphics.centered(Component.literal(font.plainSubstrByWidth(failure, width - 16)), x, height - 47,
                    0xFFFF6666);
    }

    private void renderProgress(Canvas graphics, ProgressSnapshot progress) {
        String key = switch (progress.phase()) {
            case CHECKING -> "working";
            case SCANNING -> "scanning";
            case VERIFYING -> "verifying";
            case APPLYING -> "applying";
            case CANCELLING -> "cancelling";
        };
        int center = width / 2;
        graphics.centered(label(key), center, 33, 0xFFFFFF55);
        int barWidth = Math.clamp(width - 16, 4, 160);
        int left = center - barWidth / 2;
        int top = 46;
        graphics.fill(left, top, left + barWidth, top + 5, 0xC0000000);
        if (progress.total() > 0) {
            int filled = (int) ((long) barWidth * progress.completed() / progress.total());
            if (filled > 0)
                graphics.fill(left, top, left + filled, top + 5, 0xFF80BDFF);
            return;
        }
        int segment = barWidth / 4;
        int range = barWidth - segment;
        int offset = Math.abs(Math.floorMod(System.nanoTime() / 10_000_000L, range * 2) - range);
        graphics.fill(left + offset, top, left + offset + segment, top + 5, 0xFF80BDFF);
    }

    @Override
    public void onClose() {
        if (task != null) {
            if (task.cancel() && cancelButton != null)
                cancelButton.active = false;
            return;
        }
        showScreen(parent);
        if (changed)
            returned.run();
    }

    @Override
    public void removed() {
        if (task != null)
            task.cancel();
        super.removed();
    }

    private static Component label(String key, Object... arguments) {
        return Component.translatable("screen.worldmirror.seed." + key, arguments);
    }
}
