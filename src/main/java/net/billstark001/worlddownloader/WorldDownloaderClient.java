package net.billstark001.worlddownloader;

import net.billstark001.worlddownloader.config.ModConfig;
import net.billstark001.worlddownloader.download.DownloadManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

@Environment(EnvType.CLIENT)
public class WorldDownloaderClient implements ClientModInitializer {

    /**
     * Translation key for the keybinding category.
     * The lang file supplies the human-readable name for this key.
     * Using a plain string (not {@code KeyBinding.Category.create(Identifier)}) avoids
     * the double-namespace expansion that produced
     * {@code key.category.minecraft.category.worlddownloader}.
     */
    private static final String CATEGORY = "key.categories.worlddownloader";

    private static KeyBinding toggleKey;
    private static KeyBinding exportKey;
    private static KeyBinding clearKey;

    @Override
    public void onInitializeClient() {
        ModConfig.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.worlddownloader.toggle",
                InputUtil.Type.SCANCODE, 80 /* P */,
                CATEGORY));

        exportKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.worlddownloader.export",
                InputUtil.Type.SCANCODE, 79 /* O */,
                CATEGORY));

        clearKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.worlddownloader.clear",
                InputUtil.Type.SCANCODE, 76 /* L */,
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
            DownloadManager.onClientTick(client);
        });
    }
}

