package net.billstark001.worlddownloader.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Registers the World Downloader settings screen with Mod Menu.
 * <p>
 * This class is only loaded when Mod Menu is present; it is listed as a
 * {@code modmenu} entrypoint in {@code fabric.mod.json}.
 */
public class ModMenuApiImpl implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::new;
    }
}
