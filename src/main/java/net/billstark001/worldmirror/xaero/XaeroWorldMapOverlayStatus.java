package net.billstark001.worldmirror.xaero;

import net.billstark001.worldmirror.config.ModConfig;
import net.billstark001.worldmirror.util.WMLogger;
import net.minecraft.network.chat.Component;

import java.util.Set;

public final class XaeroWorldMapOverlayStatus {
    public static final String SUPPORTED_XAERO_WORLD_MAP_VERSION = "1.41.1";
    private static final Set<String> SUPPORTED_EXACT_XAERO_WORLD_MAP_VERSIONS =
            Set.of(SUPPORTED_XAERO_WORLD_MAP_VERSION);

    private static final ThreadLocal<Boolean> exactRenderedThisPass =
            ThreadLocal.withInitial(() -> false);

    private static volatile Capability capability = Capability.UNKNOWN;
    private static volatile String xaeroVersion = "";
    private static volatile ModConfig.XaeroOverlayInjectionMode injectionMode =
            ModConfig.XaeroOverlayInjectionMode.EXACT_ONLY;
    private static volatile boolean exactMixinEnabled;
    private static volatile boolean tailMixinEnabled;
    private static volatile boolean tailFallbackRenderEnabled;
    private static volatile String lastLogMessage = "";

    private XaeroWorldMapOverlayStatus() {}

    public static void updatePlan(boolean xaeroLoaded,
                                  String version,
                                  ModConfig.XaeroOverlayInjectionMode mode,
                                  boolean exactEnabled,
                                  boolean tailEnabled,
                                  boolean tailRenderEnabled) {
        xaeroVersion = version == null ? "" : version;
        injectionMode = mode;
        exactMixinEnabled = exactEnabled;
        tailMixinEnabled = tailEnabled;
        tailFallbackRenderEnabled = tailRenderEnabled;

        if (mode == ModConfig.XaeroOverlayInjectionMode.DISABLED) {
            setCapability(Capability.DISABLED, "disabled by config");
        } else if (!xaeroLoaded) {
            setCapability(Capability.MISSING_MOD, "Xaero's World Map is not loaded");
        } else if (exactEnabled) {
            setCapability(Capability.EXACT_READY, "using exact injection for Xaero's World Map " + xaeroVersion);
        } else if (tailRenderEnabled) {
            setCapability(Capability.TAIL_FALLBACK_READY, "using tail fallback for Xaero's World Map " + xaeroVersion);
        } else {
            setCapability(Capability.UNSUPPORTED_VERSION, "unsupported Xaero's World Map " + xaeroVersion);
        }
    }

    public static void resetRenderPass() {
        exactRenderedThisPass.set(false);
    }

    public static void markExactRenderedThisPass() {
        exactRenderedThisPass.set(true);
        setCapability(Capability.EXACT_ACTIVE, "rendered with exact injection");
    }

    public static boolean wasExactRenderedThisPass() {
        return exactRenderedThisPass.get();
    }

    public static void markTailFallbackRendered() {
        setCapability(Capability.TAIL_FALLBACK_ACTIVE, "rendered with tail fallback");
    }

    public static boolean isTailFallbackRenderEnabled() {
        return tailFallbackRenderEnabled;
    }

    public static void markExactUnavailableThisPass() {
        setCapability(Capability.UNAVAILABLE, "exact injection did not match and tail fallback is disabled");
    }

    public static Component statusComponent() {
        if (!ModConfig.get().chunkMap.showXaeroWorldMapOverlay) {
            return Component.translatable("screen.worldmirror.status.xaeroOverlay.disabled");
        }
        return Component.translatable(capability.translationKey())
                .append(statusSuffix());
    }

    public static boolean isExactVersionSupported(String version) {
        return SUPPORTED_EXACT_XAERO_WORLD_MAP_VERSIONS.contains(version);
    }

    public static boolean isLoadedXaeroVersion(String version) {
        return xaeroVersion.equals(version);
    }

    private static String statusSuffix() {
        if (xaeroVersion == null || xaeroVersion.isBlank()) return "";
        return " (" + xaeroVersion + ")";
    }

    private static void setCapability(Capability newCapability, String logDetail) {
        capability = newCapability;
        String message = "Xaero World Map overlay: " + logDetail;
        if (!message.equals(lastLogMessage)) {
            lastLogMessage = message;
            switch (newCapability) {
                case UNSUPPORTED_VERSION -> WMLogger.warn(message);
                case UNAVAILABLE -> WMLogger.warn(message);
                case TAIL_FALLBACK_READY, TAIL_FALLBACK_ACTIVE -> WMLogger.info(message);
                default -> WMLogger.debug(message);
            }
        }
    }

    public enum Capability {
        UNKNOWN("screen.worldmirror.status.xaeroOverlay.unknown"),
        DISABLED("screen.worldmirror.status.xaeroOverlay.disabled"),
        MISSING_MOD("screen.worldmirror.status.xaeroOverlay.missing"),
        UNSUPPORTED_VERSION("screen.worldmirror.status.xaeroOverlay.unsupported"),
        UNAVAILABLE("screen.worldmirror.status.xaeroOverlay.unavailable"),
        EXACT_READY("screen.worldmirror.status.xaeroOverlay.exact"),
        EXACT_ACTIVE("screen.worldmirror.status.xaeroOverlay.exact"),
        TAIL_FALLBACK_READY("screen.worldmirror.status.xaeroOverlay.tail"),
        TAIL_FALLBACK_ACTIVE("screen.worldmirror.status.xaeroOverlay.tail");

        private final String translationKey;

        Capability(String translationKey) {
            this.translationKey = translationKey;
        }

        private String translationKey() {
            return translationKey;
        }
    }
}
