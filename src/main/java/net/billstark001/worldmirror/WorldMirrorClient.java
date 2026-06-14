package net.billstark001.worldmirror;

import com.mojang.blaze3d.platform.InputConstants;
import net.billstark001.worldmirror.config.ModConfig;
import net.billstark001.worldmirror.download.DownloadManager;
import net.billstark001.worldmirror.ui.StatusScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

@Environment(EnvType.CLIENT)
public class WorldMirrorClient implements ClientModInitializer {

    /**
     * Translation key for the keybinding category.
     * Using a plain string avoids the double-namespace expansion that produced
     * {@code key.category.minecraft.category.worldmirror}.
     */
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("worldmirror", "default"));

    private static KeyMapping toggleKey;
    private static KeyMapping exportKey;
    private static KeyMapping clearKey;
    private static KeyMapping statusKey;

    @Override
    public void onInitializeClient() {
        ModConfig.register();

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.toggle",
                InputConstants.Type.SCANCODE, 0x19 /* P */,
                CATEGORY));

        exportKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.export",
                InputConstants.Type.SCANCODE, 0x18 /* O */,
                CATEGORY));

        clearKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.clear",
                InputConstants.Type.SCANCODE, 0x26 /* L */,
                CATEGORY));

        statusKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.status",
                InputConstants.Type.SCANCODE, 0x17 /* I */,
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
            DownloadManager.onClientTick(client);
        });

        // Apply the configured on-join behaviour whenever the player enters a world.
        // ClientPlayConnectionEvents.JOIN fires after the world object is available,
        // which is exactly when we need it.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                DownloadManager.onJoinWorld(client));

        // Reset lifecycle tracking state when the player leaves a server / world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                DownloadManager.onLeaveWorld());
    }
}

