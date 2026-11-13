package io.github.billstark001.worldmirror;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.config.EnumDropdownGui;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import io.github.billstark001.worldmirror.ui.map.ChunkMapScreen;
import io.github.billstark001.worldmirror.ui.map.ChunkWorldOverlay;
#if MC_VERSION >= 260300
import io.github.billstark001.worldmirror.ui.map.ChunkWorldOverlayGeometry;
#else
#endif
import io.github.billstark001.worldmirror.ui.StatusScreen;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.xaero.XaeroBridgeOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
#if MC_VERSION >= 260300
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.LevelResource;
#elif MC_VERSION >= 260102
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import org.lwjgl.glfw.GLFW;
#else
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import org.lwjgl.glfw.GLFW;
#endif

import io.github.billstark001.worldmirror.download.WorldGraphService;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

import java.nio.file.Path;

@Environment(EnvType.CLIENT)
public class WorldMirrorClient implements ClientModInitializer {
    /**
     * Translation key for the keybinding category.
     * Using a plain string avoids the double-namespace expansion that produced
     * {@code key.category.minecraft.category.worldmirror}.
     */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("worldmirror", "default"));

    private static KeyMapping toggleKey;
    private static KeyMapping exportKey;
    private static KeyMapping clearKey;
    private static KeyMapping statusKey;
    private static KeyMapping chunkMapKey;
    private static KeyMapping worldOverlayKey;

    @Override
    public void onInitializeClient() {
        ModConfig.register();
        EnumDropdownGui.register();
        ChunkDatabase.configureSqliteNativeDirectory();
        installXaeroBridgeOverlay();

#if MC_VERSION >= 260300
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_U, CATEGORY));

        exportKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.export", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, CATEGORY));

        clearKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.clear", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, CATEGORY));

        statusKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.status", InputConstants.Type.KEYBOARD, InputConstants.KEY_I, CATEGORY));

        chunkMapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.chunkMap", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, CATEGORY));

        worldOverlayKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.worldmirror.worldOverlay",
                InputConstants.Type.KEYBOARD, InputConstants.KEY_K,
#elif MC_VERSION >= 260102
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, CATEGORY));

        exportKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.export", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));

        clearKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.clear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY));

        statusKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.status", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, CATEGORY));

        chunkMapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.chunkMap", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY));

        worldOverlayKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.worldmirror.worldOverlay", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K,
#else
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.worldmirror.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, CATEGORY));

        exportKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.worldmirror.export", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));

        clearKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.worldmirror.clear", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY));

        statusKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.worldmirror.status", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, CATEGORY));

        chunkMapKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.worldmirror.chunkMap", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY));

        worldOverlayKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.worldmirror.worldOverlay", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K,
#endif
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                DownloadManager.toggle(client);
            }
            while (exportKey.consumeClick()) {
                DownloadManager.exportNow(client);
            }
            while (clearKey.consumeClick()) {
                DownloadManager.clearAll(client);
            }
            while (statusKey.consumeClick()) {
                StatusScreen.open();
            }
            while (chunkMapKey.consumeClick()) {
                ChunkMapScreen.open();
            }
            while (worldOverlayKey.consumeClick()) {
                ChunkWorldOverlay.toggleForCurrentWorld(client);
            }
            DownloadManager.onClientTick(client);
#if MC_VERSION >= 260102
#else
            ChunkWorldOverlay.capture(client, client.options.hideGui);
#endif
        });
        ClientChunkEvents.CHUNK_LOAD.register(
                (world, chunk) -> DownloadManager.queueChunkCapture(world, chunk.getPos(), "chunk-load"));
        ClientChunkEvents.CHUNK_UNLOAD.register(DownloadManager::captureChunkBeforeUnload);
#if MC_VERSION >= 260300
        LevelRenderEvents.END_MAIN.register(context -> DownloadManager.recordWorldFrame());
        LevelRenderEvents.END_EXTRACTION.register(context
                -> ChunkWorldOverlay.capture(net.minecraft.client.Minecraft.getInstance(),
                        net.minecraft.client.Minecraft.getInstance().gui.hud.isHidden()));
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!net.minecraft.client.Minecraft.getInstance().gameRenderer.useImprovedTransparency())
                return;
            ChunkWorldOverlay.OverlaySnapshot overlay = ChunkWorldOverlay.snapshot();
            if (overlay.isEmpty() || context.levelState().cameraRenderState == null
                    || context.levelState().cameraRenderState.pos == null)
                return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            context.submitNodeCollector().submitCustomGeometry(
                    context.poseStack(), RenderTypes.debugFilledBox(), (pose, consumer) -> {
                        ChunkWorldOverlayGeometry.renderFilled(pose, consumer, overlay, camera);
                        ChunkWorldOverlayGeometry.renderTopEdges(pose, consumer, overlay, camera);
                    });
        });
#elif MC_VERSION >= 260200
        LevelRenderEvents.END_MAIN.register(context -> DownloadManager.recordWorldFrame());
        LevelRenderEvents.END_EXTRACTION.register(context
                -> ChunkWorldOverlay.capture(net.minecraft.client.Minecraft.getInstance(),
                        net.minecraft.client.Minecraft.getInstance().gui.hud.isHidden()));

#elif MC_VERSION >= 260102
        LevelRenderEvents.END_MAIN.register(context -> DownloadManager.recordWorldFrame());
        LevelRenderEvents.END_EXTRACTION.register(context
                -> ChunkWorldOverlay.capture(net.minecraft.client.Minecraft.getInstance(),
                        net.minecraft.client.Minecraft.getInstance().options.hideGui));

#else
                WorldRenderEvents.END_MAIN.register(context -> DownloadManager.recordWorldFrame());

#endif

        // Apply the configured on-join behaviour whenever the player enters a world.
        // ClientPlayConnectionEvents.JOIN fires after the world object is available,
        // which is exactly when we need it.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> WorldGraphService.instance().refreshAsync());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof SelectWorldScreen || screen instanceof JoinMultiplayerScreen) {
                WorldGraphService.instance().refreshAsync();
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(DownloadManager::shutdown);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));

        // Reset lifecycle tracking state when the player leaves a server / world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DownloadManager.onLeaveWorld(client);
            MirrorWorldContext.leave();
            WorldGraphService.instance().leave();
        });
    }

    private static void onJoin(net.minecraft.client.Minecraft client) {
        MirrorWorldContext.enter(
                currentLocalSave(client), SharedConstants.getCurrentVersion().dataVersion().version());
        WorldGraphService.instance().enter(client);
        DownloadManager.onJoinWorld(client);
    }

    private static Path currentLocalSave(net.minecraft.client.Minecraft client) {
        try {
            return client.getSingleplayerServer() != null
                    ? client.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void installXaeroBridgeOverlay() {
        if (!FabricLoader.getInstance().isModLoaded("xaero_world_map_bridge"))
            return;
        try {
            XaeroBridgeOverlay.install();
        } catch (LinkageError error) {
            WMLogger.warn(
                    "Xaero World Map Bridge is present but could not be linked; skipping overlay", error);
        }
    }
}
#if MC_VERSION >= 260300
#else

#endif
