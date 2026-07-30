package io.github.billstark001.worldmirror.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

@Environment(EnvType.CLIENT)
public final class ClientScreens {
    private ClientScreens() {}

    public static void set(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    public static void setLater(Screen screen) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.setScreen(screen));
    }
}
