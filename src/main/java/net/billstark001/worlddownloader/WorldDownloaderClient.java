package net.billstark001.worlddownloader;

import net.billstark001.worlddownloader.config.ModConfig;
import net.billstark001.worlddownloader.download.DownloadManager;
import net.billstark001.worlddownloader.ui.StatusScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public class WorldDownloaderClient implements ClientModInitializer {

    /**
     * Translation key for the keybinding category.
     * Using a plain string avoids the double-namespace expansion that produced
     * {@code key.category.minecraft.category.worlddownloader}.
     */
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("worlddownloader", "default"));

    private static KeyBinding toggleKey;
    private static KeyBinding exportKey;
    private static KeyBinding clearKey;
    private static KeyBinding statusKey;

    @Override
    public void onInitializeClient() {
        ModConfig.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.worlddownloader.toggle",
                InputUtil.Type.SCANCODE, 0x19 /* P */,
                CATEGORY));

        exportKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.worlddownloader.export",
                InputUtil.Type.SCANCODE, 0x18 /* O */,
                CATEGORY));

        clearKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.worlddownloader.clear",
                InputUtil.Type.SCANCODE, 0x26 /* L */,
                CATEGORY));

        statusKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.worlddownloader.status",
                InputUtil.Type.SCANCODE, 0x17 /* I */,
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                DownloadManager.toggle(client);
            }
            while (exportKey.wasPressed()) {
                DownloadManager.exportNow(client);
            }
            while (clearKey.wasPressed()) {
                DownloadManager.clearAll(client);
            }
            while (statusKey.wasPressed()) {
                StatusScreen.open();
            }
            DownloadManager.onClientTick(client);
        });
    }
}

