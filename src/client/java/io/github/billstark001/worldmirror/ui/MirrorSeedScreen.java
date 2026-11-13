package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.download.SeedConversion;
import io.github.billstark001.worldmirror.download.WorldGraphService;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.util.OfflineTasks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Offline seed conversion for one verified mirror save. */
final class MirrorSeedScreen extends StatusScreenApi {
    private final Screen parent;
    private final java.nio.file.Path folder;
    private final Runnable returned;
    private String nodeId;
    private boolean loaded, seeded, blendingEnabled, busy, blend, remove, editingSeed, changed;
    private String seed = "";
    private String failure;
    private Long currentSeed;
    private SeedConversion.Plan pending;

    MirrorSeedScreen(Screen parent, String nodeId, java.nio.file.Path folder) {
        this(parent, nodeId, folder, () -> {});
    }

    MirrorSeedScreen(Screen parent, String nodeId, java.nio.file.Path folder, Runnable returned) {
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
        int y = height / 2;
        int span = Math.min(300, Math.max(100, width - 24));
        if (!loaded && !busy)
            load();
        if (pending != null) {
            button(label("apply"), x - span / 2, height - 31, span / 2 - 3, this::apply);
            button(Component.translatable("gui.back"), x + 3, height - 31, span / 2 - 3, () -> {
                pending = null;
                rebuildWidgets();
            });
        } else {
            if (loaded && available) {
                if (!seeded || editingSeed) {
                    var field = addRenderableWidget(
                            new EditBox(font, x - span / 2, y - 45, span, 20, label("input")));
                    field.setMaxLength(128);
                    field.setHint(label("input"));
                    field.setValue(seed);
                    field.setResponder(value -> seed = value);
                    field.active = !busy;
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
            button(Component.translatable("gui.done"), x - 55, height - 31, 110, this::onClose);
        }
    }

    private void button(Component text, int x, int y, int width, Runnable action) {
        addRenderableWidget(Button.builder(text, ignored -> action.run()).bounds(x, y, width, 20).build())
                .active = !busy;
    }

    private void load() {
        run("WM-ReadSeed", () -> SeedConversion.inspectTarget(folder, nodeId), mode -> {
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
                () -> SeedConversion.preview(folder, nodeId, request), result -> pending = result);
    }

    private void apply() {
        var approved = pending;
        run("WM-ApplySeed",
                ()
                        -> {
                    SeedConversion.apply(approved);
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
                        WorldGraphService.instance().refreshAsync();
                    }
                });
    }

    private <T> void run(String name, OfflineTasks.Work<T> work, java.util.function.Consumer<T> completed) {
        if (busy)
            return;
        failure = null;
        busy = true;
        var client = Minecraft.getInstance();
        if (loaded)
            rebuildWidgets();
        OfflineTasks.start(name,
                ()
                        -> new Outcome<>(work.run(), null),
                error -> new Outcome<T>(null, error.getMessage()), client::execute, outcome -> {
                    if (ClientDialogs.currentScreen(client) != this)
                        return;
                    completed.accept(outcome.result());
                    if (outcome.error() != null)
                        failure = outcome.error();
                    rebuildWidgets();
                }, () -> busy = false);
    }

    private record Outcome<T>(T result, String error) {}

    @Override
    protected void renderContent(Canvas graphics) {
        int x = width / 2;
        graphics.centered(title, x, 16, 0xFFFFFFFF);
        if (busy) {
            graphics.centered(label("working"), x, 33, 0xFFFFFF55);
        } else if (currentSeed != null && pending == null) {
            graphics.centered(label("current", currentSeed), x, 35, 0xFFE0E0E0);
        }
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

    @Override
    public void onClose() {
        if (!busy) {
            showScreen(parent);
            if (changed)
                returned.run();
        }
    }

    private static Component label(String key, Object... arguments) {
        return Component.translatable("screen.worldmirror.seed." + key, arguments);
    }
}
