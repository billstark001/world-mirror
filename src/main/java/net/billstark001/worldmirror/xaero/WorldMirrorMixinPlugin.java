package net.billstark001.worldmirror.xaero;

import net.billstark001.worldmirror.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public class WorldMirrorMixinPlugin implements IMixinConfigPlugin {
    private static final String XAERO_GUI_MAP_MIXIN = "net.billstark001.worldmirror.xaero.mixin.XaeroGuiMapMixin";

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!XAERO_GUI_MAP_MIXIN.equals(mixinClassName)) {
            return true;
        }

        OverlayPlan plan = resolveOverlayPlan();
        XaeroWorldMapOverlayStatus.updatePlan(
                plan.xaeroLoaded,
                plan.xaeroVersion,
                plan.injectionMode,
                plan.exactEnabled,
                plan.mixinEnabled,
                plan.tailRenderEnabled
        );

        return plan.mixinEnabled;
    }

    private static OverlayPlan resolveOverlayPlan() {
        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("xaeroworldmap");
        boolean xaeroLoaded = container.isPresent();
        String version = container
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        ModConfig.XaeroOverlayInjectionMode mode = readInjectionMode();
        boolean exactSupported = XaeroWorldMapOverlayStatus.isExactVersionSupported(version);
        boolean exactEnabled = xaeroLoaded
                && exactSupported
                && mode != ModConfig.XaeroOverlayInjectionMode.DISABLED;
        boolean mixinEnabled = xaeroLoaded
                && mode != ModConfig.XaeroOverlayInjectionMode.DISABLED
                && (exactSupported || mode == ModConfig.XaeroOverlayInjectionMode.TAIL_FALLBACK);
        boolean tailRenderEnabled = xaeroLoaded
                && mode == ModConfig.XaeroOverlayInjectionMode.TAIL_FALLBACK;
        return new OverlayPlan(xaeroLoaded, version, mode, exactEnabled, mixinEnabled, tailRenderEnabled);
    }

    private static ModConfig.XaeroOverlayInjectionMode readInjectionMode() {
        try {
            return ModConfig.get().chunkMap.xaeroWorldMapOverlayInjection;
        } catch (RuntimeException ignored) {
            return ModConfig.XaeroOverlayInjectionMode.EXACT_ONLY;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    private record OverlayPlan(boolean xaeroLoaded,
                               String xaeroVersion,
                               ModConfig.XaeroOverlayInjectionMode injectionMode,
                               boolean exactEnabled,
                               boolean mixinEnabled,
                               boolean tailRenderEnabled) {}
}
