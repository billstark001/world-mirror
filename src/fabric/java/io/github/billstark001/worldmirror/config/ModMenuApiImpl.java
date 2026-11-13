package io.github.billstark001.worldmirror.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.autoconfig.AutoConfigClient;

/**
 * Registers the World Mirror settings screen with Mod Menu.
 * <p>
 * This class is only loaded when Mod Menu is present; it is listed as a
 * {@code modmenu} entrypoint in {@code fabric.mod.json}.
 * <p>
 * Opens the global World Mirror configuration.
 */
public class ModMenuApiImpl implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            EnumDropdownGui.register();
            return AutoConfigClient.getConfigScreen(ModConfig.class, parent).get();
        };
    }
}
